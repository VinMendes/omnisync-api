package com.puccampinas.omnisync.core.product.service;

import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public ProductDto create(Long systemClientId, ProductDto data) {
        validateCreateRequest(systemClientId, data);
        validator(data, systemClientId);

        Product product = toEntity(data);
        product.setId(null);
        product.setSystemClientId(systemClientId);
        product.setActive(true);

        return toDto(this.productRepository.save(product));
    }

    public List<ProductDto> getAll(Long systemClientId) {
        validateSystemClientId(systemClientId);
        return this.productRepository.findAllBySystemClientIdAndActiveTrue(systemClientId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public ProductDto getById(Long systemClientId, Long id) {
        validateIdentifiers(systemClientId, id);
        return toDto(findActiveById(systemClientId, id));
    }

    public ProductDto update(Long systemClientId, Long id, ProductDto data) {
        validateIdentifiers(systemClientId, id);
        validator(data, systemClientId);

        Product existing = findActiveById(systemClientId, id);
        existing.setSystemClientId(systemClientId);
        existing.setSku(data.getSku());
        existing.setName(data.getName());
        existing.setDescription(data.getDescription());
        existing.setStock(data.getStock());
        existing.setReservedStock(data.getReservedStock());
        existing.setPrice(data.getPrice());
        existing.setResource(data.getResource());

        return toDto(this.productRepository.save(existing));
    }

    public ProductDto delete(Long systemClientId, Long id) {
        validateIdentifiers(systemClientId, id);
        Product existing = findActiveById(systemClientId, id);
        existing.setActive(false);

        return toDto(this.productRepository.save(existing));
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
