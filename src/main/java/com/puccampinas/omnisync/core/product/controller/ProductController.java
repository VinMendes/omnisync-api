package com.puccampinas.omnisync.core.product.controller;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.product.dto.LowStockProductsResponse;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.core.users.enums.Permission;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final TenantAccess access;

    public ProductController(ProductService service, TenantAccess access) {
        this.service = service;
        this.access = access;
    }

    @PostMapping
    @PreAuthorize(HAS_PRODUCT_WRITE)
    public ResponseEntity<ProductDto> create(
            @PathVariable Long systemClientId,
            @RequestBody ProductDto data
    ) {
        access.requireTenant(systemClientId);
        if (data.isAnnouncement()) {
            access.requirePermission(Permission.LISTING_PUBLISH);
        }
        return ResponseEntity.ok(this.service.create(systemClientId, data));
    }

    @GetMapping
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<Page<ProductDto>> getAll(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.getAll(systemClientId, offset, limit));
    }

    @GetMapping("/low-stock")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<LowStockProductsResponse> getLowStock(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit,
            @AuthenticationPrincipal OmniUserPrincipal principal
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.getLowStock(systemClientId, offset, limit, principal));
    }

    @GetMapping("/sku/{sku}")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<ProductDto> getBySku(
            @PathVariable Long systemClientId,
            @PathVariable String sku
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.getBySku(systemClientId, sku));
    }

    @GetMapping("/{id}")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<ProductDto> getById(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.getById(systemClientId, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(HAS_PRODUCT_WRITE)
    public ResponseEntity<ProductDto> update(
            @PathVariable Long systemClientId,
            @PathVariable Long id,
            @RequestBody ProductDto data
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.update(systemClientId, id, data));
    }

    @PostMapping("/{id}/announce")
    @PreAuthorize(HAS_LISTING_PUBLISH)
    public ResponseEntity<ProductDto> announce(
            @PathVariable Long systemClientId,
            @PathVariable Long id,
            @RequestBody Map<String, Object> mlResource
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.announce(systemClientId, id, mlResource));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(HAS_PRODUCT_WRITE)
    public ResponseEntity<ProductDto> delete(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(this.service.delete(systemClientId, id));
    }
}
