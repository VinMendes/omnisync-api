package com.puccampinas.omnisync.core.sale.controller;

<<<<<<< HEAD
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.dto.SaleLogDto;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import jakarta.persistence.EntityNotFoundException;
=======
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import org.junit.jupiter.api.DisplayName;
>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
<<<<<<< HEAD
import org.springframework.data.domain.PageImpl;
=======
import org.springframework.http.MediaType;
>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
<<<<<<< HEAD
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

=======

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SaleController.class) // Avisa o Spring para carregar apenas este Controller
@AutoConfigureMockMvc(addFilters = false) // Desliga a exigência de Token JWT para o teste rodar isolado
@DisplayName("Testes do Controlador de Vendas (SaleController)")
class SaleControllerTest {

    @Autowired
    private MockMvc mockMvc; // O nosso "Postman" interno

    private final ObjectMapper objectMapper = new ObjectMapper(); // Ferramenta para converter Java em JSON

    @MockitoBean
    private SaleService saleService; // Dublê do seu Service

    // Mocks de segurança que o seu amigo usou.
    // É bom mantê-los aqui porque o Spring Security pode tentar procurá-los ao iniciar o teste.
>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
<<<<<<< HEAD
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
=======
    @DisplayName("Deve criar uma venda com sucesso e retornar 201 Created")
    void create_success() throws Exception {
        // 1. Arrange: Monta a caixa (DTO) que o usuário "enviaria" pelo celular/web
        SaleCreateRequest request = new SaleCreateRequest();
        request.setSystemClientId(1L);
        request.setProductId(1L);
        request.setQuantity(2);
        request.setTotalValue(new BigDecimal("150.50"));
        request.setChannel(SaleChannel.PHYSICAL);

        // 2. Arrange: Monta a Venda que o dublê (SaleService) vai fingir que salvou
        Sale mockSaleResponse = new Sale();
        mockSaleResponse.setId(99L);
        mockSaleResponse.setQuantity(2);
        mockSaleResponse.setTotalValue(new BigDecimal("150.50"));
        mockSaleResponse.setChannel(SaleChannel.PHYSICAL);
        mockSaleResponse.setStatus(SaleStatus.CONFIRMED);

        // Ensina o dublê a devolver a venda montada
        when(saleService.createSale(any(SaleCreateRequest.class))).thenReturn(mockSaleResponse);

        // 3. Act & Assert: Faz a requisição HTTP e inspeciona o resultado
        mockMvc.perform(post("/api/sale")
                        .contentType(MediaType.APPLICATION_JSON)
                        // Transforma a classe SaleCreateRequest em um texto JSON de verdade
                        .content(objectMapper.writeValueAsString(request)))

                // --- INSPETORES DO MOCKMVC ---
                .andExpect(status().isCreated()) // Verifica se retornou 201 Created como você programou
                .andExpect(jsonPath("$.id").value(99)) // Navega no JSON de resposta e procura o ID 99
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.channel").value("PHYSICAL"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.totalValue").value(150.50));
    }
}
>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
