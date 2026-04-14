package com.puccampinas.omnisync.core.sale.controller;

import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sales/{systemClientId}")
public class SaleController {

    private final SaleService saleService;

    public SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @GetMapping
    public ResponseEntity<Page<SaleDto>> getAll(
            @PathVariable Long systemClientId,
            @RequestParam(defaultValue = "0") long offset,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(saleService.getAll(systemClientId, offset, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SaleDto> getById(
            @PathVariable Long systemClientId,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(saleService.getById(systemClientId, id));
    }
}
