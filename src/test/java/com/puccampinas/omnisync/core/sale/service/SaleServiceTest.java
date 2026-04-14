package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.entity.SaleLog;
import com.puccampinas.omnisync.core.sale.repository.SaleLogRepository;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SaleServiceTest {

    private SaleRepository saleRepository;
    private SaleLogRepository saleLogRepository;
    private SaleService saleService;

    @BeforeEach
    void setUp() {
        saleRepository = mock(SaleRepository.class);
        saleLogRepository = mock(SaleLogRepository.class);
        saleService = new SaleService(saleRepository, saleLogRepository);
    }

    @Test
    void getAllShouldReturnSalesPage() {
        Sale sale = buildSale();
        when(saleRepository.findAllBySystemClientId(any(Long.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sale)));

        Page<SaleDto> result = saleService.getAll(1L, 0, 20);

        assertEquals(1, result.getTotalElements());
        assertEquals(10L, result.getContent().getFirst().getId());
        verify(saleRepository).findAllBySystemClientId(
                argThat(id -> id.equals(1L)),
                argThat(pageable -> pageable.getOffset() == 0 && pageable.getPageSize() == 20)
        );
        verifyNoInteractions(saleLogRepository);
    }

    @Test
    void getByIdShouldReturnSaleWithLogs() {
        Sale sale = buildSale();
        SaleLog log = new SaleLog();
        log.setId(1L);
        log.setSaleId(10L);
        log.setSystemClientId(1L);
        log.setAction("CREATED");
        log.setNewStatus("CONFIRMED");
        log.setCreatedAt(LocalDateTime.of(2026, 4, 13, 10, 0));

        when(saleRepository.findByIdAndSystemClientId(10L, 1L)).thenReturn(Optional.of(sale));
        when(saleLogRepository.findAllBySaleIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(log));

        SaleDto result = saleService.getById(1L, 10L);

        assertEquals(10L, result.getId());
        assertEquals(1, result.getLogs().size());
        assertEquals("CREATED", result.getLogs().getFirst().getAction());
    }

    @Test
    void getByIdShouldThrowWhenSaleDoesNotExist() {
        when(saleRepository.findByIdAndSystemClientId(99L, 1L)).thenReturn(Optional.empty());

        EntityNotFoundException error = assertThrows(
                EntityNotFoundException.class,
                () -> saleService.getById(1L, 99L)
        );

        assertEquals("Sale not found for id=99 and systemClientId=1", error.getMessage());
    }

    private Sale buildSale() {
        Sale sale = new Sale();
        sale.setId(10L);
        sale.setSystemClientId(1L);
        sale.setProductId(20L);
        sale.setQuantity(2);
        sale.setTotalValue(new BigDecimal("59.80"));
        sale.setChannel("MERCADO_LIVRE");
        sale.setExternalReferenceId("2001:MLB123");
        sale.setStatus("CONFIRMED");
        sale.setCreatedAt(LocalDateTime.of(2026, 4, 13, 10, 0));
        sale.setResource(Map.of("mercado_livre_order_id", "2001"));
        return sale;
    }
}
