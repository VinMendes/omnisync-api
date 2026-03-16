package com.puccampinas.omnisync.core.sale.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

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
    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
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