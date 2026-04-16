package com.puccampinas.omnisync.core.product.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.common.util.OffsetLimitPageable;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreSyncResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductLogService productLogService;
    private final MercadoLivreListingService mercadoLivreListingService;
    private final MarketplaceIntegrationRepository marketplaceIntegrationRepository;
    private final UserService userService;

    public ProductService(
            ProductRepository productRepository,
            ProductLogService productLogService,
            MercadoLivreListingService mercadoLivreListingService,
            MarketplaceIntegrationRepository marketplaceIntegrationRepository,
            UserService userService
    ) {
        this.productRepository = productRepository;
        this.productLogService = productLogService;
        this.mercadoLivreListingService = mercadoLivreListingService;
        this.marketplaceIntegrationRepository = marketplaceIntegrationRepository;
        this.userService = userService;
    }

    @Transactional
    public ProductDto create(Long systemClientId, ProductDto data) {
        validateCreateRequest(systemClientId, data);
        validator(data, systemClientId);

        Product product = toEntity(data);
        product.setId(null);
        product.setSystemClientId(systemClientId);
        product.setActive(true);

        Product savedProduct = this.productRepository.save(product);
        this.mercadoLivreListingService.createListing(systemClientId, savedProduct);
        this.productLogService.logCreate(savedProduct);

        return toDto(savedProduct);
    }

    public Page<ProductDto> getAll(Long systemClientId, long offset, int limit) {
        validateSystemClientId(systemClientId);
        validatePagination(offset, limit);

        return this.productRepository.findAllBySystemClientIdAndActiveTrue(
                        systemClientId,
                        new OffsetLimitPageable(offset, limit)
                )
                .map(this::toDto);
    }

    public ProductDto getBySku(Long systemClientId, String sku) {
        validateSystemClientId(systemClientId);
        validateSku(sku);

        return toDto(this.productRepository.findBySkuAndSystemClientIdAndActiveTrue(sku, systemClientId)
                .orElseThrow(() -> new EntityNotFoundException("SKU não encontrada.")));
    }

    public ProductDto getById(Long systemClientId, Long id) {
        validateIdentifiers(systemClientId, id);
        return toDto(findActiveById(systemClientId, id));
    }

    @Transactional
    public ProductDto update(Long systemClientId, Long id, ProductDto data) {
        validateIdentifiers(systemClientId, id);
        validator(data, systemClientId);

        Product existing = findActiveById(systemClientId, id);
        Product previousState = copy(existing);
        String itemId = extractMercadoLivreItemId(existing);
        existing.setSystemClientId(systemClientId);
        existing.setSku(data.getSku());
        existing.setName(data.getName());
        existing.setDescription(data.getDescription());
        existing.setStock(data.getStock());
        existing.setReservedStock(data.getReservedStock());
        existing.setPrice(data.getPrice());
        existing.setResource(mergeResourceForUpdate(existing.getResource(), data.getResource(), itemId));

        Product updatedProduct = this.productRepository.save(existing);
        this.mercadoLivreListingService.updateListing(systemClientId, id, itemId, updatedProduct);
        this.productLogService.logEdit(previousState, updatedProduct);

        return toDto(updatedProduct);
    }

    @Transactional
    public ProductDto delete(Long systemClientId, Long id) {
        validateIdentifiers(systemClientId, id);
        Product existing = findActiveById(systemClientId, id);
        Product previousState = copy(existing);
        existing.setActive(false);

        Product deletedProduct = this.productRepository.save(existing);
        this.mercadoLivreListingService.deleteListing(systemClientId, deletedProduct);
        this.productLogService.logDelete(previousState, deletedProduct);

        return toDto(deletedProduct);
    }

    @Transactional
    public MercadoLivreSyncResponse syncMercadoLivreProducts(String authenticatedEmail, Long systemClientId) {
        validateAuthenticatedEmail(authenticatedEmail);
        validateSystemClientId(systemClientId);

        User authenticatedUser = userService.findActiveEntityByEmail(authenticatedEmail);
        if (!authenticatedUser.getSystemClientId().equals(systemClientId)) {
            throw new EntityNotFoundException("System client not found for the authenticated user.");
        }

        MarketplaceIntegration integration = marketplaceIntegrationRepository
                .findMercadoLivreActiveIntegrationForSync(systemClientId, Marketplace.MERCADO_LIVRE.name())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Active Mercado Livre integration not found for systemClientId=" + systemClientId
                ));

        extractMarketplaceSellerUserId(integration);
        Map<String, Object> listings = mercadoLivreListingService.listAllClientListings(systemClientId);
        List<Map<String, Object>> items = extractMercadoLivreItems(listings.get("items"));
        Set<String> syncedItemIds = new LinkedHashSet<>();

        int created = 0;
        int updated = 0;
        int reactivated = 0;

        for (Map<String, Object> item : items) {
            ProductSyncOutcome outcome = syncMercadoLivreItem(systemClientId, item);
            syncedItemIds.add(outcome.itemId());

            if (outcome.created()) {
                created++;
            }

            if (outcome.updated()) {
                updated++;
            }

            if (outcome.reactivated()) {
                reactivated++;
            }
        }

        int deactivated = deactivateMissingMercadoLivreProducts(systemClientId, syncedItemIds);

        MercadoLivreSyncResponse response = new MercadoLivreSyncResponse();
        response.setMessage("Anúncios do Mercado Livre sincronizados com sucesso.");
        response.setSyncedProducts(items.size());
        return response;
    }

    private Product findActiveById(Long systemClientId, Long id) {
        return this.productRepository.findByIdAndSystemClientIdAndActiveTrue(id, systemClientId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Active product not found for id=" + id + " and systemClientId=" + systemClientId
                ));
    }

    private void validateCreateRequest(Long systemClientId, ProductDto data) {
        if (systemClientId == null && data == null) {
            throw new IllegalArgumentException("System client id and product payload are required.");
        }

        if (systemClientId == null) {
            throw new IllegalArgumentException("System client id is required.");
        }

        if (data == null) {
            throw new IllegalArgumentException("Product payload is required.");
        }
    }

    private void validateSystemClientId(Long systemClientId) {
        if (systemClientId == null) {
            throw new IllegalArgumentException("System client id is required.");
        }
    }

    private void validateAuthenticatedEmail(String authenticatedEmail) {
        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required.");
        }
    }

    private String extractMarketplaceSellerUserId(MarketplaceIntegration integration) {
        if (integration.getResource() == null) {
            throw new IllegalStateException("Marketplace integration resource must contain user_id.");
        }

        Object userId = integration.getResource().get("user_id");
        if (userId == null || String.valueOf(userId).isBlank()) {
            throw new IllegalStateException("Marketplace integration resource must contain user_id.");
        }

        return String.valueOf(userId);
    }

    private void validatePagination(long offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be greater than or equal to 0.");
        }

        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be greater than 0.");
        }
    }

    private void validateSku(String sku) {
        if (sku == null || sku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU is required.");
        }
    }

    private void validateIdentifiers(Long systemClientId, Long id) {
        if (systemClientId == null && id == null) {
            throw new IllegalArgumentException("System client id and id are required.");
        }

        if (systemClientId == null) {
            throw new IllegalArgumentException("System client id is required.");
        }

        if (id == null) {
            throw new IllegalArgumentException("Id is required.");
        }
    }

    private String extractMercadoLivreItemId(Product product) {
        if (product.getResource() == null) {
            throw new IllegalArgumentException("Product resource must contain Mercado Livre item_id.");
        }

        Object mercadoLivre = product.getResource().get("mercado_livre");
        if (!(mercadoLivre instanceof Map<?, ?> mercadoLivreMap)) {
            throw new IllegalArgumentException("Product resource must contain Mercado Livre item_id.");
        }

        Object itemId = mercadoLivreMap.get("item_id");
        if (itemId == null || String.valueOf(itemId).isBlank()) {
            throw new IllegalArgumentException("Product resource must contain Mercado Livre item_id.");
        }

        return String.valueOf(itemId);
    }

    private Map<String, Object> mergeResourceForUpdate(
            Map<String, Object> currentResource,
            Map<String, Object> incomingResource,
            String itemId
    ) {
        Map<String, Object> merged = currentResource == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(currentResource);

        if (incomingResource != null) {
            for (Map.Entry<String, Object> entry : incomingResource.entrySet()) {
                if (!"mercado_livre".equals(entry.getKey())) {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }

        Map<String, Object> mercadoLivre = currentMercadoLivreResource(incomingResource);
        mercadoLivre.put("item_id", itemId);

        merged.put("mercado_livre", mercadoLivre);
        return merged;
    }

    private Map<String, Object> currentMercadoLivreResource(Map<String, Object> resource) {
        if (resource == null) {
            return new java.util.LinkedHashMap<>();
        }

        Object mercadoLivre = resource.get("mercado_livre");
        if (!(mercadoLivre instanceof Map<?, ?> mercadoLivreMap)) {
            return new java.util.LinkedHashMap<>();
        }

        Map<String, Object> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mercadoLivreMap.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    private void validator(ProductDto data, Long systemClientId) {
        if (data == null) {
            throw new IllegalArgumentException("Product payload is required.");
        }

        if (data.getSystemClientId() == null) {
            throw new IllegalArgumentException("System client is required.");
        }

        if (!data.getSystemClientId().equals(systemClientId)) {
            throw new IllegalArgumentException("System client id must match the endpoint.");
        }

        if (data.getName() == null || data.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Name is required.");
        }

        if (data.getSku() == null || data.getSku().trim().isEmpty()) {
            throw new IllegalArgumentException("SKU is required.");
        }

        if (data.getDescription() == null || data.getDescription().trim().isEmpty()) {
            throw new IllegalArgumentException("Description is required.");
        }

        if (data.getPrice() == null) {
            throw new IllegalArgumentException("Price is required.");
        }

        if (data.getPrice().scale() > 2) {
            throw new IllegalArgumentException("Price must have at most 2 decimal places.");
        }

        if (data.getPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Price has to be above 0.");
        }

        if (data.getStock() < 0) {
            throw new IllegalArgumentException("Stock has to be above 0.");
        }

        if (data.getReservedStock() < 0) {
            throw new IllegalArgumentException("Reserved stock has to be above 0.");
        }
    }

    private Product toEntity(ProductDto data) {
        Product product = new Product();
        product.setSystemClientId(data.getSystemClientId());
        product.setSku(data.getSku());
        product.setName(data.getName());
        product.setDescription(data.getDescription());
        product.setStock(data.getStock());
        product.setReservedStock(data.getReservedStock());
        product.setPrice(data.getPrice());
        product.setResource(data.getResource());
        return product;
    }

    private Product copy(Product source) {
        Product copy = new Product();
        copy.setId(source.getId());
        copy.setSystemClientId(source.getSystemClientId());
        copy.setSku(source.getSku());
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setStock(source.getStock());
        copy.setReservedStock(source.getReservedStock());
        copy.setPrice(source.getPrice());
        copy.setResource(source.getResource());
        copy.setActive(source.getActive());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private ProductSyncOutcome syncMercadoLivreItem(Long systemClientId, Map<String, Object> rawItem) {
        Map<String, Object> item = normalizeMercadoLivreItem(rawItem);
        String itemId = requiredString(item, "id", "Mercado Livre item id is required during sync.");
        String sku = resolveMercadoLivreSku(item, itemId);

        Optional<Product> productByItemId = productRepository.findBySystemClientIdAndMercadoLivreItemId(systemClientId, itemId);
        Optional<Product> productBySku = productByItemId.isPresent()
                ? Optional.empty()
                : productRepository.findBySkuAndSystemClientId(sku, systemClientId);

        boolean created = productByItemId.isEmpty() && productBySku.isEmpty();
        Product target = productByItemId.or(() -> productBySku).orElseGet(Product::new);
        boolean reactivated = !created && !target.getActive();
        Product previousState = created ? null : copy(target);

        applyMercadoLivreSnapshot(systemClientId, target, item, sku, itemId);
        Product saved = productRepository.save(target);

        if (created) {
            productLogService.logCreate(saved);
            return new ProductSyncOutcome(itemId, true, false, false);
        }

        if (reactivated || hasProductChanged(previousState, saved)) {
            productLogService.logEdit(previousState, saved);
            return new ProductSyncOutcome(itemId, false, !reactivated, reactivated);
        }

        return new ProductSyncOutcome(itemId, false, false, false);
    }

    private int deactivateMissingMercadoLivreProducts(Long systemClientId, Set<String> syncedItemIds) {
        List<Product> mercadoLivreProducts = productRepository.findAllMercadoLivreProductsBySystemClientId(systemClientId);
        int deactivated = 0;

        for (Product product : mercadoLivreProducts) {
            String itemId = extractMercadoLivreItemIdOrNull(product.getResource());
            if (!product.getActive() || itemId == null || syncedItemIds.contains(itemId)) {
                continue;
            }

            Product previousState = copy(product);
            product.setActive(false);
            Product saved = productRepository.save(product);
            productLogService.logDelete(previousState, saved);
            deactivated++;
        }

        return deactivated;
    }

    private List<Map<String, Object>> extractMercadoLivreItems(Object rawItems) {
        if (!(rawItems instanceof List<?> items)) {
            return List.of();
        }

        List<Map<String, Object>> normalizedItems = new ArrayList<>();
        for (Object rawItem : items) {
            if (rawItem instanceof Map<?, ?> itemMap) {
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                    if (entry.getKey() != null) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                normalizedItems.add(normalized);
            }
        }

        return normalizedItems;
    }

    private Map<String, Object> normalizeMercadoLivreItem(Map<String, Object> rawItem) {
        Object body = rawItem.get("body");
        if (!(body instanceof Map<?, ?> bodyMap)) {
            return rawItem;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : bodyMap.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    private void applyMercadoLivreSnapshot(
            Long systemClientId,
            Product target,
            Map<String, Object> item,
            String sku,
            String itemId
    ) {
        String title = requiredString(item, "title", "Mercado Livre title is required during sync.");

        target.setSystemClientId(systemClientId);
        target.setSku(sku);
        target.setName(title);
        target.setDescription(resolveDescription(item, target));
        target.setStock(extractInteger(item.get("available_quantity")));
        target.setReservedStock(resolveReservedStock(target));
        target.setPrice(extractBigDecimal(item.get("price")));
        target.setResource(mergeMercadoLivreResource(target.getResource(), item, itemId));
        target.setActive(true);
    }

    private String resolveMercadoLivreSku(Map<String, Object> item, String fallbackItemId) {
        Object sellerCustomField = item.get("seller_custom_field");
        if (sellerCustomField != null && !String.valueOf(sellerCustomField).isBlank()) {
            return String.valueOf(sellerCustomField);
        }

        Object attributes = item.get("attributes");
        if (attributes instanceof List<?> attributeList) {
            for (Object rawAttribute : attributeList) {
                if (!(rawAttribute instanceof Map<?, ?> attributeMap)) {
                    continue;
                }

                Object id = attributeMap.get("id");
                if (id == null || !"SELLER_SKU".equalsIgnoreCase(String.valueOf(id))) {
                    continue;
                }

                Object valueName = attributeMap.get("value_name");
                if (valueName != null && !String.valueOf(valueName).isBlank()) {
                    return String.valueOf(valueName);
                }
            }
        }

        return fallbackItemId;
    }

    private String resolveDescription(Map<String, Object> item, Product existing) {
        Object plainText = item.get("description");
        if (plainText != null && !String.valueOf(plainText).isBlank()) {
            return String.valueOf(plainText);
        }

        if (existing.getDescription() != null && !existing.getDescription().isBlank()) {
            return existing.getDescription();
        }

        return requiredString(item, "title", "Mercado Livre title is required during sync.");
    }

    private int resolveReservedStock(Product existing) {
        return Math.max(existing.getReservedStock(), 0);
    }

    private Map<String, Object> mergeMercadoLivreResource(
            Map<String, Object> currentResource,
            Map<String, Object> item,
            String itemId
    ) {
        Map<String, Object> merged = currentResource == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(currentResource);

        Map<String, Object> mercadoLivre = new LinkedHashMap<>();
        Object existingMercadoLivre = merged.get("mercado_livre");
        if (existingMercadoLivre instanceof Map<?, ?> existingMap) {
            for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
                if (entry.getKey() != null) {
                    mercadoLivre.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        mercadoLivre.put("item_id", itemId);
        mercadoLivre.put("seller_id", item.get("seller_id"));
        mercadoLivre.put("status", item.get("status"));
        mercadoLivre.put("category_id", item.get("category_id"));
        mercadoLivre.put("listing_type_id", item.get("listing_type_id"));
        mercadoLivre.put("permalink", item.get("permalink"));
        mercadoLivre.put("last_updated", item.get("last_updated"));
        mercadoLivre.put("raw", new LinkedHashMap<>(item));

        merged.put("mercado_livre", mercadoLivre);
        return merged;
    }

    private String extractMercadoLivreItemIdOrNull(Map<String, Object> resource) {
        if (resource == null) {
            return null;
        }

        Object mercadoLivre = resource.get("mercado_livre");
        if (!(mercadoLivre instanceof Map<?, ?> mercadoLivreMap)) {
            return null;
        }

        Object itemId = mercadoLivreMap.get("item_id");
        if (itemId == null || String.valueOf(itemId).isBlank()) {
            return null;
        }

        return String.valueOf(itemId);
    }

    private String requiredString(Map<String, Object> source, String key, String message) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return String.valueOf(value);
    }

    private int extractInteger(Object value) {
        if (value == null) {
            return 0;
        }

        if (value instanceof Number number) {
            return Math.max(number.intValue(), 0);
        }

        return Math.max(Integer.parseInt(String.valueOf(value)), 0);
    }

    private BigDecimal extractBigDecimal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Mercado Livre price is required during sync.");
        }

        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }

        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }

        return new BigDecimal(String.valueOf(value));
    }

    private boolean hasProductChanged(Product previousState, Product currentState) {
        return !Objects.equals(previousState.getSku(), currentState.getSku())
                || !Objects.equals(previousState.getName(), currentState.getName())
                || !Objects.equals(previousState.getDescription(), currentState.getDescription())
                || previousState.getStock() != currentState.getStock()
                || previousState.getReservedStock() != currentState.getReservedStock()
                || !Objects.equals(previousState.getPrice(), currentState.getPrice())
                || previousState.getActive() != currentState.getActive()
                || !Objects.equals(previousState.getResource(), currentState.getResource());
    }

    private record ProductSyncOutcome(String itemId, boolean created, boolean updated, boolean reactivated) {
    }

    private ProductDto toDto(Product product) {
        ProductDto dto = new ProductDto();
        dto.setId(product.getId());
        dto.setSystemClientId(product.getSystemClientId());
        dto.setSku(product.getSku());
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        dto.setStock(product.getStock());
        dto.setReservedStock(product.getReservedStock());
        dto.setPrice(product.getPrice());
        dto.setResource(product.getResource());
        dto.setActive(product.getActive());
        dto.setCreatedAt(product.getCreatedAt());
        return dto;
    }
}
