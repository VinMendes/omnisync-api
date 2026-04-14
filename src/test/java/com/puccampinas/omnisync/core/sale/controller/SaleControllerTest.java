package com.puccampinas.omnisync.core.sale.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.dto.SaleLogDto;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SaleController.class)
@AutoConfigureMockMvc(addFilters = false)
class SaleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private JwtService jwtService;

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
