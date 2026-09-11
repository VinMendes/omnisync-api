package com.puccampinas.omnisync.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.service.AuthService;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.integration.service.MercadoLivreAuthService;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real commits/rollbacks, JWT and services; only the remote marketplace is mocked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class AuditIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired SystemClientRepository clients;
    @Autowired MarketplaceIntegrationRepository integrations;
    @Autowired ProductRepository products;
    @Autowired ProductService productService;
    @Autowired AuditService audit;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CustomUserDetailsService details;
    @Autowired TextEncryptor encryptor;
    @Autowired com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetTokenRepository resetTokens;
    @MockitoBean MercadoLivreClient remote;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean AuditLogRepository auditRepository;
    final ObjectMapper json = new ObjectMapper();
    Long tenant;
    User admin;
    Cookie cookie;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        SystemClient company = new SystemClient();
        company.setName("Audit test");
        company.setDocument(UUID.randomUUID().toString().substring(0, 18));
        company.setActive(true);
        company.setResource(Map.of("mercado_livre", true));
        tenant = clients.saveAndFlush(company).getId();
        admin = user(Role.ADMIN, Permission.names(Role.ADMIN.defaultPermissions()));
        cookie = cookie(admin);
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void querySupportsEveryFilterAndExactOffsetWithStableNewestFirstOrder() throws Exception {
        seed(AuditAction.UPDATE, AuditEntityType.PRODUCT, "42");
        seed(AuditAction.UPDATE, AuditEntityType.PRODUCT, "43");
        seed(AuditAction.CREATE, AuditEntityType.PRODUCT, "44");
        jdbc.update("UPDATE audit_logs SET created_at = '2026-09-10 14:35:12' WHERE system_client_id = ?", tenant);
        var response = mvc.perform(get(path()).cookie(cookie)
                        .param("userId", admin.getId().toString()).param("role", "ADMIN")
                        .param("action", "UPDATE").param("entityType", "PRODUCT")
                        .param("from", "2026-09-10T14:35:12").param("to", "2026-09-10T14:35:12")
                        .param("offset", "1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_elements").value(2))
                .andExpect(jsonPath("$.offset").value(1)).andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.has_next").value(false))
                .andExpect(jsonPath("$.content[0].entity_id").value("42"))
                .andExpect(jsonPath("$.content[0].system_client_id").value(tenant))
                .andExpect(jsonPath("$.content[0].user.id").value(admin.getId())).andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).get("content").size()).isEqualTo(1);
        mvc.perform(get(path()).cookie(cookie).param("action", "UPDATE").param("limit", "1"))
                .andExpect(jsonPath("$.content[0].entity_id").value("43")).andExpect(jsonPath("$.has_next").value(true));
        mvc.perform(get(path()).cookie(cookie).param("offset", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.has_next").value(false));
        mvc.perform(get(path()).cookie(cookie).param("userId", "99999999"))
                .andExpect(jsonPath("$.total_elements").value(0));
        mvc.perform(get(path()).cookie(cookie).param("from", "2026-09-11T00:00:00"))
                .andExpect(jsonPath("$.total_elements").value(0));
    }

    @Test void rolesPermissionsAndTenantIsolationUseTheRealCookie() throws Exception {
        User viewer = user(Role.VIEWER, List.of());
        User reader = user(Role.SELLER, List.of("AUDIT_READ"));
        User customizedAdmin = user(Role.ADMIN, List.of());
        SystemClient other = new SystemClient();
        other.setName("Other audit tenant"); other.setDocument(UUID.randomUUID().toString().substring(0, 18)); other.setActive(true);
        other = clients.saveAndFlush(other);
        auth.register(new RegisterRequest(other.getId(), "Other user", UUID.randomUUID() + "@example.invalid",
                "other-password", Map.of(), "ADMIN", List.of()));
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(get(path()).cookie(cookie(viewer))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Permissão insuficiente: AUDIT_READ"))
                .andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(get(path()).cookie(cookie(reader))).andExpect(status().isOk());
        mvc.perform(get(path()).cookie(cookie(customizedAdmin))).andExpect(status().isOk());
        mvc.perform(get("/api/audit-logs/" + other.getId()).cookie(cookie)).andExpect(status().isNotFound());
        mvc.perform(get(path()).cookie(cookie)).andExpect(jsonPath("$.total_elements").value(4));
        mvc.perform(get("/api/audit-logs/" + (tenant + 100000)).cookie(cookie))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE roles SET resource = '{\"permissions\":[]}'::jsonb WHERE id = ?", reader.getTenantRole().getId());
        mvc.perform(get(path()).cookie(cookie(reader))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role=OWNER", "role=", "action=HACK", "entityType=BOGUS", "from=not-date",
            "to=2026-02-30T00:00:00", "offset=-1", "offset=2147483648", "offset=x",
            "limit=0", "limit=101", "userId=-1", "userId=abc"})
    void rejectsInvalidFilters(String pair) throws Exception {
        String[] parts = pair.split("=", -1);
        mvc.perform(get(path()).cookie(cookie).param(parts[0], parts[1])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test void rejectsInvertedPeriod() throws Exception {
        mvc.perform(get(path()).cookie(cookie).param("from", "2026-09-15T00:00:00").param("to", "2026-09-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test void userCreationUpdateAndStatusCaptureSnapshotsWithoutCredentials() throws Exception {
        var request = Map.of("systemClientId", tenant, "name", "Member", "email", UUID.randomUUID() + "@example.invalid",
                "password", "secret-password-sentinel", "role", "SELLER", "permissions", List.of("SALE_READ"),
                "resource", Map.of("oauthCode", "oauth-sentinel", "nested", Map.of("token", "token-sentinel")));
        var created = mvc.perform(post("/api/users").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(put("/api/users/" + id).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Edited member\",\"permissions\":[\"SALE_READ\",\"AUDIT_READ\"]}"))
                .andExpect(status().isOk());
        for (boolean active : List.of(false, true, true)) {
            mvc.perform(patch("/api/users/" + id + "/status").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":" + active + "}")).andExpect(status().isOk());
        }
        var events = events("USER", String.valueOf(id));
        assertThat(events).hasSize(4);
        assertThat(events.get(0).get("action").asText()).isEqualTo("CREATE");
        assertThat(events.get(0).path("user").path("id").asLong()).isEqualTo(admin.getId());
        assertThat(events.get(1).path("previous_data").path("name").asText()).isEqualTo("Member");
        assertThat(events.get(1).path("new_data").path("name").asText()).isEqualTo("Edited member");
        assertThat(events.get(2).get("action").asText()).isEqualTo("DEACTIVATE");
        assertThat(events.get(3).get("action").asText()).isEqualTo("ACTIVATE");
        assertThat(events.toString()).doesNotContain("secret-password-sentinel", "oauth-sentinel", "token-sentinel", "password");
    }

    @Test void productCrudAndSaleAreAuditedAndFailedBatchRollsBack() throws Exception {
        long id = createProduct(false);
        var update = productPayload(false);
        update.put("name", "Updated product"); update.put("stock", 15); update.put("price", 54.90);
        mvc.perform(put("/api/products/" + tenant + "/" + id).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update))).andExpect(status().isOk());
        var sale = Map.of("productId", id, "quantity", 2, "totalValue", 109.80, "resource", Map.of("token", "sale-secret"));
        mvc.perform(post("/api/sales/" + tenant).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(List.of(sale)))).andExpect(status().isOk());
        assertThat(count("SALE")).isEqualTo(1);
        long before = count(null);
        mvc.perform(post("/api/sales/" + tenant).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(List.of(sale, Map.of("productId", 999999999, "quantity", 1)))))
                .andExpect(status().isNotFound());
        assertThat(count(null)).isEqualTo(before);
        assertThat(products.findById(id).orElseThrow().getStock()).isEqualTo(13);
        mvc.perform(delete("/api/products/" + tenant + "/" + id).cookie(cookie)).andExpect(status().isOk());
        var events = events("PRODUCT", String.valueOf(id));
        assertThat(events).hasSize(3);
        assertThat(events.get(1).path("previous_data").path("stock").asInt()).isEqualTo(20);
        assertThat(events.get(1).path("new_data").path("stock").asInt()).isEqualTo(15);
        assertThat(events.get(2).get("action").asText()).isEqualTo("DELETE");
        assertThat(events.toString()).doesNotContain("resource-secret", "access_token");
    }

    @Test void explicitOuterRollbackRemovesBusinessAndAuditRowsTogether() {
        long before = count(null);
        var principal = details.loadUserByUsername(admin.getEmail());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        ProductDto dto = new ProductDto();
        dto.setSystemClientId(tenant); dto.setSku("rollback"); dto.setName("Rollback");
        dto.setDescription("Rollback"); dto.setStock(5); dto.setPrice(BigDecimal.ONE); dto.setResource(Map.of());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            productService.create(tenant, dto);
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count(null)).isEqualTo(before);
        assertThat(products.findBySkuAndSystemClientIdAndActiveTrue("rollback", tenant)).isEmpty();
    }

    @Test void auditPersistenceFailureAlsoRollsBackBusinessData() {
        long before = count(null);
        ProductDto dto = new ProductDto();
        dto.setSystemClientId(tenant); dto.setSku("audit-failure"); dto.setName("Audit failure");
        dto.setDescription("Test"); dto.setStock(1); dto.setPrice(BigDecimal.ONE); dto.setResource(Map.of());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("forced audit failure"))
                .when(auditRepository).save(any(AuditLog.class));
        try {
            assertThatThrownBy(() -> productService.create(tenant, dto))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(products.findBySkuAndSystemClientIdAndActiveTrue("audit-failure", tenant)).isEmpty();
            assertThat(count(null)).isEqualTo(before);
        } finally {
            reset(auditRepository);
        }
    }

    @Test void auditRequiresAnExistingTransaction() {
        assertThatThrownBy(() -> audit.record(tenant, AuditAction.CREATE, AuditEntityType.PRODUCT, "x",
                null, Map.of("name", "example"), AuditSource.SYSTEM))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test void migrationHasTenantConstraintAndIndexes() {
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name='audit_logs' AND column_name='system_client_id'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE tablename='audit_logs'", Integer.class)).isGreaterThanOrEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE system_client_id IS NULL", Long.class)).isZero();
    }

    @Test void passwordResetRecordsOnlyTheFactOfCredentialChange() throws Exception {
        var token = new com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetToken();
        token.setToken(UUID.randomUUID().toString()); token.setUser(admin);
        token.setExpiresAt(java.time.Instant.now().plusSeconds(600)); resetTokens.saveAndFlush(token);
        auth.resetPassword(new com.puccampinas.omnisync.core.auth.dto.ResetPasswordRequest(token.getToken(), "new-password-sentinel"));
        var events = events("USER", admin.getId().toString());
        JsonNode changed = events.getLast();
        assertThat(changed.path("metadata").path("source").asText()).isEqualTo("PASSWORD_RESET");
        assertThat(changed.path("previous_data").isNull()).isTrue();
        assertThat(changed.path("new_data").isNull()).isTrue();
        assertThat(events.toString()).doesNotContain(token.getToken(), "new-password-sentinel", admin.getPasswordHash());
    }

    @Test void publicSignupWithExistingCookieAttributesOnlyTheNewCompany() throws Exception {
        var signup = Map.of("companyName", "New company", "document", "11222333000181",
                "name", "New admin", "email", UUID.randomUUID() + "@example.invalid", "password", "signup-password");
        long before = count(null);
        var result = mvc.perform(post("/api/auth/register-company").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(signup))).andExpect(status().isOk()).andReturn();
        String access = result.getResponse().getCookie("ACCESS_TOKEN").getValue();
        var meResult = mvc.perform(get("/api/users/me").cookie(new Cookie("ACCESS_TOKEN", access))).andExpect(status().isOk()).andReturn();
        JsonNode me = json.readTree(meResult.getResponse().getContentAsString());
        long newTenant = me.get("systemClientId").asLong();
        mvc.perform(get("/api/audit-logs/" + newTenant).cookie(new Cookie("ACCESS_TOKEN", access)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.content[0].user.id").value(me.get("id").asLong()))
                .andExpect(jsonPath("$.content[0].metadata.source").value("SIGNUP"));
        assertThat(count(null)).isEqualTo(before);
    }

    @Test void signedCallbackAndIdempotentDisconnect() throws Exception {
        String state = oauthState();
        MercadoLivreTokenResponse token = new MercadoLivreTokenResponse();
        token.setAccessToken("oauth-access-sentinel"); token.setRefreshToken("oauth-refresh-sentinel");
        token.setExpiresIn(3600L); token.setUserId(tenant);
        when(remote.exchangeCode(eq("oauth-code-sentinel"), anyString())).thenReturn(token);
        mvc.perform(get("/api/integrations/mercadolivre/callback").param("state", state).param("code", "oauth-code-sentinel"))
                .andExpect(status().isOk());
        var integration = integrations.findBySystemClientIdAndMarketplace(tenant, Marketplace.MERCADO_LIVRE).orElseThrow();
        var connected = events("INTEGRATION", integration.getId().toString());
        assertThat(connected).hasSize(1);
        assertThat(connected.getFirst().path("user").path("id").asLong()).isEqualTo(admin.getId());
        assertThat(connected.getFirst().path("action").asText()).isEqualTo("CONNECT");
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/integrations/mercadolivre/disconnect").cookie(cookie)
                    .param("systemClientId", tenant.toString())).andExpect(status().isNoContent());
        }
        var all = events("INTEGRATION", integration.getId().toString());
        assertThat(all).hasSize(2);
        assertThat(all.getLast().path("action").asText()).isEqualTo("DISCONNECT");
        assertThat(all.toString()).doesNotContain("oauth-access-sentinel", "oauth-refresh-sentinel", "oauth-code-sentinel", state);
        var disconnected = integrations.findById(integration.getId()).orElseThrow();
        assertThat(disconnected.getActive()).isFalse();
        assertThat(disconnected.getAccessToken()).isEmpty();
        assertThat(disconnected.getRefreshToken()).isNull();
    }

    @Test void callbackRejectsTamperingAndRevokedPermission() throws Exception {
        String state = oauthState();
        String decoded = new String(Base64.getUrlDecoder().decode(state), java.nio.charset.StandardCharsets.UTF_8);
        String forged = Base64.getUrlEncoder().encodeToString(decoded.replaceFirst("^[0-9]+:", "99999:").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/integrations/mercadolivre/callback").param("state", forged).param("code", "unused"))
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE roles SET resource = '{\"permissions\":[]}'::jsonb WHERE id=?", admin.getTenantRole().getId());
        mvc.perform(get("/api/integrations/mercadolivre/callback").param("state", state).param("code", "unused"))
                .andExpect(status().isForbidden());
        verify(remote, never()).exchangeCode(anyString(), anyString());
        assertThat(count("INTEGRATION")).isZero();
    }

    @Test void disconnectChecksPermissionsAndTenant() throws Exception {
        User viewer = user(Role.VIEWER, List.of());
        mvc.perform(delete("/api/integrations/mercadolivre/disconnect").param("systemClientId", tenant.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/integrations/mercadolivre/disconnect").cookie(cookie(viewer))
                .param("systemClientId", tenant.toString())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/integrations/mercadolivre/disconnect").cookie(cookie)
                .param("systemClientId", "99999999")).andExpect(status().isNotFound());
        assertThat(count("INTEGRATION")).isZero();
    }

    @Test void syncSourcesAndFailedSync() throws Exception {
        var integration = integration();
        when(remote.getMyUser(anyString())).thenReturn(Map.of("id", tenant));
        when(remote.searchSellerItems(anyString(), eq(tenant), anyMap())).thenReturn(Map.of("results", List.of(), "paging", Map.of("total", 0)));
        for (String source : List.of("MANUAL", "AUTOMATIC", "WEB")) {
            mvc.perform(post("/api/integrations/mercadolivre/catalog/" + tenant + "/sync").cookie(cookie)
                    .param("source", source)).andExpect(status().isOk());
        }
        var events = events("INTEGRATION", integration.getId().toString());
        assertThat(events).hasSize(3);
        assertThat(events.stream().map(e -> e.path("metadata").path("source").asText()).toList())
                .containsExactly("MANUAL", "AUTOMATIC", "WEB");
        assertThat(events.stream().map(e -> e.path("action").asText()).toList()).containsOnly("SYNC");
        when(remote.getMyUser(anyString())).thenThrow(new com.puccampinas.omnisync.common.exception.ExternalApiException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "upstream-secret-sentinel"));
        mvc.perform(post("/api/integrations/mercadolivre/catalog/" + tenant + "/sync").cookie(cookie))
                .andExpect(status().isBadGateway());
        assertThat(count("INTEGRATION")).isEqualTo(3);
        mvc.perform(post("/api/integrations/mercadolivre/catalog/" + tenant + "/sync").cookie(cookie).param("source", "SYSTEM"))
                .andExpect(status().isBadRequest());
    }

    @Test void invalidGrantDisconnectCommitsWithoutSuccessfulSync() throws Exception {
        var integration = integration();
        integration.setExpiresAt(LocalDateTime.now().minusHours(1)); integration.setRefreshToken(null);
        integrations.saveAndFlush(integration);
        mvc.perform(post("/api/integrations/mercadolivre/catalog/" + tenant + "/sync").cookie(cookie))
                .andExpect(status().isConflict());
        var events = events("INTEGRATION", integration.getId().toString());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().path("action").asText()).isEqualTo("DISCONNECT");
        assertThat(events.getFirst().path("user").path("role").asText()).isEqualTo("SYSTEM");
        assertThat(integrations.findById(integration.getId()).orElseThrow().getActive()).isFalse();
    }

    @Test void publishCloseAndRollbackNeverCopyProviderSecrets() throws Exception {
        integration();
        when(remote.createItem(anyString(), anyMap())).thenReturn(Map.of("id", "MLB-AUDIT", "status", "active", "access_token", "provider-secret"));
        when(remote.getItem(anyString(), eq("MLB-AUDIT"))).thenReturn(Map.of("id", "MLB-AUDIT", "status", "active"));
        when(remote.updateItem(anyString(), eq("MLB-AUDIT"), anyMap())).thenReturn(Map.of("id", "MLB-AUDIT", "status", "closed", "token", "provider-secret"));
        long id = createProduct(false);
        mvc.perform(post("/api/products/" + tenant + "/" + id + "/announce").cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(listingMetadata())))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/products/" + tenant + "/" + id).cookie(cookie)).andExpect(status().isOk());
        var events = events("LISTING", "MLB-AUDIT");
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().path("action").asText()).isEqualTo("PUBLISH");
        assertThat(events.getLast().path("action").asText()).isEqualTo("CLOSE");
        assertThat(events.toString()).doesNotContain("provider-secret", "access_token", "test-access-token");
        long before = count(null);
        var payload = productPayload(true);
        payload.put("resource", Map.of("mercado_livre", listingMetadata()));
        when(remote.createItem(anyString(), anyMap())).thenThrow(new com.puccampinas.omnisync.common.exception.ExternalApiException(
                org.springframework.http.HttpStatus.BAD_GATEWAY, "provider-failure"));
        mvc.perform(post("/api/products/" + tenant).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload))).andExpect(status().isBadGateway());
        assertThat(count(null)).isEqualTo(before);
        assertThat(products.findBySkuAndSystemClientIdAndActiveTrue((String) payload.get("sku"), tenant)).isEmpty();
    }

    @Test void webhookSaleUsesSystemActorAndDoesNotDuplicateAudit() throws Exception {
        integration();
        long id = createProduct(false);
        var product = products.findById(id).orElseThrow();
        product.setResource(Map.of("mercado_livre", Map.of("item_id", "MLB-WEBHOOK")));
        products.saveAndFlush(product);
        when(remote.getOrder(anyString(), eq("123"))).thenReturn(Map.of("id", 123, "status", "paid",
                "access_token", "order-secret", "order_items", List.of(Map.of(
                        "item", Map.of("id", "MLB-WEBHOOK"), "quantity", 2, "unit_price", 10))));
        String body = json.writeValueAsString(Map.of("resource", "/orders/123", "user_id", tenant, "topic", "orders_v2"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/notifications").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        assertThat(count("SALE")).isEqualTo(1);
        var result = mvc.perform(get(path()).cookie(cookie).param("entityType", "SALE").param("role", "SYSTEM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_elements").value(1))
                .andExpect(jsonPath("$.content[0].user.id").isEmpty())
                .andExpect(jsonPath("$.content[0].metadata.source").value("WEBHOOK")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("order-secret");
    }

    private String oauthState() throws Exception {
        when(remote.buildAuthorizationUrl(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        var result = mvc.perform(get("/api/integrations/mercadolivre/connect-url").cookie(cookie)
                .param("systemClientId", tenant.toString())).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("authorizationUrl").asText();
    }

    private Map<String, Object> listingMetadata() {
        return Map.of("category_id", "MLB123", "condition", "new", "pictures", List.of(Map.of("id", "picture-id")),
                "attributes", List.of(Map.of("id", "BRAND", "value_name", "Test")));
    }

    private MarketplaceIntegration integration() {
        MarketplaceIntegration integration = new MarketplaceIntegration();
        integration.setSystemClientId(tenant); integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setAccessToken(encryptor.encrypt("test-access-token"));
        integration.setRefreshToken(encryptor.encrypt("test-refresh-token"));
        integration.setExpiresAt(LocalDateTime.now().plusHours(1)); integration.setActive(true);
        integration.setResource(Map.of("user_id", tenant));
        return integrations.saveAndFlush(integration);
    }

    private User user(Role role, List<String> permissions) {
        return auth.register(new RegisterRequest(tenant, "Audit " + role, UUID.randomUUID() + "@example.invalid",
                "test-audit-password", Map.of(), role.name(), permissions));
    }

    private Cookie cookie(User user) { return new Cookie("ACCESS_TOKEN", jwt.generateAccessToken(user.getEmail())); }
    private String path() { return "/api/audit-logs/" + tenant; }
    private long count(String type) {
        return type == null ? jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE system_client_id=?", Long.class, tenant)
                : jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE system_client_id=? AND entity_type=?", Long.class, tenant, type);
    }
    private void seed(AuditAction action, AuditEntityType type, String id) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> audit.recordAs(AuditActor.from(admin),
                action, type, id, Map.of("stock", 20), Map.of("stock", 15), AuditSource.WEB, Map.of()));
    }
    private List<JsonNode> events(String type, String id) throws Exception {
        var response = mvc.perform(get(path()).cookie(cookie).param("entityType", type).param("limit", "100"))
                .andExpect(status().isOk()).andReturn();
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode event : json.readTree(response.getResponse().getContentAsString()).get("content")) {
            if (event.get("entity_id").asText().equals(id)) result.add(event);
        }
        Collections.reverse(result);
        return result;
    }
    private Map<String, Object> productPayload(boolean announce) {
        return new LinkedHashMap<>(Map.of("system_client_id", tenant, "sku", UUID.randomUUID().toString(),
                "name", "Product", "description", "Test product", "stock", 20, "reserved_stock", 0,
                "price", 49.90, "announcement", announce, "resource", Map.of("access_token", "resource-secret")));
    }
    private long createProduct(boolean announce) throws Exception {
        var response = mvc.perform(post("/api/products/" + tenant).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(productPayload(announce)))).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
}
