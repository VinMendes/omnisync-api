package com.puccampinas.omnisync.core.auth.controller;

import com.puccampinas.omnisync.core.auth.service.RegistrationService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.config.security.JwtAuthenticationFilter;
import com.puccampinas.omnisync.config.security.SecurityBeansConfig;
import com.puccampinas.omnisync.config.security.SecurityConfig;
import com.puccampinas.omnisync.core.auth.cookie.AuthCookieService;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetEmailService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetTokenRepository;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.auth.service.AuthService;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.controller.UserController;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.core.users.service.UserService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP, provider BCrypt, JWT e cookies reais; somente persistência e e-mail são substituídos. */
@WebMvcTest(controllers = {AuthController.class, UserController.class})
@Import({SecurityConfig.class, SecurityBeansConfig.class, JwtAuthenticationFilter.class,
        CustomUserDetailsService.class, AuthService.class, JwtService.class, AuthCookieService.class,
        UserService.class, AuthFlowIntegrationTest.IdentityController.class})
@TestPropertySource(properties = {
        "app.jwt.secret=" + AuthFlowIntegrationTest.TEST_SECRET,
        "app.jwt.accessExpMinutes=20", "app.jwt.refreshExpDays=7",
        "app.cookie.accessName=ACCESS_TOKEN", "app.cookie.refreshName=REFRESH_TOKEN",
        "app.cookie.secure=false", "app.frontend.reset-password-url=http://localhost/reset-password",
        "app.cors.allowed-origins=http://localhost:5173"
})
class AuthFlowIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.puccampinas.omnisync.core.audit.AuditService audit;

    @MockitoBean
    private RegistrationService registrationService;

    static final String TEST_SECRET = "isolated-auth-flow-test-key-0123456789-0123456789";
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "valid-test-password";
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private SystemClientRepository clientRepository;
    @MockitoBean
    private TenantRoleRepository tenantRoleRepository;
    @MockitoBean
    private PasswordResetTokenRepository resetTokenRepository;
    @MockitoBean
    private PasswordResetEmailService resetEmailService;

    private User user;
    private SystemClient company;

    @BeforeEach
    void account() {
        user = new User();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setSystemClientId(10L);
        user.setName("Usuário de teste");
        user.setEmail(EMAIL);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setActive(true);
        user.setResource(UserResource.defaults());
        user.setTenantRole(role(Role.ADMIN, Role.ADMIN.defaultPermissions()));
        company = new SystemClient();
        company.setId(10L);
        company.setActive(true);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(clientRepository.findById(10L)).thenReturn(Optional.of(company));
        when(tenantRoleRepository.save(any(TenantRole.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void shouldLoginWithCurrentJsonAndCookiesAndReadMe() throws Exception {
        MvcResult login = login(EMAIL.toUpperCase(), PASSWORD).andExpect(status().isOk()).andReturn();
        JsonNode body = json.readTree(login.getResponse().getContentAsString());
        assertThat(body.size()).isEqualTo(7);
        assertThat(body.get("userId").asLong()).isEqualTo(1L);
        assertThat(body.get("name").asText()).isEqualTo(user.getName());
        assertThat(body.get("email").asText()).isEqualTo(EMAIL);
        assertThat(body.get("active").asBoolean()).isTrue();
        assertThat(body.get("message").asText()).isEqualTo("Login realizado com sucesso");
        Cookie access = login.getResponse().getCookie("ACCESS_TOKEN");
        Cookie refresh = login.getResponse().getCookie("REFRESH_TOKEN");
        assertCookie(access, body.get("accessToken").asText(), 900);
        assertCookie(refresh, body.get("refreshToken").asText(), 604800);
        assertThat(jwtService.validateAndGetClaims(access.getValue(), JwtService.TYPE_ACCESS).getSubject())
                .isEqualTo(EMAIL);
        assertThat(jwtService.validateAndGetClaims(refresh.getValue(), JwtService.TYPE_REFRESH).getSubject())
                .isEqualTo(EMAIL);
        assertThat(login.getRequest().getSession(false)).isNull();

        mvc.perform(get("/api/users/me").cookie(access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.systemClientId").value(10))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.resource.role").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "PRODUCT_WRITE", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE",
                        "USER_MANAGE", "INTEGRATION_MANAGE", "SETTINGS_MANAGE", "AUDIT_READ")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/test/auth-identity").header(HttpHeaders.AUTHORIZATION, "Bearer " + access.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(EMAIL))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.systemClientId").value(10))
                .andExpect(jsonPath("$.credentialsErased").value(true));
        assertThat(passwordEncoder.matches(PASSWORD, user.getPasswordHash())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-password", "missing-user", "inactive-user", "inactive-company", "missing-company"})
    void shouldRejectInvalidCredentialsOrUnavailableAccountWithSameResponse(String scenario) throws Exception {
        switch (scenario) {
            case "missing-user" -> when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            case "inactive-user" -> user.setActive(false);
            case "inactive-company" -> company.setActive(false);
            case "missing-company" -> when(clientRepository.findById(10L)).thenReturn(Optional.empty());
        }
        login(EMAIL, scenario.equals("wrong-password") ? "wrong-password" : PASSWORD)
                .andExpect(status().isUnauthorized()).andExpect(content().string("Credenciais inválidas"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void shouldNotDisguiseDatabaseFailureAsInvalidPassword() throws Exception {
        when(userRepository.findByEmail(EMAIL)).thenThrow(new DataAccessResourceFailureException("test database offline"));
        login(EMAIL, PASSWORD).andExpect(status().isServiceUnavailable())
                .andExpect(content().string("Serviço de autenticação indisponível"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void shouldAllowLoginDespiteExpiredAccessCookie() throws Exception {
        mvc.perform(post("/api/auth/login").cookie(expiredCookie())
                        .contentType(MediaType.APPLICATION_JSON).content(credentials(EMAIL, PASSWORD)))
                .andExpect(status().isOk()).andExpect(cookie().exists("ACCESS_TOKEN"));
    }

    @Test
    void shouldRefreshDespiteExpiredAccessCookieAndReadProtectedRoute() throws Exception {
        MvcResult login = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
        Cookie refresh = login.getResponse().getCookie("REFRESH_TOKEN");
        MvcResult renewed = mvc.perform(post("/api/auth/refresh").cookie(expiredCookie(), refresh))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Access renovado com sucesso"))
                .andExpect(cookie().doesNotExist("REFRESH_TOKEN")).andReturn();
        Cookie access = renewed.getResponse().getCookie("ACCESS_TOKEN");
        assertCookie(access, json.readTree(renewed.getResponse().getContentAsString()).get("accessToken").asText(), 900);
        mvc.perform(get("/api/users/me").cookie(access)).andExpect(status().isOk());
    }

    @Test
    void shouldStillAcceptRefreshTokenInBearerHeader() throws Exception {
        mvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateRefreshToken(EMAIL)))
                .andExpect(status().isOk()).andExpect(cookie().exists("ACCESS_TOKEN"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive-user", "deleted-user", "inactive-company", "deleted-company"})
    void shouldRecheckAccountForAlreadyIssuedAccessAndRefresh(String scenario) throws Exception {
        MvcResult login = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn();
        switch (scenario) {
            case "inactive-user" -> user.setActive(false);
            case "deleted-user" -> when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
            case "inactive-company" -> company.setActive(false);
            case "deleted-company" -> when(clientRepository.findById(10L)).thenReturn(Optional.empty());
        }
        mvc.perform(get("/api/users/me").cookie(login.getResponse().getCookie("ACCESS_TOKEN")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(login.getResponse().getCookie("REFRESH_TOKEN")))
                .andExpect(status().isUnauthorized()).andExpect(cookie().maxAge("ACCESS_TOKEN", 0))
                .andExpect(cookie().maxAge("REFRESH_TOKEN", 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "wrong-type", "wrong-signature", "malformed"})
    void shouldRejectInvalidAccessAndRefreshTokens(String scenario) throws Exception {
        String access;
        String refresh;
        switch (scenario) {
            case "expired" -> {
                access = expiredToken(JwtService.TYPE_ACCESS);
                refresh = expiredToken(JwtService.TYPE_REFRESH);
            }
            case "wrong-type" -> {
                access = jwtService.generateRefreshToken(EMAIL);
                refresh = jwtService.generateAccessToken(EMAIL);
            }
            case "wrong-signature" -> {
                JwtService otherIssuer = new JwtService("other-test-key-0123456789-0123456789-0123456789", 20, 7);
                access = otherIssuer.generateAccessToken(EMAIL);
                refresh = otherIssuer.generateRefreshToken(EMAIL);
            }
            default -> { access = "not-a-jwt"; refresh = "not-a-jwt"; }
        }
        mvc.perform(get("/api/users/me").cookie(new Cookie("ACCESS_TOKEN", access)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("REFRESH_TOKEN", refresh)))
                .andExpect(status().isUnauthorized()).andExpect(cookie().maxAge("REFRESH_TOKEN", 0));
    }

    @Test
    void shouldNotUseCookieAsFallbackForInvalidBearerToken() throws Exception {
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                        .cookie(new Cookie("ACCESS_TOKEN", jwtService.generateAccessToken(EMAIL))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(EMAIL))
                        .cookie(expiredCookie()))
                .andExpect(status().isOk());
    }

    @Test
    void shouldKeepPublicRoutesAccessibleAndProtectedRoutesStateless() throws Exception {
        mvc.perform(get("/api/auth/ping").cookie(expiredCookie())).andExpect(status().isOk());
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldClearBothCookiesOnLogoutDespiteExpiredAccess() throws Exception {
        mvc.perform(post("/api/auth/logout").cookie(expiredCookie()))
                .andExpect(status().isOk()).andExpect(cookie().maxAge("ACCESS_TOKEN", 0))
                .andExpect(cookie().maxAge("REFRESH_TOKEN", 0))
                .andExpect(cookie().httpOnly("ACCESS_TOKEN", true));
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectLegacyRegistrationWithoutAnAdminSession() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", 10, "name", "Nova conta",
                                "email", "new@example.com", "password", PASSWORD, "resource", Map.of("role", "viewer")))))
                .andExpect(status().isForbidden())
                .andExpect(cookie().doesNotExist("ACCESS_TOKEN"))
                .andExpect(cookie().doesNotExist("REFRESH_TOKEN"));
        org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
    }

    @Test
    void shouldPreserveCredentialedCorsPreflight() throws Exception {
        mvc.perform(options("/api/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void shouldNotSwallowDatabaseFailuresDuringTokenValidation() {
        when(userRepository.findByEmail(EMAIL)).thenThrow(new DataAccessResourceFailureException("test database offline"));
        assertThatThrownBy(() -> mvc.perform(get("/api/users/me")
                .cookie(new Cookie("ACCESS_TOKEN", jwtService.generateAccessToken(EMAIL)))))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void shouldReloadRoleChangesWithTheSameAccessTokenWithoutChangingItsFormat() throws Exception {
        user.setTenantRole(role(Role.ADMIN, Set.of(Permission.USER_MANAGE)));
        Cookie access = login(EMAIL, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getCookie("ACCESS_TOKEN");

        assertThat(jwtService.validateAndGetClaims(access.getValue()).keySet())
                .containsExactlyInAnyOrder("sub", "iat", "exp", "token_type");
        mvc.perform(get("/test/auth-identity").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities", containsInAnyOrder("ROLE_ADMIN", "PERM_USER_MANAGE")));

        user.setTenantRole(role(Role.VIEWER, Set.of(Permission.PRODUCT_READ)));
        mvc.perform(get("/test/auth-identity").cookie(access)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities", containsInAnyOrder("ROLE_VIEWER", "PERM_PRODUCT_READ")));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(email, password)));
    }

    private String credentials(String email, String password) throws Exception {
        return json.writeValueAsString(Map.of("email", email, "password", password));
    }

    private Cookie expiredCookie() {
        return new Cookie("ACCESS_TOKEN", expiredToken(JwtService.TYPE_ACCESS));
    }

    private String expiredToken(String type) {
        return Jwts.builder().subject(EMAIL).claim(JwtService.CLAIM_TOKEN_TYPE, type)
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    private void assertCookie(Cookie cookie, String token, int maxAge) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo(token);
        assertThat(cookie.getMaxAge()).isEqualTo(maxAge);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/");
    }

    private TenantRole role(Role name, Set<Permission> permissions) {
        TenantRole role = new TenantRole();
        role.setSystemClientId(10L);
        role.setName(name);
        role.setResource(RoleResource.of(permissions));
        return role;
    }

    @RestController
    public static class IdentityController {
        @GetMapping("/test/auth-identity")
        public Map<String, Object> identity(Authentication authentication) {
            OmniUserPrincipal principal = (OmniUserPrincipal) authentication.getPrincipal();
            return Map.of("username", authentication.getName(), "userId", principal.getUserId(),
                    "systemClientId", principal.getSystemClientId(),
                    "authorities", principal.getAuthorities().stream().map(Object::toString).toList(),
                    "credentialsErased", authentication.getCredentials() == null && principal.getPassword() == null);
        }
    }
}
