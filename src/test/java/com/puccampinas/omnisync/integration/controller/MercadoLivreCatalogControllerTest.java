package com.puccampinas.omnisync.integration.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreSyncResponse;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MercadoLivreCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
class MercadoLivreCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MercadoLivreListingService mercadoLivreListingService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void syncSellerListingsShouldReturnSyncSummary() throws Exception {
        MercadoLivreSyncResponse response = new MercadoLivreSyncResponse();
        response.setSellerUserId("123456");
        response.setSystemClientId(1L);
        response.setTotalListings(2);
        response.setSyncedProducts(2);
        response.setCreated(1);
        response.setUpdated(0);
        response.setReactivated(1);
        response.setDeactivated(1);

        when(productService.syncMercadoLivreProducts("user@test.com", 1L)).thenReturn(response);

        mockMvc.perform(post("/api/integrations/mercadolivre/catalog/1/sync")
                        .principal(new UsernamePasswordAuthenticationToken("user@test.com", null, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seller_user_id").value("123456"))
                .andExpect(jsonPath("$.system_client_id").value(1L))
                .andExpect(jsonPath("$.total_listings").value(2))
                .andExpect(jsonPath("$.synced_products").value(2))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.reactivated").value(1))
                .andExpect(jsonPath("$.deactivated").value(1));
    }
}
