package com.puccampinas.omnisync.core.sale.controller;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_SALE_READ;
import static com.puccampinas.omnisync.config.security.PermissionAuthority.HAS_SALE_WRITE;

@RestController
@RequestMapping("/api/sales/{systemClientId}")
public class SaleController {

    private final SaleService saleService;

    public SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @PostMapping
    @PreAuthorize(HAS_SALE_WRITE)
    public ResponseEntity<List<SaleDto>> create(
            @PathVariable Long systemClientId,
            @RequestBody List<SaleCreateRequest> requests
    ) {
        return ResponseEntity.ok(this.saleService.create(systemClientId, requests));
    }

    @GetMapping
    @PreAuthorize(HAS_SALE_READ)
    public ResponseEntity<Page<SaleDto>> getAll(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(saleService.getAll(systemClientId, offset, limit));
    }

    @GetMapping("/{id}")
    @PreAuthorize(HAS_SALE_READ)
    public ResponseEntity<SaleDto> getById(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(saleService.getById(systemClientId, id));
    }
}
