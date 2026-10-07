package com.puccampinas.omnisync.core.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.audit.AuditService;
import com.puccampinas.omnisync.core.audit.AuditSource;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetToken;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetTokenRepository;
import com.puccampinas.omnisync.core.auth.security.CustomUserDetailsService;
import com.puccampinas.omnisync.core.auth.service.AuthService;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.dto.ChangePasswordRequest;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.core.users.service.UserPasswordService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** No surrounding test transaction: assertions observe real commits and rollbacks. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class PasswordManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired SystemClientRepository clients;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired CustomUserDetailsService details;
    @Autowired UserPasswordService passwordService;
    @MockitoSpyBean AuditService audit;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetEmailService resetEmail;
    private final ObjectMapper json = new ObjectMapper();
    private static final String OLD = "CurrentPassword123";
    private static final String NEW = "NewPassword456";
    private User manager;
    private User member;
    private Long tenant;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext();
        tenant = company();
        manager = user(tenant, "SELLER", List.of("USER_MANAGE")); // Permission, not ADMIN name.
        member = user(tenant, "VIEWER", List.of());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void ownPasswordRequiresNoManagementPermissionAndPreservesAccess() throws Exception {
        var pending = token(member, Instant.now().plusSeconds(600));
        String roleBefore = jdbc.queryForObject("select resource::text from roles where id=?", String.class, member.getTenantRole().getId());
        mvc.perform(put("/api/users/me/password").cookie(cookie(member)).contentType(MediaType.APPLICATION_JSON)
                        .content(own(OLD, NEW, NEW)))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertHash(member, NEW);
        assertThat(tokens.findById(pending.getId()).orElseThrow().isUsed()).isTrue();
        assertThat(jdbc.queryForObject("select resource::text from roles where id=?", String.class, member.getTenantRole().getId())).isEqualTo(roleBefore);
        assertThat(users.findById(member.getId()).orElseThrow().getActive()).isTrue();
        auditMatches(member, member, "PASSWORD_CHANGE", OLD, NEW, pending.getToken());
        login(member, NEW, 200); login(member, OLD, 401);
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(reset(pending.getToken(), "AnotherPassword789"))).andExpect(status().isBadRequest());
    }

    @Test void administratorChangesOnlyTheTargetWithinTheTenant() throws Exception {
        var pending = token(member, Instant.now().plusSeconds(600));
        mvc.perform(put("/api/users/" + member.getId() + "/password").cookie(cookie(manager))
                        .contentType(MediaType.APPLICATION_JSON).content(admin(NEW, NEW)))
                .andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertHash(member, NEW); assertHash(manager, OLD);
        assertThat(tokens.findById(pending.getId()).orElseThrow().isUsed()).isTrue();
        assertThat(users.findById(member.getId()).orElseThrow().getTenantRole().getPermissions()).isEmpty();
        auditMatches(manager, member, "ADMIN_PASSWORD_RESET", OLD, NEW, pending.getToken());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WrongSecret123", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void rejectsIncorrectCurrentPasswordWithoutMutation(String incorrect) throws Exception {
        var pending = token(member, Instant.now().plusSeconds(600));
        String result = mvc.perform(put("/api/users/me/password").cookie(cookie(member)).contentType(MediaType.APPLICATION_JSON)
                        .content(own(incorrect, NEW, NEW)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Senha atual incorreta."))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain(incorrect, NEW, member.getPasswordHash());
        assertHash(member, OLD);
        assertThat(tokens.findById(pending.getId()).orElseThrow().isUsed()).isFalse();
        assertThat(auditCount(member)).isZero();
    }

    @Test void requiresAuthenticationPermissionAndOwnPasswordEvenForAdmin() throws Exception {
        User adminWithoutPermission = user(tenant, "ADMIN", List.of());
        mvc.perform(put("/api/users/me/password").contentType(MediaType.APPLICATION_JSON).content(own(OLD, NEW, NEW)))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/" + member.getId() + "/password").contentType(MediaType.APPLICATION_JSON).content(admin(NEW, NEW)))
                .andExpect(status().isUnauthorized());
        for (User unauthorized : List.of(member, adminWithoutPermission)) {
            mvc.perform(put("/api/users/" + manager.getId() + "/password").cookie(cookie(unauthorized))
                    .contentType(MediaType.APPLICATION_JSON).content(admin(NEW, NEW)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Permissão insuficiente: USER_MANAGE"));
        }
        mvc.perform(put("/api/users/" + manager.getId() + "/password").cookie(cookie(manager))
                .contentType(MediaType.APPLICATION_JSON).content(admin(NEW, NEW))).andExpect(status().isBadRequest());
        assertHash(manager, OLD); assertThat(auditCount(manager)).isZero();
    }

    @Test void rejectsOtherTenantAndMissingUserWithoutRevealingDetails() throws Exception {
        User foreign = user(company(), "VIEWER", List.of());
        for (long id : List.of(foreign.getId(), Long.MAX_VALUE)) {
            mvc.perform(put("/api/users/" + id + "/password").cookie(cookie(manager))
                    .contentType(MediaType.APPLICATION_JSON).content(admin(NEW, NEW)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Usuário não encontrado."));
        }
        assertHash(foreign, OLD); assertThat(auditCount(foreign)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "      ", "mismatch", "long", "unicode"})
    void enforcesConfirmationAndPolicyInBothRoutes(String scenario) throws Exception {
        String password = switch (scenario) { case "long" -> "a".repeat(101); case "unicode" -> "á".repeat(37); default -> scenario; };
        String confirmation = scenario.equals("mismatch") ? "different" : password;
        mvc.perform(put("/api/users/me/password").cookie(cookie(member)).contentType(MediaType.APPLICATION_JSON)
                .content(own(OLD, password, confirmation))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(put("/api/users/" + member.getId() + "/password").cookie(cookie(manager))
                .contentType(MediaType.APPLICATION_JSON).content(admin(password, confirmation)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        assertHash(member, OLD); assertThat(auditCount(member)).isZero();
    }

    @Test void missingFieldsAndMalformedJsonNeverEchoCredentials() throws Exception {
        for (String body : List.of("{}", admin(NEW, NEW), "{\"current_password\":\"SECRET-SENTINEL\",", 
                "{\"current_password\":{},\"new_password\":\"SECRET-SENTINEL\"}")) {
            String response = mvc.perform(put("/api/users/me/password").cookie(cookie(member))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("SECRET-SENTINEL", NEW, "rejectedValue", "stackTrace");
        }
        assertThat(auditCount(member)).isZero();
    }

    @Test void recoveryIsSingleUseAndInvalidatesOtherPendingLinks() throws Exception {
        var link = token(member, Instant.now().plusSeconds(600));
        var other = token(member, Instant.now().plusSeconds(600));
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content(reset(link.getToken(), NEW)))
                .andExpect(status().isOk());
        assertHash(member, NEW);
        assertThat(tokens.findById(link.getId()).orElseThrow().isUsed()).isTrue();
        assertThat(tokens.findById(other.getId()).orElseThrow().isUsed()).isTrue();
        for (String used : List.of(link.getToken(), other.getToken())) {
            mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                    .content(reset(used, "CannotReuse789"))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Link de recuperação inválido ou expirado."));
        }
        assertThat(auditCount(member)).isEqualTo(1);
        auditMatches(member, member, "PASSWORD_RESET", NEW, link.getToken(), other.getToken());
    }

    @Test void expiredMissingAndInactiveRecoveryHaveSameSafeError() throws Exception {
        var expired = token(member, Instant.now().minusSeconds(1));
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(reset(expired.getToken(), NEW))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Link de recuperação inválido ou expirado."));
        assertThat(tokens.findById(expired.getId()).orElseThrow().isUsed()).isFalse();
        var active = token(member, Instant.now().plusSeconds(600));
        jdbc.update("update users set active=false where id=?", member.getId());
        for (String value : List.of(expired.getToken(), active.getToken(), "unknown-link")) {
            mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                    .content(reset(value, NEW))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Link de recuperação inválido ou expirado."));
        }
        assertHash(member, OLD); assertThat(auditCount(member)).isZero();
    }

    @Test void auditFailureRollsBackPasswordAndTokenInvalidation() {
        var link = token(member, Instant.now().plusSeconds(600));
        var principal = details.loadUserByUsername(member.getEmail());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        AuditService auditTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("forced audit failure"))
                .when(auditTarget).credentialChanged(any(User.class), eq(AuditSource.PASSWORD_CHANGE));
        try {
            assertThatThrownBy(() -> passwordService.changeOwn(new ChangePasswordRequest(OLD, NEW, NEW)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertHash(member, OLD);
            assertThat(tokens.findById(link.getId()).orElseThrow().isUsed()).isFalse();
            assertThat(auditCount(member)).isZero();
        } finally { org.mockito.Mockito.reset(auditTarget); }
    }

    @Test void simultaneousRecoveryRequestsHaveOnlyOneWinner() throws Exception {
        var link = token(member, Instant.now().plusSeconds(600));
        var statuses = concurrently(() -> mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(reset(link.getToken(), NEW))).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        assertHash(member, NEW); assertThat(auditCount(member)).isEqualTo(1);
    }

    @Test void recoveryAuditFailureDoesNotConsumeLinkOrChangePassword() {
        var link = token(member, Instant.now().plusSeconds(600));
        AuditService auditTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("forced audit failure"))
                .when(auditTarget).credentialChanged(any(User.class));
        try {
            assertThatThrownBy(() -> auth.resetPassword(
                    new com.puccampinas.omnisync.core.auth.dto.ResetPasswordRequest(link.getToken(), NEW)))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertHash(member, OLD);
            assertThat(tokens.findById(link.getId()).orElseThrow().isUsed()).isFalse();
            assertThat(auditCount(member)).isZero();
        } finally { org.mockito.Mockito.reset(auditTarget); }
    }

    @Test void newlyIssuedRecoveryLinkReplacesPreviousLinkAndRemainsUsable() throws Exception {
        var oldLink = token(member, Instant.now().plusSeconds(600));
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", member.getEmail()))))
                .andExpect(status().isOk());
        var link = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(resetEmail).sendPasswordResetEmail(eq(member.getEmail()), link.capture());
        String value = link.getValue().substring(link.getValue().indexOf("?token=") + 7);
        assertThat(tokens.findById(oldLink.getId())).isEmpty();
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(reset(value, NEW))).andExpect(status().isOk());
        assertHash(member, NEW);
    }

    @Test void simultaneousOwnChangesValidateCurrentPasswordUnderLock() throws Exception {
        var statuses = concurrently(() -> mvc.perform(put("/api/users/me/password").cookie(cookie(member))
                .contentType(MediaType.APPLICATION_JSON).content(own(OLD, NEW, NEW))).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(204, 400);
        assertHash(member, NEW); assertThat(auditCount(member)).isEqualTo(1);
    }

    private List<Integer> concurrently(Callable<Integer> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> { start.await(5, TimeUnit.SECONDS); return action.call(); };
            Future<Integer> first = executor.submit(task), second = executor.submit(task);
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    private void auditMatches(User actor, User target, String source, String... forbidden) {
        var row = jdbc.queryForMap("select user_id, entity_id, metadata::text as metadata, previous_data, new_data from audit_logs where system_client_id=? and entity_type='USER' and entity_id=? and action='UPDATE'",
                target.getSystemClientId(), target.getId().toString());
        assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(actor.getId());
        assertThat(row.get("entity_id")).isEqualTo(target.getId().toString());
        assertThat(row.get("previous_data")).isNull(); assertThat(row.get("new_data")).isNull();
        assertThat(row.get("metadata").toString()).contains(source).doesNotContain(forbidden).doesNotContain(target.getPasswordHash());
    }
    private long auditCount(User target) {
        return jdbc.queryForObject("select count(*) from audit_logs where system_client_id=? and entity_type='USER' and entity_id=? and action='UPDATE'",
                Long.class, target.getSystemClientId(), target.getId().toString());
    }
    private void assertHash(User user, String password) {
        String hash = users.findById(user.getId()).orElseThrow().getPasswordHash();
        assertThat(hash).isNotEqualTo(password).startsWith("$2");
        assertThat(encoder.matches(password, hash)).isTrue();
    }
    private void login(User user, String password, int expected) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", password))))
                .andExpect(status().is(expected));
    }
    private PasswordResetToken token(User user, Instant expiry) {
        var token = new PasswordResetToken(); token.setUser(user); token.setToken(UUID.randomUUID().toString()); token.setExpiresAt(expiry);
        return tokens.saveAndFlush(token);
    }
    private User user(Long tenantId, String role, List<String> permissions) {
        return auth.register(new RegisterRequest(tenantId, "Password test", UUID.randomUUID() + "@example.invalid", OLD, Map.of(), role, permissions));
    }
    private Long company() {
        var company = new SystemClient(); company.setName("Password test"); company.setDocument(UUID.randomUUID().toString().substring(0, 18));
        company.setActive(true); return clients.saveAndFlush(company).getId();
    }
    private Cookie cookie(User user) { return new Cookie("ACCESS_TOKEN", jwt.generateAccessToken(user.getEmail())); }
    private String own(String old, String next, String confirm) throws Exception {
        return json.writeValueAsString(Map.of("current_password", old, "new_password", next, "new_password_confirmation", confirm));
    }
    private String admin(String next, String confirm) throws Exception {
        return json.writeValueAsString(Map.of("new_password", next, "new_password_confirmation", confirm));
    }
    private String reset(String token, String next) throws Exception {
        return json.writeValueAsString(Map.of("token", token, "newPassword", next));
    }
}
