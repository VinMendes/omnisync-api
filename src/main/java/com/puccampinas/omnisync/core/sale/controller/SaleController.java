package com.puccampinas.omnisync.core.sale.controller;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/sale")
public class SaleController {
    private final SaleService saleService;

    public  SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @PostMapping
    public ResponseEntity<Sale> create (@RequestBody SaleCreateRequest request){
        Sale newSale = saleService.createSale(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(newSale);
    }
}
