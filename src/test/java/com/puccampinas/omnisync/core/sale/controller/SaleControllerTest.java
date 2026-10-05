package com.puccampinas.omnisync.core.sale.controller;

import com.puccampinas.omnisync.config.security.TenantAccess;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.dto.SaleLogDto;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SaleController.class)
@AutoConfigureMockMvc(addFilters = false)
class SaleControllerTest {

    @MockitoBean
    private TenantAccess tenantAccess;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void getByIdShouldReturnNotFoundWhenSaleDoesNotExist() throws Exception {
        when(saleService.getById(1L, 99L))
                .thenThrow(new EntityNotFoundException("Sale not found for id=99 and systemClientId=1"));

        mockMvc.perform(get("/api/sales/1/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Sale not found for id=99 and systemClientId=1"));
    }

    @Test
    void getByIdShouldReturnSaleWithLogs() throws Exception {
        SaleDto sale = buildSaleDto();
        when(saleService.getById(1L, 10L)).thenReturn(sale);

        mockMvc.perform(get("/api/sales/1/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.system_client_id").value(1L))
                .andExpect(jsonPath("$.product_id").value(20L))
                .andExpect(jsonPath("$.external_reference_id").value("2001"))
                .andExpect(jsonPath("$.logs[0].action").value("CREATED"));
    }

    @Test
    void getAllShouldReturnSalesPage() throws Exception {
        when(saleService.getAll(1L, 0, 20)).thenReturn(new PageImpl<>(List.of(buildSaleDto())));

        mockMvc.perform(get("/api/sales/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(10L))
                .andExpect(jsonPath("$.content[0].status").value("CONFIRMED"));
    }

    @Test
    void createShouldRejectNonPositiveQuantity() throws Exception {
        mockMvc.perform(post("/api/sales/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSaleJson().replace("\"quantity\":2", "\"quantity\":0")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShouldRejectNegativeTotalValue() throws Exception {
        mockMvc.perform(post("/api/sales/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSaleJson().replace("\"totalValue\":59.80", "\"totalValue\":-0.01")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShouldRejectBlankExternalReference() throws Exception {
        mockMvc.perform(post("/api/sales/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSaleJson().replace("PDV-20261004-001", "   ")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createShouldReturnExactInsufficientStockConflict() throws Exception {
        when(saleService.create(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(newInsufficientStockException(42L, 2, 1));

        mockMvc.perform(post("/api/sales/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSaleJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("Estoque insuficiente para concluir a venda."))
                .andExpect(jsonPath("$.details.product_id").value(42))
                .andExpect(jsonPath("$.details.requested_quantity").value(2))
                .andExpect(jsonPath("$.details.available_quantity").value(1))
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist())
                .andExpect(jsonPath("$.lastSyncAt").doesNotExist());
    }

    @Test
    void repeatedCreateShouldReturnOriginalSaleIdentityAndCreationTime() throws Exception {
        SaleDto original = buildSaleDto();
        original.setId(981L);
        original.setSystemClientId(1L);
        original.setProductId(42L);
        original.setChannel("MANUAL");
        original.setExternalReferenceId("PDV-20261004-001");
        original.setCreatedAt(LocalDateTime.of(2026, 9, 10, 15, 20, 30));
        original.setResource(Map.of("origin", "PHYSICAL_STORE"));
        when(saleService.create(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(original));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/sales/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(validSaleJson()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value(981L))
                    .andExpect(jsonPath("$[0].external_reference_id").value("PDV-20261004-001"))
                    .andExpect(jsonPath("$[0].created_at").value("2026-09-10T15:20:30"));
        }

        verify(saleService, times(2)).create(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void createShouldReturnExactIdempotencyConflict() throws Exception {
        when(saleService.create(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(newIdempotencyConflictException());

        mockMvc.perform(post("/api/sales/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validSaleJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.message").value("A referência externa já foi utilizada por outra venda."))
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.retryAfterSeconds").doesNotExist())
                .andExpect(jsonPath("$.lastSyncAt").doesNotExist());
    }

    private RuntimeException newInsufficientStockException(
            Long productId,
            int requestedQuantity,
            int availableQuantity
    ) throws Exception {
        Class<?> exceptionType = Class.forName(
                "com.puccampinas.omnisync.core.sale.exception.InsufficientStockException"
        );
        return (RuntimeException) exceptionType
                .getConstructor(Long.class, int.class, int.class)
                .newInstance(productId, requestedQuantity, availableQuantity);
    }

    private RuntimeException newIdempotencyConflictException() throws Exception {
        Class<?> exceptionType = Class.forName(
                "com.puccampinas.omnisync.core.sale.exception.IdempotencyConflictException"
        );
        return (RuntimeException) exceptionType.getConstructor().newInstance();
    }

    private String validSaleJson() {
        return """
                [{
                  "productId":42,
                  "quantity":2,
                  "totalValue":59.80,
                  "channel":"MANUAL",
                  "externalReferenceId":"PDV-20261004-001",
                  "resource":{"origin":"PHYSICAL_STORE"}
                }]
                """;
    }

    private SaleDto buildSaleDto() {
        SaleLogDto log = new SaleLogDto();
        log.setId(1L);
        log.setSaleId(10L);
        log.setSystemClientId(1L);
        log.setAction("CREATED");
        log.setNewStatus("CONFIRMED");
        log.setCreatedAt(LocalDateTime.of(2026, 4, 13, 10, 0));

        SaleDto sale = new SaleDto();
        sale.setId(10L);
        sale.setSystemClientId(1L);
        sale.setProductId(20L);
        sale.setQuantity(2);
        sale.setTotalValue(new BigDecimal("59.80"));
        sale.setChannel("MERCADO_LIVRE");
        sale.setExternalReferenceId("2001");
        sale.setStatus("CONFIRMED");
        sale.setCreatedAt(LocalDateTime.of(2026, 4, 13, 10, 0));
        sale.setResource(Map.of("mercado_livre_order_id", "2001"));
        sale.setLogs(List.of(log));
        return sale;
    }
}
