package com.puccampinas.omnisync.core.product.service;

import com.puccampinas.omnisync.common.util.OffsetLimitPageable;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductLogService productLogService;
    private final MercadoLivreListingService mercadoLivreListingService;

    public ProductService(
            ProductRepository productRepository,
            ProductLogService productLogService,
            MercadoLivreListingService mercadoLivreListingService
    ) {
        this.productRepository = productRepository;
        this.productLogService = productLogService;
        this.mercadoLivreListingService = mercadoLivreListingService;
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
