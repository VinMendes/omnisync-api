package com.puccampinas.omnisync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@AutoConfigureMockMvc
@Transactional
class OmniSyncApplicationTests {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private SystemClientRepository companies;
    @Autowired private EntityManager entityManager;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void contextLoads() {
    }

    @Test
    void shouldPersistTypedJsonbAndExposeDefaultsThroughMe() throws Exception {
        Long companyId = company();
        var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", companyId, "name", "Usuário de teste",
                                "email", "typed@example.com", "password", "test-password", "role", "MANAGER",
                                "resource", Map.of("cpf", "preserved")))))
                .andExpect(status().isOk()).andReturn().getResponse();
        entityManager.flush();
        entityManager.clear();

        User stored = users.findByEmail("typed@example.com").orElseThrow();
        assertThat(stored.getResource().role()).isEqualTo(Role.MANAGER);
        assertThat(stored.getResource().permissions()).isEqualTo(Role.MANAGER.defaultPermissions());
        assertThat(stored.getResource().attributes()).containsEntry("cpf", "preserved");
        assertThat(entityManager.createNativeQuery("SELECT resource ->> 'role' FROM users WHERE id = ?1", String.class)
                .setParameter(1, stored.getId()).getSingleResult()).isEqualTo("MANAGER");

        mvc.perform(get("/api/users/me").cookie(response.getCookie("ACCESS_TOKEN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "PRODUCT_WRITE", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE")))
                .andExpect(jsonPath("$.resource.role").value("manager"))
                .andExpect(jsonPath("$.resource.cpf").value("preserved"));
    }

    @Test
    void shouldAcceptLegacyEditorAndExposeCanonicalSellerWithoutChangingFrontend() throws Exception {
        var response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", company(), "name", "Editor legado",
                                "email", "legacy@example.com", "password", "test-password", "resource",
                                Map.of("role", "editor", "permissions", List.of("Anúncios", "Gestão de estoque"))))))
                .andExpect(status().isOk()).andReturn().getResponse();
        entityManager.flush();
        entityManager.clear();
        assertThat(users.findByEmail("legacy@example.com").orElseThrow().getResource().role()).isEqualTo(Role.SELLER);
        mvc.perform(get("/api/users/me").cookie(response.getCookie("ACCESS_TOKEN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.resource.role").value("editor"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("PRODUCT_READ", "PRODUCT_WRITE", "LISTING_PUBLISH")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"top-role", "nested-role", "top-permission", "nested-permission", "bad-permission-shape"})
    void shouldRejectUnknownOrMalformedAccessFieldsWith400BeforeSaving(String scenario) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("systemClientId", company(),
                "name", "Inválido", "email", "invalid@example.com", "password", "test-password"));
        String error = switch (scenario) {
            case "top-role" -> { payload.put("role", "superuser"); yield "Role desconhecida: superuser"; }
            case "nested-role" -> { payload.put("resource", Map.of("role", "superuser")); yield "Role desconhecida: superuser"; }
            case "top-permission" -> { payload.put("permissions", List.of("ALL_POWERS")); yield "Permissão desconhecida: ALL_POWERS"; }
            case "nested-permission" -> { payload.put("resource", Map.of("permissions", List.of("ALL_POWERS"))); yield "Permissão desconhecida: ALL_POWERS"; }
            default -> { payload.put("resource", Map.of("permissions", "PRODUCT_READ")); yield "lista de strings"; }
        };
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString(error)));
        assertThat(users.findByEmail("invalid@example.com")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"legacy\"", "{\"role\":\"superuser\",\"permissions\":[\"USER_MANAGE\"]}",
            "{\"role\":\"VIEWER\",\"permissions\":\"invalid\"}"})
    void shouldReadMalformedStoredJsonbWithoutReturning500(String rawJson) throws Exception {
        User user = user();
        entityManager.createNativeQuery("UPDATE users SET resource = CAST(?1 AS jsonb) WHERE id = ?2")
                .setParameter(1, rawJson).setParameter(2, user.getId()).executeUpdate();
        entityManager.clear();
        mvc.perform(get("/api/users/me").cookie(access(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    void shouldUpdateTypedPermissionsAndPreserveMetadata() throws Exception {
        User user = user();
        mvc.perform(put("/api/users/{id}", user.getId()).cookie(access(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("role", "MANAGER", "permissions", List.of("SALE_READ"),
                                "resource", Map.of("theme", "dark")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("SALE_READ")));
        entityManager.flush();
        entityManager.clear();
        UserResource stored = users.findById(user.getId()).orElseThrow().getResource();
        assertThat(stored.role()).isEqualTo(Role.MANAGER);
        assertThat(stored.permissions()).containsExactly(Permission.SALE_READ);
        assertThat(stored.attributes()).containsEntry("cpf", "preserved").containsEntry("theme", "dark");
    }

    private Long company() {
        SystemClient company = new SystemClient();
        company.setName("Empresa de teste");
        company.setDocument("test-only");
        company.setActive(true);
        return companies.saveAndFlush(company).getId();
    }

    private User user() {
        User user = new User();
        user.setSystemClientId(company());
        user.setName("Usuário de teste");
        user.setEmail("account@example.com");
        user.setPasswordHash(passwordEncoder.encode("test-password"));
        user.setResource(UserResource.create(Map.of("cpf", "preserved"), "VIEWER", null));
        return users.saveAndFlush(user);
    }

    private Cookie access(User user) {
        return new Cookie("ACCESS_TOKEN", jwtService.generateAccessToken(user.getEmail()));
    }
}
