package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.core.users.enums.Permission;
import org.springframework.security.access.prepost.PreAuthorize;
import com.puccampinas.omnisync.integration.dto.MercadoLivreSyncResponse;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_INTEGRATION_MANAGE;
import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_PRODUCT_READ;

@RestController
@RequestMapping("/api/integrations/mercadolivre/catalog")
public class MercadoLivreCatalogController {

    private final MercadoLivreListingService mercadoLivreListingService;
    private final ProductService productService;
    private final TenantAccess access;

    public MercadoLivreCatalogController(
            MercadoLivreListingService mercadoLivreListingService,
            ProductService productService,
            TenantAccess access
    ) {
        this.mercadoLivreListingService = mercadoLivreListingService;
        this.productService = productService;
        this.access = access;
    }

    @GetMapping("/categories")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<Map<String, Object>> siteCategories(
            @RequestParam Long systemClientId,
            @RequestParam(defaultValue = "MLB") String siteId
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(
                mercadoLivreListingService.getSiteCategories(systemClientId, siteId)
        );
    }

    @GetMapping("/categories/suggestions")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<Map<String, Object>> categorySuggestions(
            @RequestParam Long systemClientId,
            @RequestParam String q,
            @RequestParam(defaultValue = "MLB") String siteId
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(
                mercadoLivreListingService.searchCategorySuggestions(systemClientId, siteId, q)
        );
    }

    @GetMapping("/categories/{categoryId}/attributes")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<Map<String, Object>> categoryAttributes(
            @RequestParam Long systemClientId,
            @PathVariable String categoryId
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(
                mercadoLivreListingService.getCategoryAttributes(systemClientId, categoryId)
        );
    }

    @GetMapping("/categories/{categoryId}/requirements")
    @PreAuthorize(HAS_PRODUCT_READ)
    public ResponseEntity<Map<String, Object>> categoryRequirements(
            @RequestParam Long systemClientId,
            @PathVariable String categoryId
    ) {
        access.requireTenant(systemClientId);
        return ResponseEntity.ok(
                mercadoLivreListingService.getCategoryRequirements(systemClientId, categoryId)
        );
    }

    @PostMapping("/{systemClientId}/sync")
    @PreAuthorize(HAS_INTEGRATION_MANAGE)
    public ResponseEntity<MercadoLivreSyncResponse> syncSellerListings(
            Authentication authentication,
            @PathVariable Long systemClientId
    ) {
        access.requireTenant(systemClientId);
        access.requirePermission(Permission.PRODUCT_WRITE);
        return ResponseEntity.ok(
                productService.syncMercadoLivreProducts(authentication.getName(), systemClientId)
        );
    }
}
