package com.puccampinas.omnisync.core.dashboard.controller;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardSalesDay;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardSummary;
import com.puccampinas.omnisync.core.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void returnsDashboardContractAndPassesAuthenticatedPrincipal() throws Exception {
        OmniUserPrincipal principal = principal();
        DashboardSummary response = new DashboardSummary(
                128, new BigDecimal("2.4"), 4521, new BigDecimal("-0.8"),
                new BigDecimal("48250.90"), 83, new BigDecimal("5.1"),
                new BigDecimal("14290.00"), new BigDecimal("12.3"), 18, 7,
                List.of(new DashboardSalesDay(LocalDate.parse("2026-08-20"), new BigDecimal("4200.00"), 12)),
                List.of()
        );
        when(dashboardService.getSummary(3L, "7d", principal)).thenReturn(response);

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
        try {
            mockMvc.perform(get("/api/dashboard/3/summary?range=7d"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalProducts").value(128))
                    .andExpect(jsonPath("$.totalStock").value(4521))
                    .andExpect(jsonPath("$.inventoryValue").value(48250.90))
                    .andExpect(jsonPath("$.activeListings").value(83))
                    .andExpect(jsonPath("$.revenueToday").value(14290.00))
                    .andExpect(jsonPath("$.salesTodayCount").value(18))
                    .andExpect(jsonPath("$.lowStockCount").value(7))
                    .andExpect(jsonPath("$.recentEvents").isArray())
                    .andExpect(jsonPath("$.salesByDay[0].date").value("2026-08-20"))
                    .andExpect(jsonPath("$.salesByDay[0].total").value(4200.00))
                    .andExpect(jsonPath("$.salesByDay[0].count").value(12));
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(dashboardService).getSummary(3L, "7d", principal);
    }

    private OmniUserPrincipal principal() {
        return new OmniUserPrincipal(
                1L, 3L, "Dashboard user", "dashboard@example.com", null, true, true,
                List.of(new SimpleGrantedAuthority("PRODUCT_READ"), new SimpleGrantedAuthority("SALE_READ"))
        );
    }
}
