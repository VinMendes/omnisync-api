package com.puccampinas.omnisync.core.product.controller;

import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products/{systemClientId}")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductDto> create(
            @PathVariable Long systemClientId,
            @RequestBody ProductDto data
    ) {
        return ResponseEntity.ok(this.service.create(systemClientId, data));
    }

    @GetMapping
    public ResponseEntity<Page<ProductDto>> getAll(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(this.service.getAll(systemClientId, offset, limit));
    }

    @GetMapping("/sku/{sku}")
    public ResponseEntity<ProductDto> getBySku(
            @PathVariable Long systemClientId,
            @PathVariable String sku
    ) {
        return ResponseEntity.ok(this.service.getBySku(systemClientId, sku));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> getById(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(this.service.getById(systemClientId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductDto> update(
            @PathVariable Long systemClientId,
            @PathVariable Long id,
            @RequestBody ProductDto data
    ) {
        return ResponseEntity.ok(this.service.update(systemClientId, id, data));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ProductDto> delete(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(this.service.delete(systemClientId, id));
    }
}
