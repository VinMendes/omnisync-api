package com.puccampinas.omnisync.core.product.controller;

import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_LISTING_PUBLISH;
import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_PRODUCT_READ;
import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_PRODUCT_WRITE;

@RestController
@RequestMapping("/api/products/{systemClientId}")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(HAS_PRODUCT_WRITE)
    public ResponseEntity<ProductDto> create(
            @PathVariable Long systemClientId,
            @RequestBody ProductDto data
    ) {
        return ResponseEntity.ok(this.service.create(systemClientId, data));
    }

    @GetMapping
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<Page<ProductDto>> getAll(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(this.service.getAll(systemClientId, offset, limit));
    }

    @GetMapping("/sku/{sku}")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<ProductDto> getBySku(
            @PathVariable Long systemClientId,
            @PathVariable String sku
    ) {
        return ResponseEntity.ok(this.service.getBySku(systemClientId, sku));
    }

    @GetMapping("/{id}")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<ProductDto> getById(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(this.service.getById(systemClientId, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(HAS_PRODUCT_WRITE)
    public ResponseEntity<ProductDto> update(
            @PathVariable Long systemClientId,
            @PathVariable Long id,
            @RequestBody ProductDto data
    ) {
        return ResponseEntity.ok(this.service.update(systemClientId, id, data));
    }

    @PostMapping("/{id}/announce")
    @PreAuthorize(HAS_LISTING_PUBLISH)
    public ResponseEntity<ProductDto> announce(
            @PathVariable Long systemClientId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> mlResource
    ) {
        return ResponseEntity.ok(this.service.announce(systemClientId, id, mlResource));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(HAS_PRODUCT_WRITE)
    public ResponseEntity<ProductDto> delete(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(this.service.delete(systemClientId, id));
    }
}
