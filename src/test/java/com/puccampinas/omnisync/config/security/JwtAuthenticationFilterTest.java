package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    private AuthCookieService authCookieService;

    @MockitoBean
    private Claims claims;

    @RestController
    public static class TestSecureController {

        @GetMapping("/test/secure")
        public String secure() {
            return "ok";
        }
    }

    @Test
    void shouldDenyAccessWhenNoTokenIsProvided() throws Exception {
        mockMvc.perform(get("/test/secure"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowAccessWithValidBearerAccessToken() throws Exception {
        String token = "valid-access-token";

        when(jwtService.validateAndGetClaims(token, JwtService.TYPE_ACCESS))
                .thenReturn(claims);
        when(claims.getSubject())
                .thenReturn("vinicius@email.com");

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

        doThrow(new RuntimeException("Token inválido"))
                .when(jwtService)
                .validateAndGetClaims(token, JwtService.TYPE_ACCESS);

        mockMvc.perform(
                        get("/test/secure")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                )
                .andExpect(status().isForbidden());
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
                .andExpect(status().isForbidden());
    }
}