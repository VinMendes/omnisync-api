package com.puccampinas.omnisync.core.product.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.service.ProductService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void getBySkuShouldReturnNotFoundWhenSkuDoesNotExist() throws Exception {
        when(productService.getBySku(1L, "SKU-404"))
                .thenThrow(new EntityNotFoundException("SKU não encontrada."));

        mockMvc.perform(get("/api/products/1/sku/SKU-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("SKU não encontrada."));
    }

    @Test
    void getBySkuShouldReturnProductWhenSkuExists() throws Exception {
        ProductDto product = new ProductDto();
        product.setId(10L);
        product.setSystemClientId(1L);
        product.setSku("SKU-1");
        product.setName("Product 1");
        product.setDescription("Valid description");
        product.setPrice(new BigDecimal("99.90"));

        when(productService.getBySku(1L, "SKU-1")).thenReturn(product);

        mockMvc.perform(get("/api/products/1/sku/SKU-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.system_client_id").value(1L))
                .andExpect(jsonPath("$.sku").value("SKU-1"))
                .andExpect(jsonPath("$.name").value("Product 1"));
    }
}
