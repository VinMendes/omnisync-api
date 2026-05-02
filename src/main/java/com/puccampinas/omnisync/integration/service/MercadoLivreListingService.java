package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MercadoLivreListingService {

    private static final String RESOURCE_ROOT_KEY = "mercado_livre";
    private static final String ITEM_ID_KEY = "item_id";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_MULTIGET_ITEMS = 20;
    private static final int SEARCH_PAGE_LIMIT = 50;
    private static final String TEST_SITE_ID = "MLB";
    private static final String TEST_CURRENCY_ID = "BRL";
    private static final String TEST_BUYING_MODE = "buy_it_now";
    private static final String DEFAULT_LISTING_TYPE_ID = "gold_special";
    private static final String TEST_TITLE = "Item de Teste - Por Favor Nao Ofertar";

    private final MercadoLivreClient mercadoLivreClient;
    private final MarketplaceTokenService marketplaceTokenService;
    private final ProductRepository productRepository;

    public MercadoLivreListingService(
            MercadoLivreClient mercadoLivreClient,
            MarketplaceTokenService marketplaceTokenService,
            ProductRepository productRepository
    ) {
        this.mercadoLivreClient = mercadoLivreClient;
        this.marketplaceTokenService = marketplaceTokenService;
        this.productRepository = productRepository;
    }

    @Transactional
    public Map<String, Object> createListing(Long systemClientId, Product product) {
        validateSystemClientId(systemClientId);
        validateProductForCreate(product);

        String accessToken = getAccessToken(systemClientId);
        Map<String, Object> payload = buildCreatePayload(product, accessToken);
        Map<String, Object> createdItem = mercadoLivreClient.createItem(accessToken, payload);

        String itemId = extractRequiredString(createdItem, "id", "Mercado Livre did not return the created item id.");
        upsertDescription(accessToken, itemId, product.getDescription());
        persistMercadoLivreResource(product, createdItem);

        return createdItem;
    }

    @Transactional
    public Map<String, Object> updateListing(Long systemClientId, Long productId, String itemId, Product data) {
        validateSystemClientId(systemClientId);
        validateProductIdentifier(productId);
        validateItemId(itemId);
        validateProductPayload(data);

        Product existing = findActiveProduct(systemClientId, productId);
        applyUpdateData(existing, data, itemId);
        String accessToken = getAccessToken(systemClientId);
        Map<String, Object> payload = buildUpdatePayload(existing, accessToken);
        Map<String, Object> updatedItem = mercadoLivreClient.updateItem(accessToken, itemId, payload);

        upsertDescription(accessToken, itemId, existing.getDescription());
        persistMercadoLivreResource(existing, updatedItem);
        return updatedItem;
    }

    @Transactional
    public Map<String, Object> deleteListing(Long systemClientId, Product product) {
        validateSystemClientId(systemClientId);
        validateProductForCreate(product);

        String accessToken = getAccessToken(systemClientId);
        String itemId = resolveItemId(product);
        Map<String, Object> currentItem = mercadoLivreClient.getItem(accessToken, itemId);

        if (!shouldSkipCloseStep(currentItem)) {
            mercadoLivreClient.updateItem(accessToken, itemId, Map.of("status", "closed"));
        }

        Map<String, Object> deletedItem = markItemAsDeleted(accessToken, itemId);
        persistMercadoLivreResource(product, deletedItem);
        return deletedItem;
    }

    @Transactional
    public Map<String, Object> deleteListingByItemId(Long systemClientId, String itemId) {
        validateSystemClientId(systemClientId);
        validateItemId(itemId);

        String accessToken = getAccessToken(systemClientId);
        Map<String, Object> currentItem = mercadoLivreClient.getItem(accessToken, itemId);

        if (!shouldSkipCloseStep(currentItem)) {
            mercadoLivreClient.updateItem(accessToken, itemId, Map.of("status", "closed"));
        }

        Map<String, Object> deletedItem = markItemAsDeleted(accessToken, itemId);
        productRepository.findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(systemClientId, itemId)
                .ifPresent(product -> persistMercadoLivreResource(product, deletedItem));

        return deletedItem;
    }

    public Map<String, Object> listListings(
            Long systemClientId,
            Integer offset,
            Integer limit,
            String status,
            String sellerSku
    ) {
        validateSystemClientId(systemClientId);
        validatePagination(offset, limit);

        String accessToken = getAccessToken(systemClientId);
        Long sellerId = resolveSellerId(accessToken);

        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("offset", offset == null ? 0 : offset);
        queryParams.put("limit", normalizeLimit(limit));

        if (status != null && !status.isBlank()) {
            queryParams.put("status", status);
        }

        if (sellerSku != null && !sellerSku.isBlank()) {
            queryParams.put("seller_sku", sellerSku);
        }

        Map<String, Object> searchResponse = mercadoLivreClient.searchSellerItems(accessToken, sellerId, queryParams);
        List<String> itemIds = extractItemIds(searchResponse.get("results"));
        searchResponse.put("items", fetchItemDetailsInBatches(accessToken, itemIds));

        searchResponse.put("seller_id", sellerId);
        return searchResponse;
    }

    public Map<String, Object> listAllClientListings(Long systemClientId) {
        validateSystemClientId(systemClientId);

        String accessToken = getAccessToken(systemClientId);
        Long sellerId = resolveSellerId(accessToken);

        List<String> allItemIds = new ArrayList<>();
        int offset = 0;
        Integer total = null;

        while (true) {
            Map<String, Object> page = mercadoLivreClient.searchSellerItems(
                    accessToken,
                    sellerId,
                    Map.of("offset", offset, "limit", SEARCH_PAGE_LIMIT)
            );

            List<String> pageItemIds = extractItemIds(page.get("results"));
            allItemIds.addAll(pageItemIds);

            int pageCount = pageItemIds.size();
            if (total == null) {
                total = extractPagingTotal(page.get("paging"));
            }

            if (pageCount == 0 || total == null || allItemIds.size() >= total) {
                break;
            }

            offset += SEARCH_PAGE_LIMIT;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seller_id", sellerId);
        response.put("total", total == null ? allItemIds.size() : total);
        response.put("results", allItemIds);
        response.put("items", fetchItemDetailsInBatches(accessToken, allItemIds));
        return response;
    }

    public Map<String, Object> searchCategorySuggestions(Long systemClientId, String siteId, String query) {
        validateSystemClientId(systemClientId);

        String accessToken = getAccessToken(systemClientId);
        List<Map<String, Object>> suggestions = mercadoLivreClient.searchCategorySuggestions(accessToken, siteId, query);

        return Map.of(
                "site_id", siteId,
                "query", query,
                "suggestions", suggestions
        );
    }

    public Map<String, Object> getSiteCategories(Long systemClientId, String siteId) {
        validateSystemClientId(systemClientId);

        String accessToken = getAccessToken(systemClientId);
        List<Map<String, Object>> categories = mercadoLivreClient.getSiteCategories(accessToken, siteId);

        return Map.of(
                "site_id", siteId,
                "categories", categories
        );
    }

    public Map<String, Object> getCategoryAttributes(Long systemClientId, String categoryId) {
        validateSystemClientId(systemClientId);

        String accessToken = getAccessToken(systemClientId);
        List<Map<String, Object>> attributes = mercadoLivreClient.getCategoryAttributes(accessToken, categoryId);

        return Map.of(
                "category_id", categoryId,
                "attributes", attributes
        );
    }

    public Map<String, Object> getCategoryRequirements(Long systemClientId, String categoryId) {
        validateSystemClientId(systemClientId);

        String accessToken = getAccessToken(systemClientId);
        List<Map<String, Object>> attributes = mercadoLivreClient.getCategoryAttributes(accessToken, categoryId);
        Map<String, Object> technicalSpecsInput = mercadoLivreClient.getCategoryTechnicalSpecsInput(accessToken, categoryId);

        return Map.of(
                "category_id", categoryId,
                "attributes", attributes,
                "technical_specs_input", technicalSpecsInput,
                "required_attributes", extractRequiredAttributes(technicalSpecsInput)
        );
    }

    public Map<String, Object> getListing(Long systemClientId, String itemId) {
        validateSystemClientId(systemClientId);

        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre item id is required.");
        }

        return mercadoLivreClient.getItem(getAccessToken(systemClientId), itemId);
    }

    public Map<String, Object> getListingByItemId(Long systemClientId, String itemId) {
        return getListing(systemClientId, itemId);
    }

    private String getAccessToken(Long systemClientId) {
        return marketplaceTokenService.getValidAccessToken(systemClientId, Marketplace.MERCADO_LIVRE);
    }

    private Product findActiveProduct(Long systemClientId, Long productId) {
        return productRepository.findByIdAndSystemClientIdAndActiveTrue(productId, systemClientId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Active product not found for id=" + productId + " and systemClientId=" + systemClientId
                ));
    }

    private Map<String, Object> buildCreatePayload(Product product, String accessToken) {
        Map<String, Object> metadata = extractMercadoLivreMetadata(product, true);
        String categoryId = getRequiredMetadataString(metadata, "category_id");
        String condition = getRequiredMetadataString(metadata, "condition");
        List<Map<String, Object>> pictures = getRequiredPictures(metadata, accessToken);
        List<Map<String, Object>> attributes = getRequiredAttributes(metadata.get("attributes"), product.getSku());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", TEST_SITE_ID);
        payload.put("title", product.getName());
        payload.put("category_id", categoryId);
        payload.put("price", product.getPrice());
        payload.put("currency_id", TEST_CURRENCY_ID);
        payload.put("available_quantity", calculateAvailableQuantity(product));
        payload.put("buying_mode", TEST_BUYING_MODE);
        payload.put("listing_type_id", getOptionalMetadataString(metadata, "listing_type_id", DEFAULT_LISTING_TYPE_ID));
        payload.put("condition", condition);
        payload.put("pictures", pictures);
        payload.put("attributes", attributes);
        payload.put("seller_custom_field", product.getSku());

        return payload;
    }

    private Map<String, Object> buildUpdatePayload(Product product, String accessToken) {
        Map<String, Object> metadata = extractMercadoLivreMetadata(product, true);
        int availableQuantity = calculateAvailableQuantity(product);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", product.getName());
        payload.put("price", product.getPrice());
        payload.put("available_quantity", availableQuantity);

        if (availableQuantity == 0) {
            payload.put("status", "paused");
        } else {
            putIfPresent(payload, "status", metadata.get("status"));
        }

        putIfPresent(payload, "condition", metadata.get("condition"));
        putIfPresent(payload, "buying_mode", metadata.get("buying_mode"));
        putIfPresent(payload, "video_id", metadata.get("video_id"));
        putIfPresent(payload, "pictures", normalizeOptionalPictures(metadata.get("pictures"), accessToken));
        putIfPresent(payload, "shipping", metadata.get("shipping"));
        putIfPresent(payload, "sale_terms", metadata.get("sale_terms"));
        putIfPresent(payload, "variations", metadata.get("variations"));

        List<Map<String, Object>> attributes = mergeAttributes(metadata.get("attributes"), product.getSku());
        if (!attributes.isEmpty()) {
            payload.put("attributes", attributes);
        }

        return payload;
    }

    private Map<String, Object> extractMercadoLivreMetadata(Product product, boolean required) {
        if (product.getResource() == null || product.getResource().isEmpty()) {
            if (required) {
                throw new IllegalArgumentException(
                        "Product resource must contain Mercado Livre metadata."
                );
            }

            return Map.of();
        }

        Object nestedResource = product.getResource().get(RESOURCE_ROOT_KEY);
        Object source = nestedResource == null ? product.getResource() : nestedResource;

        if (!(source instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Mercado Livre resource must be a JSON object.");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                metadata.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return metadata;
    }

    private List<Map<String, Object>> getRequiredPictures(Map<String, Object> metadata, String accessToken) {
        List<Map<String, Object>> pictures = normalizeOptionalPictures(metadata.get("pictures"), accessToken);
        if (pictures == null || pictures.isEmpty()) {
            throw new IllegalArgumentException("Mercado Livre pictures are required in product.resource.");
        }

        return pictures;
    }

    private List<Map<String, Object>> normalizeOptionalPictures(Object pictures, String accessToken) {
        if (pictures == null) {
            return null;
        }

        if (!(pictures instanceof List<?> rawPictures) || rawPictures.isEmpty()) {
            throw new IllegalArgumentException("Mercado Livre pictures must be a non-empty array of objects.");
        }

        List<Map<String, Object>> normalizedPictures = new ArrayList<>();

        for (Object rawPicture : rawPictures) {
            if (!(rawPicture instanceof Map<?, ?> pictureMap)) {
                throw new IllegalArgumentException("Mercado Livre pictures must be an array of objects.");
            }

            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : pictureMap.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }

            Object source = normalized.get("source");
            Object id = normalized.get("id");
            Object base64 = normalized.get("base64");
            boolean hasSource = source != null && !String.valueOf(source).isBlank();
            boolean hasId = id != null && !String.valueOf(id).isBlank();
            boolean hasBase64 = base64 != null && !String.valueOf(base64).isBlank();

            if (!hasSource && !hasId && !hasBase64) {
                throw new IllegalArgumentException(
                        "Each Mercado Livre picture must contain either source, id or base64."
                );
            }

            if (hasBase64) {
                normalizedPictures.add(uploadBase64Picture(accessToken, normalized));
                continue;
            }

            if (hasId) {
                normalizedPictures.add(Map.of("id", String.valueOf(id)));
                continue;
            }

            normalizedPictures.add(Map.of("source", String.valueOf(source)));
        }

        return normalizedPictures;
    }

    private List<Map<String, Object>> mergeAttributes(Object rawAttributes, String sku) {
        List<Map<String, Object>> attributes = new ArrayList<>();

        if (rawAttributes instanceof List<?> rawList) {
            for (Object rawAttribute : rawList) {
                if (!(rawAttribute instanceof Map<?, ?> attributeMap)) {
                    throw new IllegalArgumentException("Mercado Livre attributes must be an array of objects.");
                }

                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : attributeMap.entrySet()) {
                    if (entry.getKey() != null) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }

                attributes.add(normalized);
            }
        }

        if (sku != null && !sku.isBlank() && !hasSellerSkuAttribute(attributes)) {
            Map<String, Object> sellerSkuAttribute = new LinkedHashMap<>();
            sellerSkuAttribute.put("id", "SELLER_SKU");
            sellerSkuAttribute.put("value_name", sku);
            attributes.add(sellerSkuAttribute);
        }

        return attributes;
    }

    private List<Map<String, Object>> getRequiredAttributes(Object rawAttributes, String sku) {
        List<Map<String, Object>> attributes = mergeAttributes(rawAttributes, sku);
        if (attributes.isEmpty()) {
            throw new IllegalArgumentException("Mercado Livre attributes are required in product.resource.");
        }

        return attributes;
    }

    private boolean hasSellerSkuAttribute(List<Map<String, Object>> attributes) {
        for (Map<String, Object> attribute : attributes) {
            Object id = attribute.get("id");
            if (id != null && "SELLER_SKU".equalsIgnoreCase(String.valueOf(id))) {
                return true;
            }
        }
        return false;
    }

    private int calculateAvailableQuantity(Product product) {
        int availableQuantity = product.getStock() - product.getReservedStock();

        if (availableQuantity < 0) {
            throw new IllegalArgumentException("Reserved stock cannot be greater than stock for Mercado Livre sync.");
        }

        return availableQuantity;
    }

    private String resolveItemId(Product product) {
        Map<String, Object> metadata = extractMercadoLivreMetadata(product, true);
        return getRequiredMetadataString(metadata, ITEM_ID_KEY);
    }

    private Long resolveSellerId(String accessToken) {
        Map<String, Object> me = mercadoLivreClient.getMyUser(accessToken);
        Object id = me.get("id");

        if (!(id instanceof Number sellerId)) {
            throw new IllegalStateException("Mercado Livre /users/me did not return a valid seller id.");
        }

        return sellerId.longValue();
    }

    private boolean shouldSkipCloseStep(Map<String, Object> currentItem) {
        Object status = currentItem.get("status");

        if (status != null && "closed".equalsIgnoreCase(String.valueOf(status))) {
            return true;
        }

        Object subStatus = currentItem.get("sub_status");
        if (status != null && "under_review".equalsIgnoreCase(String.valueOf(status))) {
            if (subStatus instanceof String value) {
                return "forbidden".equalsIgnoreCase(value);
            }

            if (subStatus instanceof List<?> values) {
                for (Object value : values) {
                    if (value != null && "forbidden".equalsIgnoreCase(String.valueOf(value))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private Map<String, Object> markItemAsDeleted(String accessToken, String itemId) {
        try {
            return mercadoLivreClient.updateItem(accessToken, itemId, Map.of("deleted", true));
        } catch (ExternalApiException ex) {
            if (ex.getStatusCode().value() != 409) {
                throw ex;
            }

            waitForMercadoLivreConsistency();
            return mercadoLivreClient.updateItem(accessToken, itemId, Map.of("deleted", true));
        }
    }

    private void upsertDescription(String accessToken, String itemId, String description) {
        if (description == null || description.isBlank()) {
            return;
        }

        try {
            mercadoLivreClient.updateItemDescription(accessToken, itemId, description);
        } catch (ExternalApiException ex) {
            if (ex.getStatusCode().value() != 404) {
                throw ex;
            }

            mercadoLivreClient.createItemDescription(accessToken, itemId, description);
        }
    }

    private void waitForMercadoLivreConsistency() {
        try {
            Thread.sleep(2000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread interrupted while waiting to retry Mercado Livre deletion.", ex);
        }
    }

    private List<String> extractItemIds(Object results) {
        List<String> itemIds = new ArrayList<>();

        if (!(results instanceof List<?> values)) {
            return itemIds;
        }

        for (Object value : values) {
            if (value != null) {
                itemIds.add(String.valueOf(value));
            }
        }

        return itemIds;
    }

    private List<Map<String, Object>> fetchItemDetailsInBatches(String accessToken, List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> items = new ArrayList<>();

        for (int index = 0; index < itemIds.size(); index += MAX_MULTIGET_ITEMS) {
            int end = Math.min(index + MAX_MULTIGET_ITEMS, itemIds.size());
            items.addAll(mercadoLivreClient.getItems(accessToken, itemIds.subList(index, end)));
        }

        return items;
    }

    private Integer extractPagingTotal(Object paging) {
        if (!(paging instanceof Map<?, ?> pagingMap)) {
            return null;
        }

        Object total = pagingMap.get("total");
        if (!(total instanceof Number number)) {
            return null;
        }

        return number.intValue();
    }

    private List<Map<String, Object>> extractRequiredAttributes(Map<String, Object> technicalSpecsInput) {
        Object groups = technicalSpecsInput.get("groups");
        if (!(groups instanceof List<?> groupList)) {
            return List.of();
        }

        List<Map<String, Object>> requiredAttributes = new ArrayList<>();

        for (Object group : groupList) {
            if (!(group instanceof Map<?, ?> groupMap)) {
                continue;
            }

            Object components = groupMap.get("components");
            if (!(components instanceof List<?> componentList)) {
                continue;
            }

            for (Object component : componentList) {
                if (!(component instanceof Map<?, ?> componentMap)) {
                    continue;
                }

                Object attributes = componentMap.get("attributes");
                if (!(attributes instanceof List<?> attributeList)) {
                    continue;
                }

                for (Object attribute : attributeList) {
                    if (!(attribute instanceof Map<?, ?> attributeMap)) {
                        continue;
                    }

                    if (isRequiredTechnicalSpec(attributeMap)) {
                        requiredAttributes.add(normalizeRequiredAttribute(attributeMap, componentMap, groupMap));
                    }
                }
            }
        }

        return requiredAttributes;
    }

    private boolean isRequiredTechnicalSpec(Map<?, ?> attributeMap) {
        Object tags = attributeMap.get("tags");
        if (tags instanceof Map<?, ?> tagsMap) {
            Object required = tagsMap.get("required");
            return required instanceof Boolean bool && bool;
        }

        if (tags instanceof List<?> tagList) {
            for (Object tag : tagList) {
                if (tag != null && "required".equalsIgnoreCase(String.valueOf(tag))) {
                    return true;
                }
            }
        }

        return false;
    }

    private Map<String, Object> normalizeRequiredAttribute(
            Map<?, ?> attributeMap,
            Map<?, ?> componentMap,
            Map<?, ?> groupMap
    ) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("id", attributeMap.get("id"));
        normalized.put("name", attributeMap.get("name"));
        normalized.put("value_type", attributeMap.get("value_type"));
        normalized.put("component", componentMap.get("component"));
        normalized.put("group_id", groupMap.get("id"));
        normalized.put("group_label", groupMap.get("label"));
        normalized.put("tags", attributeMap.get("tags"));
        normalized.put("values", attributeMap.get("values"));
        return normalized;
    }

    private String getRequiredMetadataString(Map<String, Object> metadata, String key) {
        return getRequiredMetadataString(metadata, key, "Mercado Livre field '" + key + "' is required in product.resource.");
    }

    private String getRequiredMetadataString(Map<String, Object> metadata, String key, String message) {
        Object value = metadata.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return String.valueOf(value);
    }

    private String getOptionalMetadataString(Map<String, Object> metadata, String key, String defaultValue) {
        Object value = metadata.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }

        return String.valueOf(value);
    }

    private Object normalizeExplicitListingType(Object rawListingType) {
        if (rawListingType == null) {
            return null;
        }

        String listingType = String.valueOf(rawListingType).trim();
        if (listingType.isEmpty()) {
            return null;
        }

        if ("gold".equalsIgnoreCase(listingType)) {
            throw new IllegalArgumentException(
                    "Mercado Livre listing_type_id cannot be gold."
            );
        }

        return listingType;
    }

    private String extractRequiredString(Map<String, Object> source, String key, String message) {
        Object value = source.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException(message);
        }

        return String.valueOf(value);
    }

    private int normalizeLimit(Integer limit) {
        return limit == null ? DEFAULT_LIMIT : limit;
    }

    private void validateProductForCreate(Product product) {
        validateProductPayload(product);

        if (product.getId() == null) {
            throw new IllegalArgumentException("Product id is required to persist Mercado Livre resource data.");
        }
    }

    private void validateProductPayload(Product product) {
        if (product == null) {
            throw new IllegalArgumentException("Product is required.");
        }

        if (product.getName() == null || product.getName().isBlank()) {
            throw new IllegalArgumentException("Product name is required.");
        }

        if (product.getPrice() == null) {
            throw new IllegalArgumentException("Product price is required.");
        }

        if (product.getSku() == null || product.getSku().isBlank()) {
            throw new IllegalArgumentException("Product sku is required.");
        }
    }

    private void validateProductIdentifier(Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("Product id is required.");
        }
    }

    private void validateItemId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre item id is required.");
        }
    }

    private void validateSystemClientId(Long systemClientId) {
        if (systemClientId == null) {
            throw new IllegalArgumentException("System client id is required.");
        }
    }

    private void validatePagination(Integer offset, Integer limit) {
        int normalizedOffset = offset == null ? 0 : offset;
        int normalizedLimit = normalizeLimit(limit);

        if (normalizedOffset < 0) {
            throw new IllegalArgumentException("Offset must be greater than or equal to 0.");
        }

        if (normalizedLimit < 1 || normalizedLimit > MAX_MULTIGET_ITEMS) {
            throw new IllegalArgumentException("Limit must be between 1 and 20.");
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private void applyUpdateData(Product target, Product source, String itemId) {
        target.setSku(source.getSku());
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setStock(source.getStock());
        target.setReservedStock(source.getReservedStock());
        target.setPrice(source.getPrice());
        target.setResource(mergeResourceWithItemId(target.getResource(), source.getResource(), itemId));
    }

    private Map<String, Object> mergeResourceWithItemId(
            Map<String, Object> currentResource,
            Map<String, Object> incomingResource,
            String itemId
    ) {
        Map<String, Object> merged = currentResource == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(currentResource);

        if (incomingResource != null) {
            merged.putAll(incomingResource);
        }

        Map<String, Object> mercadoLivre = new LinkedHashMap<>();
        Object currentMercadoLivre = merged.get(RESOURCE_ROOT_KEY);

        if (currentMercadoLivre instanceof Map<?, ?> currentMetadata) {
            for (Map.Entry<?, ?> entry : currentMetadata.entrySet()) {
                if (entry.getKey() != null) {
                    mercadoLivre.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        Object incomingMercadoLivre = incomingResource == null ? null : incomingResource.get(RESOURCE_ROOT_KEY);
        if (incomingMercadoLivre instanceof Map<?, ?> incomingMetadata) {
            for (Map.Entry<?, ?> entry : incomingMetadata.entrySet()) {
                if (entry.getKey() != null) {
                    mercadoLivre.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }

        mercadoLivre.put(ITEM_ID_KEY, itemId);
        merged.put(RESOURCE_ROOT_KEY, mercadoLivre);
        return merged;
    }

    private void persistMercadoLivreResource(Product product, Map<String, Object> mercadoLivreResponse) {
        Map<String, Object> resource = product.getResource() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(product.getResource());

        Map<String, Object> mercadoLivre = new LinkedHashMap<>();
        mercadoLivre.put("item_id", mercadoLivreResponse.get("id"));
        mercadoLivre.put("permalink", mercadoLivreResponse.get("permalink"));
        mercadoLivre.put("seller_id", mercadoLivreResponse.get("seller_id"));
        mercadoLivre.put("status", mercadoLivreResponse.get("status"));
        mercadoLivre.put("category_id", mercadoLivreResponse.get("category_id"));
        mercadoLivre.put("listing_type_id", mercadoLivreResponse.get("listing_type_id"));
        mercadoLivre.put("last_updated", mercadoLivreResponse.get("last_updated"));
        mercadoLivre.put("raw", deepCopyMap(mercadoLivreResponse));

        resource.put(RESOURCE_ROOT_KEY, mercadoLivre);
        product.setResource(resource);
        productRepository.save(product);
    }

    private Map<String, Object> uploadBase64Picture(String accessToken, Map<String, Object> picture) {
        ParsedBase64Picture parsed = parseBase64Picture(
                String.valueOf(picture.get("base64")),
                picture.get("content_type") == null ? null : String.valueOf(picture.get("content_type")),
                picture.get("file_name") == null ? null : String.valueOf(picture.get("file_name"))
        );

        Map<String, Object> uploaded = mercadoLivreClient.uploadItemPicture(
                accessToken,
                parsed.content(),
                parsed.fileName(),
                parsed.contentType()
        );

        String pictureId = extractRequiredString(
                uploaded,
                "id",
                "Mercado Livre did not return the uploaded picture id."
        );

        return Map.of("id", pictureId);
    }

    private ParsedBase64Picture parseBase64Picture(String rawBase64, String explicitContentType, String explicitFileName) {
        if (rawBase64 == null || rawBase64.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre picture base64 is required.");
        }

        String contentType = explicitContentType;
        String base64Payload = rawBase64.trim();

        if (base64Payload.startsWith("data:")) {
            int commaIndex = base64Payload.indexOf(',');
            if (commaIndex < 0) {
                throw new IllegalArgumentException("Invalid data URI for Mercado Livre picture.");
            }

            String metadata = base64Payload.substring(5, commaIndex);
            base64Payload = base64Payload.substring(commaIndex + 1);

            int semicolonIndex = metadata.indexOf(';');
            if (semicolonIndex > 0 && (contentType == null || contentType.isBlank())) {
                contentType = metadata.substring(0, semicolonIndex);
            }
        }

        byte[] content;
        try {
            content = Base64.getDecoder().decode(base64Payload);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Mercado Livre picture base64 is invalid.", ex);
        }

        String resolvedContentType = resolvePictureContentType(contentType, explicitFileName);
        String resolvedFileName = resolvePictureFileName(explicitFileName, resolvedContentType);
        return new ParsedBase64Picture(content, resolvedContentType, resolvedFileName);
    }

    private String resolvePictureContentType(String explicitContentType, String explicitFileName) {
        if (explicitContentType != null && !explicitContentType.isBlank()) {
            return explicitContentType;
        }

        if (explicitFileName == null || explicitFileName.isBlank()) {
            return "image/jpeg";
        }

        String lower = explicitFileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }

        return "image/jpeg";
    }

    private String resolvePictureFileName(String explicitFileName, String contentType) {
        if (explicitFileName != null && !explicitFileName.isBlank()) {
            return explicitFileName;
        }

        if ("image/png".equalsIgnoreCase(contentType)) {
            return "image.png";
        }

        return "image.jpg";
    }

    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }

        return copy;
    }

    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
                }
            }
            return normalized;
        }

        if (value instanceof List<?> listValue) {
            List<Object> copy = new ArrayList<>();
            for (Object item : listValue) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }

        return value;
    }

    private record ParsedBase64Picture(byte[] content, String contentType, String fileName) {
    }
}
