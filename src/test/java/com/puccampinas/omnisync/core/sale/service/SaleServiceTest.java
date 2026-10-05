package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.entity.SaleLog;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.repository.SaleLogRepository;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.audit.AuditService;
import com.puccampinas.omnisync.integration.service.MarketplaceStockSyncOutboxService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SaleServiceTest {

    private SaleRepository saleRepository;
    private SaleLogRepository saleLogRepository;
    private ProductRepository productRepository;
    private SaleLogService saleLogService;
    private MarketplaceStockSyncOutboxService outboxService;
    private SaleService saleService;

    @BeforeEach
    void setUp() {
        saleRepository = mock(SaleRepository.class);
        saleLogRepository = mock(SaleLogRepository.class);
        productRepository = mock(ProductRepository.class);
        saleLogService = mock(SaleLogService.class);
        outboxService = mock(MarketplaceStockSyncOutboxService.class);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            sale.setId(100L);
            return sale;
        });
        when(outboxService.enqueueIfRequired(any(Sale.class), any(Product.class)))
                .thenReturn(Optional.empty());
        SaleRegistrationTransaction registrationTransaction = new SaleRegistrationTransaction(
                productRepository,
                saleRepository,
                saleLogService,
                mock(AuditService.class),
                outboxService,
                new SaleRequestNormalizer()
        );
        saleService = new SaleService(saleRepository, saleLogRepository, registrationTransaction);
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

    @Test
    void createShouldUseStockMinusReservedStock() {
        Product product = buildProduct(3, 1);
        when(productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(1L, List.of(20L)))
                .thenReturn(List.of(product));

        List<SaleDto> result = saleService.create(1L, List.of(buildRequest(2, "59.80", 1L)));

        assertEquals(1, result.size());
        assertEquals(1, product.getStock());
        verify(productRepository).saveAll(List.of(product));
    }

    @Test
    void createShouldAcceptPositiveQuantity() {
        Product product = buildProduct(3, 1);
        when(productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(1L, List.of(20L)))
                .thenReturn(List.of(product));

        saleService.create(1L, List.of(buildRequest(1, "29.90", 1L)));

        assertEquals(2, product.getStock());
        verify(saleRepository).save(any(Sale.class));
    }

    @Test
    void createShouldRejectZeroQuantityBeforeChangingStock() {
        Product product = buildProduct(3, 1);
        when(productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(1L, List.of(20L)))
                .thenReturn(List.of(product));

        assertThrows(
                IllegalArgumentException.class,
                () -> saleService.create(1L, List.of(buildRequest(0, "0.00", 1L)))
        );

        assertEquals(3, product.getStock());
        verify(productRepository, never()).saveAll(any());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void createShouldRejectNegativeQuantityBeforeChangingStock() {
        Product product = buildProduct(3, 1);
        when(productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(1L, List.of(20L)))
                .thenReturn(List.of(product));

        assertThrows(
                IllegalArgumentException.class,
                () -> saleService.create(1L, List.of(buildRequest(-1, "0.00", 1L)))
        );

        assertEquals(3, product.getStock());
        verify(productRepository, never()).saveAll(any());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void createShouldAcceptZeroValuePromotionalSale() {
        Product product = buildProduct(3, 1);
        when(productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(1L, List.of(20L)))
                .thenReturn(List.of(product));

        List<SaleDto> result = saleService.create(1L, List.of(buildRequest(1, "0.00", 1L)));

        assertEquals(0, result.getFirst().getTotalValue().compareTo(BigDecimal.ZERO));
        assertEquals(2, product.getStock());
    }

    @Test
    void createShouldRejectNegativeValueBeforeChangingStock() {
        Product product = buildProduct(3, 1);
        when(productRepository.findAllActiveBySystemClientIdAndIdInForUpdate(1L, List.of(20L)))
                .thenReturn(List.of(product));

        assertThrows(
                IllegalArgumentException.class,
                () -> saleService.create(1L, List.of(buildRequest(1, "-0.01", 1L)))
        );

        assertEquals(3, product.getStock());
        verify(productRepository, never()).saveAll(any());
        verify(saleRepository, never()).save(any(Sale.class));
    }

    @Test
    void createShouldRejectBodyTenantMismatchBeforeProductLookup() {
        SaleCreateRequest request = buildRequest(1, "29.90", 2L);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> saleService.create(1L, List.of(request))
        );

        assertTrue(error.getMessage() != null && !error.getMessage().isBlank());
        verifyNoInteractions(productRepository);
        verify(saleRepository, never()).save(any(Sale.class));
    }

    private SaleCreateRequest buildRequest(int quantity, String totalValue, Long bodyTenantId) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setSystemClientId(bodyTenantId);
        request.setProductId(20L);
        request.setQuantity(quantity);
        request.setTotalValue(new BigDecimal(totalValue));
        request.setChannel(SaleChannel.MANUAL);
        request.setExternalReferenceId("PDV-20261004-001");
        request.setResource(Map.of("origin", "PHYSICAL_STORE"));
        return request;
    }

    private Product buildProduct(int stock, int reservedStock) {
        Product product = new Product();
        product.setId(20L);
        product.setSystemClientId(1L);
        product.setSku("SKU-20");
        product.setName("Produto");
        product.setDescription("Produto de teste");
        product.setStock(stock);
        product.setReservedStock(reservedStock);
        product.setPrice(new BigDecimal("29.90"));
        product.setActive(true);
        product.setResource(Map.of());
        return product;
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
