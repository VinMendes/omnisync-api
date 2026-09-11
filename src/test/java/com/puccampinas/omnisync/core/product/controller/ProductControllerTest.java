package com.puccampinas.omnisync.core.product.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.product.dto.LowStockProductsResponse;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.service.ProductService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;
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
    private CustomUserDetailsService userDetailsService;

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

    @Test
    void getLowStockShouldReturnTheDocumentedPaginationContract() throws Exception {
        OmniUserPrincipal principal = new OmniUserPrincipal(
                8L, 1L, "Product reader", "reader@example.com", null, true, true,
                List.of(new SimpleGrantedAuthority("PRODUCT_READ"))
        );
        ProductDto product = new ProductDto();
        product.setId(51L);
        product.setSystemClientId(1L);
        product.setSku("TENIS-001");
        product.setName("Tênis esportivo");
        product.setStock(4);
        product.setReservedStock(1);
        product.setMinimumStock(5);
        product.setAvailableStock(3);
        product.setLowStock(true);
        product.setPrice(new BigDecimal("199.90"));
        LowStockProductsResponse response = new LowStockProductsResponse(
                List.of(product), 0, 20, 1, false
        );
        when(productService.getLowStock(1L, 0, 20, principal)).thenReturn(response);

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()
        ));
        try {
            mockMvc.perform(get("/api/products/1/low-stock?offset=0&limit=20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(51))
                    .andExpect(jsonPath("$.content[0].available_stock").value(3))
                    .andExpect(jsonPath("$.content[0].minimum_stock").value(5))
                    .andExpect(jsonPath("$.content[0].low_stock").value(true))
                    .andExpect(jsonPath("$.offset").value(0))
                    .andExpect(jsonPath("$.limit").value(20))
                    .andExpect(jsonPath("$.total_elements").value(1))
                    .andExpect(jsonPath("$.has_next").value(false));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(productService).getLowStock(1L, 0, 20, principal);
    }
}
