package com.puccampinas.omnisync.core.dashboard.controller;

import com.puccampinas.omnisync.core.activity.controller.ActivityMetricsController;
import com.puccampinas.omnisync.core.activity.dto.ActivitySalesHour;
import com.puccampinas.omnisync.core.activity.dto.ActivitySummary;
import com.puccampinas.omnisync.core.activity.service.ActivityMetricsService;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardAnalytics;
import com.puccampinas.omnisync.core.dashboard.dto.DashboardInventoryDay;
import com.puccampinas.omnisync.core.dashboard.service.DashboardAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

@WebMvcTest({DashboardAnalyticsController.class, ActivityMetricsController.class})
@AutoConfigureMockMvc(addFilters = false)
class PendingMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private DashboardAnalyticsService dashboardService;
    @MockitoBean
    private ActivityMetricsService activityService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private CustomUserDetailsService userDetailsService;
    @MockitoBean
    private AuthCookieService authCookieService;

    @Test
    void returnsAnalyticsContract() throws Exception {
        OmniUserPrincipal principal = principal();
        when(dashboardService.getAnalytics(3L, "7d", principal)).thenReturn(new DashboardAnalytics(
                List.of(new DashboardInventoryDay(
                        LocalDate.parse("2026-09-20"), 842, new BigDecimal("48250.90"))),
                List.of()
        ));
        authenticate(principal);
        try {
            mockMvc.perform(get("/api/dashboard/3/analytics?range=7d"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.inventoryByDay[0].date").value("2026-09-20"))
                    .andExpect(jsonPath("$.inventoryByDay[0].quantity").value(842))
                    .andExpect(jsonPath("$.inventoryByDay[0].value").value(48250.90))
                    .andExpect(jsonPath("$.sponsoredSalesByDay").isEmpty());
        } finally {
            SecurityContextHolder.clearContext();
        }
        verify(dashboardService).getAnalytics(3L, "7d", principal);
    }

    @Test
    void returnsActivityContractWithZeroHours() throws Exception {
        OmniUserPrincipal principal = principal();
        when(activityService.getSummary(3L, "2026-09-20", "MERCADO_LIVRE", principal))
                .thenReturn(new ActivitySummary(List.of(
                        new ActivitySalesHour(0, 0, new BigDecimal("0.00")),
                        new ActivitySalesHour(8, 3, new BigDecimal("420.00"))
                )));
        authenticate(principal);
        try {
            mockMvc.perform(get("/api/activity/3/summary")
                            .param("date", "2026-09-20")
                            .param("marketplace", "MERCADO_LIVRE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.salesByHour[0].hour").value(0))
                    .andExpect(jsonPath("$.salesByHour[0].count").value(0))
                    .andExpect(jsonPath("$.salesByHour[1].hour").value(8))
                    .andExpect(jsonPath("$.salesByHour[1].total").value(420.00));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mapsInsufficientPermissionToForbidden() throws Exception {
        OmniUserPrincipal principal = principal();
        when(activityService.getSummary(3L, "2026-09-20", null, principal))
                .thenThrow(new AccessDeniedException("SALE_READ permission is required."));
        authenticate(principal);
        try {
            mockMvc.perform(get("/api/activity/3/summary").param("date", "2026-09-20"))
                    .andExpect(status().isForbidden());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(OmniUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private OmniUserPrincipal principal() {
        return new OmniUserPrincipal(1L, 3L, "User", "user@example.com", null, true, true,
                List.of(new SimpleGrantedAuthority("PERM_PRODUCT_READ"),
                        new SimpleGrantedAuthority("PERM_SALE_READ")));
    }
}
