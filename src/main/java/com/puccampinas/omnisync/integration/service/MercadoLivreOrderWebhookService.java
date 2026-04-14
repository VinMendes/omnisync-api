package com.puccampinas.omnisync.integration.service;

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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class MercadoLivreOrderWebhookService {

    private static final String CHANNEL = "MERCADO_LIVRE";
    private static final String TOPIC_ORDERS_V2 = "orders_v2";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final MarketplaceIntegrationRepository marketplaceIntegrationRepository;
    private final MarketplaceTokenService marketplaceTokenService;
    private final MercadoLivreClient mercadoLivreClient;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final SaleLogService saleLogService;

    public MercadoLivreOrderWebhookService(
            MarketplaceIntegrationRepository marketplaceIntegrationRepository,
            MarketplaceTokenService marketplaceTokenService,
            MercadoLivreClient mercadoLivreClient,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            SaleLogService saleLogService
    ) {
        this.marketplaceIntegrationRepository = marketplaceIntegrationRepository;
        this.marketplaceTokenService = marketplaceTokenService;
        this.mercadoLivreClient = mercadoLivreClient;
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.saleLogService = saleLogService;
    }

    @Transactional
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
        OrderContext context = extractOrderContext(order);

        Product product = productRepository
                .findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(systemClientId, context.itemId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Active product not found for Mercado Livre item_id=" + context.itemId()
                                + " and systemClientId=" + systemClientId
                ));

        Optional<Sale> existingSale = saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(
                systemClientId,
                CHANNEL,
                orderId
        );

        String normalizedStatus = normalizeSaleStatus(order);
        if (existingSale.isPresent()) {
            return updateExistingSale(existingSale.get(), product, order, notification, normalizedStatus, context);
        }

        return createSale(product, orderId, order, notification, normalizedStatus, context);
    }

    private Map<String, Object> createSale(
            Product product,
            String orderId,
            Map<String, Object> order,
            MercadoLivreNotificationRequest notification,
            String normalizedStatus,
            OrderContext context
    ) {
        if (STATUS_CANCELLED.equals(normalizedStatus)) {
            Sale cancelledSale = buildSale(product, orderId, order, normalizedStatus, context);
            Sale savedSale = saleRepository.save(cancelledSale);
            saleLogService.logCreated(savedSale, Map.of(
                    "notification", notificationSnapshot(notification),
                    "stock_changed", false
            ));
            return buildResult(savedSale, product, false);
        }

        int previousStock = product.getStock();
        int newStock = previousStock - context.quantity();
        if (newStock < 0) {
            throw new IllegalArgumentException(
                    "Order quantity exceeds current stock for product id=" + product.getId()
            );
        }

        product.setStock(newStock);
        productRepository.save(product);

        Sale sale = buildSale(product, orderId, order, normalizedStatus, context);
        Sale savedSale = saleRepository.save(sale);
        saleLogService.logCreated(savedSale, saleLogService.stockMetadata(
                previousStock,
                newStock,
                context.quantity(),
                notificationSnapshot(notification)
        ));

        return buildResult(savedSale, product, true);
    }

    private Map<String, Object> updateExistingSale(
            Sale sale,
            Product product,
            Map<String, Object> order,
            MercadoLivreNotificationRequest notification,
            String normalizedStatus,
            OrderContext context
    ) {
        String previousStatus = sale.getStatus();
        boolean stockChanged = false;
        int previousStock = product.getStock();
        int newStock = previousStock;

        if (!STATUS_CANCELLED.equals(previousStatus) && STATUS_CANCELLED.equals(normalizedStatus)) {
            newStock = previousStock + safeQuantity(sale.getQuantity());
            product.setStock(newStock);
            productRepository.save(product);
            stockChanged = true;
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

        if (!previousStatus.equals(normalizedStatus)) {
            if (STATUS_CANCELLED.equals(normalizedStatus)) {
                saleLogService.logCancelled(savedSale, previousStatus, metadata);
            } else {
                saleLogService.logUpdated(savedSale, previousStatus, metadata);
            }
        }

        return buildResult(savedSale, product, stockChanged);
    }

    private Sale buildSale(
            Product product,
            String orderId,
            Map<String, Object> order,
            String normalizedStatus,
            OrderContext context
    ) {
        Sale sale = new Sale();
        sale.setSystemClientId(product.getSystemClientId());
        sale.setProductId(product.getId());
        sale.setQuantity(context.quantity());
        sale.setTotalValue(context.totalValue());
        sale.setChannel(CHANNEL);
        sale.setExternalReferenceId(orderId);
        sale.setStatus(normalizedStatus);
        sale.setResource(buildSaleResource(product, order, context));
        return sale;
    }

    private Map<String, Object> buildSaleResource(Product product, Map<String, Object> order, OrderContext context) {
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

    private Map<String, Object> buildResult(Sale sale, Product product, boolean stockChanged) {
        return Map.of(
                "processed", true,
                "sale_id", sale.getId(),
                "product_id", product.getId(),
                "order_id", sale.getExternalReferenceId(),
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
    private OrderContext extractOrderContext(Map<String, Object> order) {
        Object orderItemsObject = order.get("order_items");
        if (!(orderItemsObject instanceof List<?> orderItems) || orderItems.isEmpty()) {
            throw new IllegalArgumentException("Mercado Livre order does not contain order_items.");
        }

        Object firstItemObject = orderItems.getFirst();
        if (!(firstItemObject instanceof Map<?, ?> firstItem)) {
            throw new IllegalArgumentException("Mercado Livre order item payload is invalid.");
        }

        Object itemObject = firstItem.get("item");
        if (!(itemObject instanceof Map<?, ?> itemMap)) {
            throw new IllegalArgumentException("Mercado Livre order item is missing item data.");
        }

        String itemId = stringValue(itemMap.get("id"));
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre order item id is required.");
        }

        Integer quantity = integerValue(firstItem.get("quantity"));
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Mercado Livre order quantity is invalid.");
        }

        BigDecimal totalValue = decimalValue(order.get("total_amount"));
        if (totalValue == null) {
            BigDecimal unitPrice = decimalValue(firstItem.get("unit_price"));
            if (unitPrice == null) {
                throw new IllegalArgumentException("Mercado Livre order total_amount is invalid.");
            }
            totalValue = unitPrice.multiply(BigDecimal.valueOf(quantity.longValue()));
        }

        return new OrderContext(itemId, quantity, totalValue);
    }

    private String normalizeSaleStatus(Map<String, Object> order) {
        String orderStatus = stringValue(order.get("status"));
        if (orderStatus == null) {
            return STATUS_CONFIRMED;
        }

        String normalized = orderStatus.trim().toLowerCase();
        if (normalized.contains("cancel")) {
            return STATUS_CANCELLED;
        }

        return STATUS_CONFIRMED;
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

    private record OrderContext(String itemId, Integer quantity, BigDecimal totalValue) {
    }
}
