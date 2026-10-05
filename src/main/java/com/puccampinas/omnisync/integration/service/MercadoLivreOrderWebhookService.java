package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.core.audit.*;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.sale.service.SaleLogService;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreNotificationRequest;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MercadoLivreOrderWebhookService {

    private static final String CHANNEL = "MERCADO_LIVRE";
    private static final String TOPIC_ORDERS_V2 = "orders_v2";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final MarketplaceIntegrationRepository marketplaceIntegrationRepository;
    private final MarketplaceTokenService marketplaceTokenService;
    private final MercadoLivreClient mercadoLivreClient;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final SaleLogService saleLogService;
    private final AuditService audit;
    private final MercadoLivreOrderTransactionService orderTransactionService;

    @Value("${mercadolivre.client-id:}")
    private String configuredApplicationId;

    @Autowired
    public MercadoLivreOrderWebhookService(
            MarketplaceIntegrationRepository marketplaceIntegrationRepository,
            MarketplaceTokenService marketplaceTokenService,
            MercadoLivreClient mercadoLivreClient,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            SaleLogService saleLogService,
            AuditService audit,
            MercadoLivreOrderTransactionService orderTransactionService
    ) {
        this.marketplaceIntegrationRepository = marketplaceIntegrationRepository;
        this.marketplaceTokenService = marketplaceTokenService;
        this.mercadoLivreClient = mercadoLivreClient;
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.saleLogService = saleLogService;
        this.audit = audit;
        this.orderTransactionService = orderTransactionService;
    }

    public MercadoLivreOrderWebhookService(
            MarketplaceIntegrationRepository marketplaceIntegrationRepository,
            MarketplaceTokenService marketplaceTokenService,
            MercadoLivreClient mercadoLivreClient,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            SaleLogService saleLogService,
            AuditService audit
    ) {
        this(marketplaceIntegrationRepository, marketplaceTokenService, mercadoLivreClient, productRepository,
                saleRepository, saleLogService, audit, null);
    }

    public Map<String, Object> handleNotification(MercadoLivreNotificationRequest notification) {
        validateNotification(notification);

        if (!TOPIC_ORDERS_V2.equals(notification.topic())) {
            return Map.of(
                    "processed", false,
                    "reason", "unsupported_topic",
                    "topic", notification.topic()
            );
        }

        String orderId = extractOrderId(notification.resource());
        MarketplaceIntegration integration = findIntegrationByMercadoLivreUserId(notification.userId());
        Long systemClientId = integration.getSystemClientId();
        String accessToken = marketplaceTokenService.getValidAccessToken(systemClientId, Marketplace.MERCADO_LIVRE);
        Map<String, Object> order = mercadoLivreClient.getOrder(accessToken, orderId);
        validateApplicationOwnership(integration, notification.applicationId());
        validateSellerOwnership(order, notification.userId());
        if (orderTransactionService != null) {
            return orderTransactionService.execute(() -> applyFetchedOrder(
                    systemClientId, notification, orderId, order, true));
        }
        return applyFetchedOrder(systemClientId, notification, orderId, order, false);
    }

    public Map<String, Object> processInbox(com.puccampinas.omnisync.integration.entity.MarketplaceWebhookInbox inbox) {
        MercadoLivreNotificationRequest notification = new MercadoLivreNotificationRequest(
                null,
                inbox.getResource(),
                Long.valueOf(inbox.getMarketplaceUserId()),
                inbox.getTopic(),
                inbox.getApplicationId(),
                inbox.getAttemptCount(),
                inbox.getProviderSentAt() == null ? null : inbox.getProviderSentAt().toString(),
                null
        );
        return handleNotification(notification);
    }

    private Map<String, Object> applyFetchedOrder(
            Long systemClientId,
            MercadoLivreNotificationRequest notification,
            String orderId,
            Map<String, Object> order,
            boolean lockProducts
    ) {
        String normalizedStatus = normalizeSaleStatus(order);
        List<OrderLineContext> lineContexts = extractOrderContexts(order);
        List<Map<String, Object>> lineResults = new ArrayList<>();

        Map<String, Product> productsByItem = resolveProducts(systemClientId, lineContexts, lockProducts);

        for (OrderLineContext context : lineContexts) {
            Product product = productsByItem.get(context.itemId());

            String externalReferenceId = buildExternalReferenceId(orderId, context.itemId());
            Optional<Sale> existingSale = lockProducts
                    ? saleRepository.findByIdempotencyKeyForUpdate(systemClientId, CHANNEL, externalReferenceId)
                    : saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(
                            systemClientId, CHANNEL, externalReferenceId);

            if (existingSale.isPresent()) {
                lineResults.add(updateExistingSale(
                        existingSale.get(),
                        product,
                        orderId,
                        order,
                        notification,
                        normalizedStatus,
                        context
                ));
                continue;
            }

            lineResults.add(createSale(
                    product,
                    orderId,
                    order,
                    notification,
                    normalizedStatus,
                    context
            ));
        }

        String effectiveStatus = lineResults.size() == 1
                ? String.valueOf(lineResults.getFirst().get("sale_status"))
                : normalizedStatus;
        return Map.of(
                "processed", true,
                "order_id", orderId,
                "sale_status", effectiveStatus,
                "items_processed", lineResults.size(),
                "lines", lineResults
        );
    }

    private Map<String, Product> resolveProducts(
            Long systemClientId,
            List<OrderLineContext> contexts,
            boolean lockProducts
    ) {
        Map<String, Product> initiallyResolved = new LinkedHashMap<>();
        for (OrderLineContext context : contexts) {
            Product product = productRepository
                    .findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(systemClientId, context.itemId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Active product not found for Mercado Livre item_id=" + context.itemId()
                                    + " and systemClientId=" + systemClientId));
            initiallyResolved.put(context.itemId(), product);
        }
        if (!lockProducts) {
            return initiallyResolved;
        }

        List<Long> orderedIds = initiallyResolved.values().stream().map(Product::getId).distinct().sorted().toList();
        List<Product> locked = productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(systemClientId, orderedIds);
        if (locked.size() != orderedIds.size()) {
            throw new EntityNotFoundException("One or more Mercado Livre products became unavailable.");
        }
        Map<Long, Product> lockedById = new java.util.HashMap<>();
        locked.forEach(product -> lockedById.put(product.getId(), product));
        Map<String, Product> result = new LinkedHashMap<>();
        initiallyResolved.forEach((itemId, product) -> result.put(itemId, lockedById.get(product.getId())));
        return result;
    }

    private void validateApplicationOwnership(MarketplaceIntegration integration, Long applicationId) {
        Object integrationApplication = integration.getResource() == null
                ? null
                : integration.getResource().get("application_id");
        String expected = integrationApplication == null ? numericClientId() : String.valueOf(integrationApplication);
        if (expected != null && !expected.equals(String.valueOf(applicationId))) {
            throw new IllegalArgumentException("Mercado Livre notification application_id does not own this integration.");
        }
    }

    private String numericClientId() {
        if (configuredApplicationId == null || configuredApplicationId.isBlank()) {
            return null;
        }
        String value = configuredApplicationId.trim();
        return value.chars().allMatch(Character::isDigit) ? value : null;
    }

    private void validateSellerOwnership(Map<String, Object> order, Long expectedSellerId) {
        Object sellerObject = order.get("seller");
        if (!(sellerObject instanceof Map<?, ?> seller) || seller.get("id") == null) {
            throw new IllegalArgumentException("Mercado Livre order seller is required for ownership validation.");
        }
        if (!String.valueOf(seller.get("id")).equals(String.valueOf(expectedSellerId))) {
            throw new IllegalArgumentException("Mercado Livre order seller does not own this integration.");
        }
    }

    private Map<String, Object> createSale(
            Product product,
            String orderId,
            Map<String, Object> order,
            MercadoLivreNotificationRequest notification,
            String normalizedStatus,
            OrderLineContext context
    ) {
        int previousStock = product.getStock();
        int stockDelta = committedQuantity(normalizedStatus, context.quantity());
        int newStock = previousStock - stockDelta;

        ensureStockRemainsAvailable(product, newStock);

        if (stockDelta != 0) {
            product.setStock(newStock);
            productRepository.save(product);
        }

        Sale sale = buildSale(product, orderId, order, normalizedStatus, context);
        Sale savedSale = saleRepository.save(sale);
        Map<String, Object> metadata = saleLogService.stockMetadata(
                previousStock,
                newStock,
                context.quantity(),
                notificationSnapshot(notification)
        );
        metadata.put("stock_delta", stockDelta);
        metadata.put("stock_inconsistency", newStock < 0);
        saleLogService.logCreated(savedSale, metadata);
        audit.recordAs(AuditActor.system(product.getSystemClientId()), AuditAction.CREATE, AuditEntityType.SALE,
                savedSale.getId(), null, AuditSnapshots.sale(savedSale), AuditSource.WEBHOOK,
                AuditSnapshots.fields("previous_stock", previousStock, "new_stock", newStock));

        return buildResult(savedSale, product, stockDelta != 0, orderId, context.itemId());
    }

    private Map<String, Object> updateExistingSale(
            Sale sale,
            Product product,
            String orderId,
            Map<String, Object> order,
            MercadoLivreNotificationRequest notification,
            String normalizedStatus,
            OrderLineContext context
    ) {
        String previousStatus = sale.getStatus();
        if (isStaleOrDuplicate(sale, order, normalizedStatus, context)) {
            return buildResult(sale, product, false, orderId, context.itemId());
        }
        var before = AuditSnapshots.sale(sale);
        int previousStock = product.getStock();
        int previousQuantity = sale.getQuantity();
        int previousCommittedQuantity = committedQuantity(previousStatus, sale.getQuantity());
        int currentCommittedQuantity = committedQuantity(normalizedStatus, context.quantity());
        int committedDelta = currentCommittedQuantity - previousCommittedQuantity;
        int newStock = previousStock - committedDelta;

        ensureStockRemainsAvailable(product, newStock);

        if (committedDelta != 0) {
            product.setStock(newStock);
            productRepository.save(product);
        }

        sale.setQuantity(context.quantity());
        sale.setTotalValue(context.totalValue());
        sale.setStatus(normalizedStatus);
        sale.setResource(buildSaleResource(product, order, context));
        Sale savedSale = saleRepository.save(sale);

        Map<String, Object> metadata = saleLogService.stockMetadata(
                previousStock,
                newStock,
                context.quantity(),
                notificationSnapshot(notification)
        );
        metadata.put("previous_sale_status", previousStatus);
        metadata.put("stock_delta", committedDelta);
        metadata.put("stock_inconsistency", newStock < 0);

        if (!previousStatus.equals(normalizedStatus)) {
            if (STATUS_CANCELLED.equals(normalizedStatus)) {
                saleLogService.logCancelled(savedSale, previousStatus, metadata);
            } else {
                saleLogService.logUpdated(savedSale, previousStatus, metadata);
            }
        } else if (committedDelta != 0 || previousQuantity != context.quantity()) {
            saleLogService.logUpdated(savedSale, previousStatus, metadata);
        }

        var after = AuditSnapshots.sale(savedSale);
        if (!before.equals(after)) {
            audit.recordAs(AuditActor.system(product.getSystemClientId()), AuditAction.UPDATE, AuditEntityType.SALE,
                    savedSale.getId(), before, after, AuditSource.WEBHOOK,
                    AuditSnapshots.fields("previous_stock", previousStock, "new_stock", newStock));
        }
        return buildResult(savedSale, product, committedDelta != 0, orderId, context.itemId());
    }

    private Sale buildSale(
            Product product,
            String orderId,
            Map<String, Object> order,
            String normalizedStatus,
            OrderLineContext context
    ) {
        Sale sale = new Sale();
        sale.setSystemClientId(product.getSystemClientId());
        sale.setProductId(product.getId());
        sale.setQuantity(context.quantity());
        sale.setTotalValue(context.totalValue());
        sale.setChannel(CHANNEL);
        sale.setExternalReferenceId(buildExternalReferenceId(orderId, context.itemId()));
        sale.setStatus(normalizedStatus);
        sale.setResource(buildSaleResource(product, order, context));
        return sale;
    }

    private Map<String, Object> buildSaleResource(Product product, Map<String, Object> order, OrderLineContext context) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("channel", CHANNEL);
        resource.put("mercado_livre_item_id", context.itemId());
        resource.put("mercado_livre_order_status", stringValue(order.get("status")));
        resource.put("mercado_livre_order_tags", order.get("tags"));
        resource.put("mercado_livre_order_id", stringValue(order.get("id")));
        resource.put("sku", product.getSku());
        resource.put("product_snapshot", Map.of(
                "id", product.getId(),
                "name", product.getName(),
                "price", product.getPrice(),
                "stock", product.getStock(),
                "reserved_stock", product.getReservedStock()
        ));
        resource.put("order", order);
        return resource;
    }

    private Map<String, Object> buildResult(
            Sale sale,
            Product product,
            boolean stockChanged,
            String orderId,
            String itemId
    ) {
        return Map.of(
                "processed", true,
                "sale_id", sale.getId(),
                "product_id", product.getId(),
                "order_id", orderId,
                "item_id", itemId,
                "external_reference_id", sale.getExternalReferenceId(),
                "sale_status", sale.getStatus(),
                "stock", product.getStock(),
                "stock_changed", stockChanged
        );
    }

    private void validateNotification(MercadoLivreNotificationRequest notification) {
        if (notification == null) {
            throw new IllegalArgumentException("Mercado Livre notification payload is required.");
        }

        if (notification.userId() == null) {
            throw new IllegalArgumentException("Mercado Livre notification user_id is required.");
        }

        if (notification.resource() == null || notification.resource().isBlank()) {
            throw new IllegalArgumentException("Mercado Livre notification resource is required.");
        }

        if (notification.topic() == null || notification.topic().isBlank()) {
            throw new IllegalArgumentException("Mercado Livre notification topic is required.");
        }
    }

    private MarketplaceIntegration findIntegrationByMercadoLivreUserId(Long userId) {
        return marketplaceIntegrationRepository
                .findByMarketplaceUserIdAndMarketplaceAndActiveTrue(String.valueOf(userId), Marketplace.MERCADO_LIVRE.name())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Active Mercado Livre integration not found for user_id=" + userId
                ));
    }

    private String extractOrderId(String resource) {
        String normalized = resource.trim();
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == normalized.length() - 1) {
            throw new IllegalArgumentException("Mercado Livre notification resource must contain an order id.");
        }
        return normalized.substring(lastSlash + 1);
    }

    @SuppressWarnings("unchecked")
    private List<OrderLineContext> extractOrderContexts(Map<String, Object> order) {
        Object orderItemsObject = order.get("order_items");
        if (!(orderItemsObject instanceof List<?> orderItems) || orderItems.isEmpty()) {
            throw new IllegalArgumentException("Mercado Livre order does not contain order_items.");
        }

        Map<String, OrderLineContext> contexts = new LinkedHashMap<>();
        for (Object orderItemObject : orderItems) {
            if (!(orderItemObject instanceof Map<?, ?> orderItem)) {
                throw new IllegalArgumentException("Mercado Livre order item payload is invalid.");
            }

            Object itemObject = orderItem.get("item");
            if (!(itemObject instanceof Map<?, ?> itemMap)) {
                throw new IllegalArgumentException("Mercado Livre order item is missing item data.");
            }

            String itemId = stringValue(itemMap.get("id"));
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("Mercado Livre order item id is required.");
            }

            Integer quantity = integerValue(orderItem.get("quantity"));
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("Mercado Livre order quantity is invalid.");
            }

            BigDecimal unitPrice = decimalValue(orderItem.get("unit_price"));
            if (unitPrice == null) {
                throw new IllegalArgumentException("Mercado Livre order item unit_price is invalid.");
            }

            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity.longValue()));
            contexts.merge(
                    itemId,
                    new OrderLineContext(itemId, quantity, lineTotal),
                    (left, right) -> new OrderLineContext(
                            itemId,
                            left.quantity() + right.quantity(),
                            left.totalValue().add(right.totalValue())
                    )
            );
        }

        return new ArrayList<>(contexts.values());
    }

    private boolean isStaleOrDuplicate(
            Sale sale,
            Map<String, Object> order,
            String normalizedStatus,
            OrderLineContext context
    ) {
        String incomingUpdatedAt = orderUpdatedAt(order);
        String persistedUpdatedAt = persistedOrderUpdatedAt(sale);
        if (incomingUpdatedAt != null && persistedUpdatedAt != null
                && incomingUpdatedAt.compareTo(persistedUpdatedAt) <= 0) {
            return true;
        }
        return java.util.Objects.equals(sale.getStatus(), normalizedStatus)
                && java.util.Objects.equals(sale.getQuantity(), context.quantity())
                && sale.getTotalValue() != null
                && sale.getTotalValue().compareTo(context.totalValue()) == 0;
    }

    private String orderUpdatedAt(Map<String, Object> order) {
        Object value = order.get("date_last_updated");
        return value == null ? null : String.valueOf(value);
    }

    private String persistedOrderUpdatedAt(Sale sale) {
        if (sale.getResource() == null) {
            return null;
        }
        Object order = sale.getResource().get("order");
        if (!(order instanceof Map<?, ?> values)) {
            return null;
        }
        Object value = values.get("date_last_updated");
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeSaleStatus(Map<String, Object> order) {
        String orderStatus = stringValue(order.get("status"));
        if (isCancelledStatus(orderStatus)) {
            return STATUS_CANCELLED;
        }

        if (isConfirmedStatus(orderStatus, order.get("tags"))) {
            return STATUS_CONFIRMED;
        }

        return STATUS_PENDING;
    }

    private Map<String, Object> notificationSnapshot(MercadoLivreNotificationRequest notification) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resource", notification.resource());
        snapshot.put("user_id", notification.userId());
        snapshot.put("topic", notification.topic());
        snapshot.put("application_id", notification.applicationId());
        snapshot.put("attempts", notification.attempts());
        snapshot.put("sent", notification.sent());
        snapshot.put("received", notification.received());
        return snapshot;
    }

    private Integer safeQuantity(Integer quantity) {
        return quantity == null ? 0 : quantity;
    }

    private int committedQuantity(String saleStatus, Integer quantity) {
        if (!STATUS_CONFIRMED.equals(saleStatus)) {
            return 0;
        }
        return safeQuantity(quantity);
    }

    private void ensureStockRemainsAvailable(Product product, int newStock) {
        if (newStock < 0 || newStock < product.getReservedStock()) {
            throw new IllegalStateException("Mercado Livre order exceeds the product's available stock.");
        }
    }

    private boolean isCancelledStatus(String orderStatus) {
        if (orderStatus == null) {
            return false;
        }
        return orderStatus.trim().toLowerCase().contains("cancel");
    }

    @SuppressWarnings("unchecked")
    private boolean isConfirmedStatus(String orderStatus, Object tagsObject) {
        if (orderStatus != null) {
            String normalizedStatus = orderStatus.trim().toLowerCase();
            if ("paid".equals(normalizedStatus) || "confirmed".equals(normalizedStatus)) {
                return true;
            }
        }

        if (tagsObject instanceof List<?> tags) {
            for (Object tag : tags) {
                if ("paid".equalsIgnoreCase(String.valueOf(tag))) {
                    return true;
                }
            }
        }

        return false;
    }

    private String buildExternalReferenceId(String orderId, String itemId) {
        return orderId + ":" + itemId;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String text && !text.isBlank()) {
            return Integer.valueOf(text);
        }

        return null;
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }

        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }

        if (value instanceof String text && !text.isBlank()) {
            return new BigDecimal(text);
        }

        return null;
    }

    private record OrderLineContext(String itemId, Integer quantity, BigDecimal totalValue) {
    }
}
