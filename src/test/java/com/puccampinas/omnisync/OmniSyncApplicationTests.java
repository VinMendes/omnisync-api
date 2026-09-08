package com.puccampinas.omnisync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
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
import java.util.Set;

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
    @Autowired private TenantRoleRepository tenantRoles;
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
        User admin = user(companyId, "admin@example.com", Role.ADMIN, Role.ADMIN.defaultPermissions());
        mvc.perform(post("/api/users").cookie(access(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", companyId, "name", "Usuário de teste",
                                "email", "typed@example.com", "password", "test-password", "role", "MANAGER",
                                "resource", Map.of("cpf", "preserved")))))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Set-Cookie"));
        entityManager.flush();
        entityManager.clear();

        User stored = users.findByEmail("typed@example.com").orElseThrow();
        assertThat(stored.getTenantRole().getName()).isEqualTo(Role.MANAGER);
        assertThat(stored.getTenantRole().getPermissions()).isEqualTo(Role.MANAGER.defaultPermissions());
        assertThat(stored.getResource().attributes()).containsEntry("cpf", "preserved");
        assertThat(entityManager.createNativeQuery("SELECT resource ->> 'role' FROM users WHERE id = ?1", String.class)
                .setParameter(1, stored.getId()).getSingleResult()).isNull();

        mvc.perform(get("/api/users/me").cookie(access(stored)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "PRODUCT_WRITE", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE")))
                .andExpect(jsonPath("$.resource.role").value("manager"))
                .andExpect(jsonPath("$.resource.cpf").value("preserved"));
    }

    @Test
    void shouldAcceptLegacyEditorAndExposeCanonicalSellerWithoutChangingFrontend() throws Exception {
        Long companyId = company();
        User admin = user(companyId, "admin@example.com", Role.ADMIN, Role.ADMIN.defaultPermissions());
        mvc.perform(post("/api/users").cookie(access(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", companyId, "name", "Editor legado",
                                "email", "legacy@example.com", "password", "test-password", "resource",
                                Map.of("role", "editor", "permissions", List.of("Anúncios", "Gestão de estoque"))))))
                .andExpect(status().isCreated()).andExpect(header().doesNotExist("Set-Cookie"));
        entityManager.flush();
        entityManager.clear();
        assertThat(users.findByEmail("legacy@example.com").orElseThrow().getTenantRole().getName()).isEqualTo(Role.SELLER);
        mvc.perform(get("/api/users/me").cookie(access(users.findByEmail("legacy@example.com").orElseThrow())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.resource.role").value("editor"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "PRODUCT_WRITE", "LISTING_PUBLISH")));
    }

    @Test
    void shouldExposeEveryLegacyCheckboxForAFullAccessAdmin() throws Exception {
        User admin = user(Role.ADMIN);

        mvc.perform(get("/api/users/me").cookie(access(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.permissions", containsInAnyOrder(
                        "Acesso total",
                        "Gestão de usuários",
                        "Faturamento",
                        "Gestão de estoque",
                        "Anúncios",
                        "Vendas",
                        "Marketplaces",
                        "Atividade",
                        "Somente leitura"
                )));
    }

    @ParameterizedTest
    @ValueSource(strings = {"top-role", "nested-role", "top-permission", "nested-permission", "bad-permission-shape"})
    void shouldRejectUnknownOrMalformedAccessFieldsWith400BeforeSaving(String scenario) throws Exception {
        Long companyId = company();
        User admin = user(companyId, "admin@example.com", Role.ADMIN, Role.ADMIN.defaultPermissions());
        Map<String, Object> payload = new java.util.LinkedHashMap<>(Map.of("systemClientId", companyId,
                "name", "Inválido", "email", "invalid@example.com", "password", "test-password"));
        String error = switch (scenario) {
            case "top-role" -> { payload.put("role", "superuser"); yield "Role desconhecida: superuser"; }
            case "nested-role" -> { payload.put("resource", Map.of("role", "superuser")); yield "Role desconhecida: superuser"; }
            case "top-permission" -> { payload.put("permissions", List.of("ALL_POWERS")); yield "Permissão desconhecida: ALL_POWERS"; }
            case "nested-permission" -> { payload.put("resource", Map.of("permissions", List.of("ALL_POWERS"))); yield "Permissão desconhecida: ALL_POWERS"; }
            default -> { payload.put("resource", Map.of("permissions", "PRODUCT_READ")); yield "lista de strings"; }
        };
        mvc.perform(post("/api/users").cookie(access(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
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
        User user = user(Role.ADMIN);
        mvc.perform(put("/api/users/{id}", user.getId()).cookie(access(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resource", Map.of(
                                "role", "manager", "permissions", List.of("Anúncios", "Vendas"),
                                "theme", "dark")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE")));
        entityManager.flush();
        entityManager.clear();
        User stored = users.findById(user.getId()).orElseThrow();
        assertThat(stored.getTenantRole().getName()).isEqualTo(Role.MANAGER);
        assertThat(stored.getTenantRole().getPermissions()).containsExactlyInAnyOrder(
                Permission.PRODUCT_READ, Permission.LISTING_PUBLISH,
                Permission.SALE_READ, Permission.SALE_WRITE);
        assertThat(stored.getResource().attributes())
                .containsEntry("cpf", "preserved").containsEntry("theme", "dark");
    }

    @Test
    void shouldApplyDifferentSellerPermissionsForUsersOfTheSameTenant() throws Exception {
        Long companyId = company();
        User admin = user(companyId, "admin@example.com", Role.ADMIN, Role.ADMIN.defaultPermissions());

        mvc.perform(post("/api/users").cookie(access(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", companyId, "name", "Vendedor A",
                                "email", "seller-a@example.com", "password", "test-password", "resource",
                                Map.of("role", "editor", "permissions", List.of("Anúncios", "Vendas"))))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/users").cookie(access(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("systemClientId", companyId, "name", "Vendedor B",
                                "email", "seller-b@example.com", "password", "test-password", "resource",
                                Map.of("role", "editor", "permissions",
                                        List.of("Anúncios", "Vendas", "Gestão de estoque"))))))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/users/me").cookie(access(users.findByEmail("seller-a@example.com").orElseThrow())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE")));
        mvc.perform(get("/api/users/me").cookie(access(users.findByEmail("seller-b@example.com").orElseThrow())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "PRODUCT_WRITE", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE")));

        User storedA = users.findByEmail("seller-a@example.com").orElseThrow();
        User storedB = users.findByEmail("seller-b@example.com").orElseThrow();
        assertThat(storedA.getSystemClientId()).isEqualTo(storedB.getSystemClientId());
        assertThat(storedA.getTenantRole().getName()).isEqualTo(Role.SELLER);
        assertThat(storedB.getTenantRole().getName()).isEqualTo(Role.SELLER);
        assertThat(storedA.getTenantRole().getId()).isNotEqualTo(storedB.getTenantRole().getId());
        assertThat(storedA.getTenantRole().getPermissions()).doesNotContain(Permission.PRODUCT_WRITE);
        assertThat(storedB.getTenantRole().getPermissions()).contains(Permission.PRODUCT_WRITE);
        assertThat(storedA.getResource().attributes()).doesNotContainKeys("role", "permissions");
        assertThat(storedB.getResource().attributes()).doesNotContainKeys("role", "permissions");
    }

    @Test
    void putShouldUpdateOnlyTheTargetUsersRole() throws Exception {
        Long companyId = company();
        User admin = user(companyId, "admin@example.com", Role.ADMIN, Role.ADMIN.defaultPermissions());
        User sellerA = user(companyId, "seller-a@example.com", Role.SELLER,
                Set.of(Permission.PRODUCT_READ));
        User sellerB = user(companyId, "seller-b@example.com", Role.SELLER,
                Set.of(Permission.SALE_READ));
        Long sellerARoleId = sellerA.getTenantRole().getId();
        Long sellerBRoleId = sellerB.getTenantRole().getId();

        mvc.perform(put("/api/users/{id}", sellerA.getId()).cookie(access(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("resource", Map.of(
                                "role", "editor", "permissions", List.of("Anúncios", "Vendas"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "LISTING_PUBLISH", "SALE_READ", "SALE_WRITE")));

        entityManager.flush();
        entityManager.clear();
        User storedA = users.findById(sellerA.getId()).orElseThrow();
        User storedB = users.findById(sellerB.getId()).orElseThrow();
        assertThat(storedA.getTenantRole().getId()).isEqualTo(sellerARoleId);
        assertThat(storedB.getTenantRole().getId()).isEqualTo(sellerBRoleId);
        assertThat(storedA.getTenantRole().getPermissions()).containsExactlyInAnyOrder(
                Permission.PRODUCT_READ, Permission.LISTING_PUBLISH,
                Permission.SALE_READ, Permission.SALE_WRITE);
        assertThat(storedB.getTenantRole().getPermissions()).containsExactly(Permission.SALE_READ);
        assertThat(tenantRoles.findAllBySystemClientIdOrderByName(companyId)).hasSize(3);
    }

    private Long company() {
        SystemClient company = new SystemClient();
        company.setName("Empresa de teste");
        company.setDocument("test-only");
        company.setActive(true);
        return companies.saveAndFlush(company).getId();
    }

    private User user() {
        return user(Role.VIEWER);
    }

    private User user(Role role) {
        Long companyId = company();
        return user(companyId, "account@example.com", role, role.defaultPermissions());
    }

    private User user(Long companyId, String email, Role role, Set<Permission> permissions) {
        User user = new User();
        user.setSystemClientId(companyId);
        user.setName("Usuário de teste");
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("test-password"));
        user.setResource(UserResource.create(Map.of("cpf", "preserved")));
        TenantRole tenantRole = new TenantRole();
        tenantRole.setSystemClientId(companyId);
        tenantRole.setName(role);
        tenantRole.setResource(RoleResource.of(permissions));
        user.setTenantRole(tenantRoles.saveAndFlush(tenantRole));
        return users.saveAndFlush(user);
    }

    private Cookie access(User user) {
        return new Cookie("ACCESS_TOKEN", jwtService.generateAccessToken(user.getEmail()));
    }
}
