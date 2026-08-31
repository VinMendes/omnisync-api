package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@WebMvcTest(controllers = JwtAuthenticationFilterTest.TestSecureController.class)
@Import({
        SecurityConfig.class,
        JwtAuthenticationFilter.class,
        JwtAuthenticationFilterTest.TestSecureController.class
})
class JwtAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private CustomUserDetailsService userDetailsService;

    @MockitoBean
    private AuthCookieService authCookieService;

    @MockitoBean
    private Claims claims;

    @RestController
    public static class TestSecureController {

        @GetMapping("/test/secure")
        public String secure() {
            return "ok";
        }

        @GetMapping("/test/identity")
        public Map<String, Object> identity(Authentication authentication) {
            OmniUserPrincipal principal = (OmniUserPrincipal) authentication.getPrincipal();
            return Map.of(
                    "username", authentication.getName(),
                    "userId", principal.getUserId(),
                    "systemClientId", principal.getSystemClientId(),
                    "authorities", authentication.getAuthorities().stream().map(Object::toString).toList()
            );
        }
    }

    @Test
    void shouldDenyAccessWhenNoTokenIsProvided() throws Exception {
        mockMvc.perform(get("/test/secure"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void shouldAllowAccessWithValidBearerAccessToken() throws Exception {
        String token = "valid-access-token";

        when(jwtService.validateAndGetClaims(token, JwtService.TYPE_ACCESS))
                .thenReturn(claims);
        when(claims.getSubject())
                .thenReturn("vinicius@email.com");
        when(userDetailsService.loadActiveUserByUsername("vinicius@email.com")).thenReturn(principal());

        mockMvc.perform(
                        get("/test/secure")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void shouldAllowAccessWithValidAccessTokenCookie() throws Exception {
        String token = "valid-access-token";

        when(authCookieService.getAccessCookieName()).thenReturn("ACCESS_TOKEN");
        when(jwtService.validateAndGetClaims(token, JwtService.TYPE_ACCESS))
                .thenReturn(claims);
        when(claims.getSubject())
                .thenReturn("vinicius@email.com");
        when(userDetailsService.loadActiveUserByUsername("vinicius@email.com")).thenReturn(principal());

        mockMvc.perform(
                        get("/test/secure")
                                .cookie(new Cookie("ACCESS_TOKEN", token))
                )
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void shouldDenyAccessWithInvalidBearerToken() throws Exception {
        String token = "invalid-access-token";

        doThrow(new JwtException("Token inválido"))
                .when(jwtService)
                .validateAndGetClaims(token, JwtService.TYPE_ACCESS);

        mockMvc.perform(
                        get("/test/secure")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void shouldDenyAccessWhenRefreshTokenIsUsedOnProtectedRoute() throws Exception {
        String token = "refresh-token";

        doThrow(new IllegalArgumentException("Token type inválido: refresh"))
                .when(jwtService)
                .validateAndGetClaims(token, JwtService.TYPE_ACCESS);

        mockMvc.perform(
                        get("/test/secure")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void shouldPopulateAuthenticationWithPrincipalAndItsAuthorities() throws Exception {
        stubValidToken();
        when(userDetailsService.loadActiveUserByUsername("vinicius@email.com")).thenReturn(principal());

        mockMvc.perform(get("/test/identity").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("vinicius@email.com"))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.systemClientId").value(10))
                .andExpect(jsonPath("$.authorities[0]").value("test:read"));
    }

    @Test
    void shouldRejectSignedTokenWhenUserNoLongerExists() throws Exception {
        stubValidToken();
        when(userDetailsService.loadActiveUserByUsername("vinicius@email.com"))
                .thenThrow(new UsernameNotFoundException("Usuário não encontrado."));

        mockMvc.perform(get("/test/secure").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectSignedTokenWhenAccountIsInactive() throws Exception {
        stubValidToken();
        when(userDetailsService.loadActiveUserByUsername("vinicius@email.com"))
                .thenThrow(new DisabledException("User is disabled"));

        mockMvc.perform(get("/test/secure").header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isUnauthorized());
    }

    private void stubValidToken() {
        when(jwtService.validateAndGetClaims("valid-token", JwtService.TYPE_ACCESS)).thenReturn(claims);
        when(claims.getSubject()).thenReturn("vinicius@email.com");
    }

    private OmniUserPrincipal principal() {
        return new OmniUserPrincipal(
                1L, 10L, "Usuário de teste", "vinicius@email.com", null, true, true,
                List.of(new SimpleGrantedAuthority("test:read"))
        );
    }
}
