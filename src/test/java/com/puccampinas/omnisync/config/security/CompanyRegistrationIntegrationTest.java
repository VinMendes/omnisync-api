package com.puccampinas.omnisync.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.auth.dto.RegisterRequest;
import com.puccampinas.omnisync.core.auth.service.AuthService;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sem transação envolvendo o teste: asserções observam commits e rollbacks reais da requisição. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class CompanyRegistrationIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private SystemClientRepository clients;
    @Autowired private AuthService authService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void publicSignupCreatesOnlyANewCompanyAndItsFirstAdmin() throws Exception {
        SystemClient existing = existingCompany("signup-foreign");
        Map<String, Object> request = signup("11222333000181", "new-company-admin@example.invalid");
        request.put("systemClientId", existing.getId());
        request.put("role", "viewer");
        request.put("resource", Map.of("cpf", "00000000000", "role", "viewer", "permissions", java.util.List.of()));

        MvcResult result = mvc.perform(post("/api/auth/register-company").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("ACCESS_TOKEN", true))
                .andExpect(cookie().httpOnly("REFRESH_TOKEN", true))
                .andReturn();
        User admin = users.findByEmail("new-company-admin@example.invalid").orElseThrow();
        assertThat(admin.getSystemClientId()).isNotEqualTo(existing.getId());
        assertThat(admin.getTenantRole().getName()).isEqualTo(Role.ADMIN);
        assertThat(admin.getTenantRole().getPermissions()).containsExactlyInAnyOrderElementsOf(Role.ADMIN.defaultPermissions());
        assertThat(admin.getResource().attributes()).containsOnlyKeys("cpf");
        assertThat(passwordEncoder.matches("test-company-password", admin.getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE system_client_id = ?",
                Long.class, existing.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE system_client_id = ?",
                Long.class, admin.getSystemClientId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?",
                Long.class, admin.getId())).isEqualTo(1L);
        Cookie access = result.getResponse().getCookie("ACCESS_TOKEN");
        mvc.perform(get("/api/users/me").cookie(access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(admin.getId()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void duplicateEmailRollsBackTheNewCompanyAndDoesNotIssueCookies() throws Exception {
        SystemClient existing = existingCompany("signup-duplicate");
        authService.register(new RegisterRequest(existing.getId(), "Existing user",
                "duplicate-signup@example.invalid", "test-company-password", null));
        long companyCount = clients.count();
        long roleCount = jdbc.queryForObject("SELECT count(*) FROM roles", Long.class);

        mvc.perform(post("/api/auth/register-company").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(signup("11444777000161", "duplicate-signup@example.invalid"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Já existe usuário com esse email"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(clients.count()).isEqualTo(companyCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Long.class)).isEqualTo(roleCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_client WHERE document = ?",
                Long.class, "11444777000161")).isZero();
    }

    @Test
    void invalidCompanyDocumentDoesNotCreateAnAccount() throws Exception {
        long companyCount = clients.count();
        mvc.perform(post("/api/auth/register-company").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(signup("11111111111111", "invalid-signup@example.invalid"))))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(clients.count()).isEqualTo(companyCount);
        assertThat(users.findByEmail("invalid-signup@example.invalid")).isEmpty();
    }

    private SystemClient existingCompany(String name) {
        SystemClient client = new SystemClient();
        client.setName(name);
        client.setDocument(name);
        client.setActive(true);
        return clients.saveAndFlush(client);
    }

    private Map<String, Object> signup(String document, String email) {
        return new LinkedHashMap<>(Map.of(
                "companyName", "Empresa de teste", "document", document,
                "name", "Administrador de teste", "email", email, "password", "test-company-password"
        ));
    }
}
