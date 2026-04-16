package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.core.product.service.ProductService;
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

@RestController
@RequestMapping("/api/integrations/mercadolivre/catalog")
public class MercadoLivreCatalogController {

    private final MercadoLivreListingService mercadoLivreListingService;
    private final ProductService productService;

    public MercadoLivreCatalogController(
            MercadoLivreListingService mercadoLivreListingService,
            ProductService productService
    ) {
        this.mercadoLivreListingService = mercadoLivreListingService;
        this.productService = productService;
    }

    @GetMapping("/categories")
    public ResponseEntity<Map<String, Object>> siteCategories(
            @RequestParam Long systemClientId,
            @RequestParam(defaultValue = "MLB") String siteId
    ) {
        return ResponseEntity.ok(
                mercadoLivreListingService.getSiteCategories(systemClientId, siteId)
        );
    }

    @GetMapping("/categories/suggestions")
    public ResponseEntity<Map<String, Object>> categorySuggestions(
            @RequestParam Long systemClientId,
            @RequestParam String q,
            @RequestParam(defaultValue = "MLB") String siteId
    ) {
        return ResponseEntity.ok(
                mercadoLivreListingService.searchCategorySuggestions(systemClientId, siteId, q)
        );
    }

    @GetMapping("/categories/{categoryId}/attributes")
    public ResponseEntity<Map<String, Object>> categoryAttributes(
            @RequestParam Long systemClientId,
            @PathVariable String categoryId
    ) {
        return ResponseEntity.ok(
                mercadoLivreListingService.getCategoryAttributes(systemClientId, categoryId)
        );
    }

    @GetMapping("/categories/{categoryId}/requirements")
    public ResponseEntity<Map<String, Object>> categoryRequirements(
            @RequestParam Long systemClientId,
            @PathVariable String categoryId
    ) {
        return ResponseEntity.ok(
                mercadoLivreListingService.getCategoryRequirements(systemClientId, categoryId)
        );
    }

    @PostMapping("/{systemClientId}/sync")
    public ResponseEntity<MercadoLivreSyncResponse> syncSellerListings(
            Authentication authentication,
            @PathVariable Long systemClientId
    ) {
        return ResponseEntity.ok(
                productService.syncMercadoLivreProducts(authentication.getName(), systemClientId)
        );
    }
}
