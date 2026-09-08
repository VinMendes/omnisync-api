package com.puccampinas.omnisync.config.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/** Login, HTTP, serviços, JSONB e relacionamentos reais; somente a chamada ao marketplace é simulada. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@Transactional
class RelationalPermissionFlowIntegrationTest {

    private static final String PASSWORD = "isolated-permission-test-password";
    private final ObjectMapper json = new ObjectMapper();

    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SystemClientRepository clients;
    @Autowired private TenantRoleRepository roles;
    @Autowired private UserRepository users;
    @Autowired private ProductRepository products;
    @Autowired private MarketplaceIntegrationRepository integrations;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private MercadoLivreListingService listings;

    private SystemClient company;
    private User admin;
    private User sellerA;
    private User sellerB;
    private Product product;
    private String passwordHash;

    @BeforeEach
    void setUp() {
        passwordHash = passwordEncoder.encode(PASSWORD);
        company = company("permission-flow");
        admin = user(company, "admin", Role.ADMIN, Role.ADMIN.defaultPermissions());
        sellerA = user(company, "seller-a", Role.SELLER,
                Set.of(Permission.PRODUCT_READ, Permission.SALE_READ));
        sellerB = user(company, "seller-b", Role.SELLER,
                Set.of(Permission.PRODUCT_READ, Permission.SALE_READ));
        product = product(company, "INITIAL");
        flushAndClear();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void defaultRolesEnforceReadAndWriteAgainstRealServices(Role role) throws Exception {
        User account = user(company, "default-" + role, role, role.defaultPermissions());
        flushAndClear();
        Cookie cookie = login(account);

        mvc.perform(get("/api/products/{client}", company.getId()).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("INITIAL"));
        mvc.perform(get("/api/sales/{client}", company.getId()).cookie(cookie))
                .andExpect(status().isOk());

        // Expectations are explicit: the test must not derive them from the production permission map.
        boolean mayCreateProduct = role == Role.ADMIN || role == Role.MANAGER;
        ResultActions creation = createProduct(cookie, "BY-" + role, false);
        if (mayCreateProduct) {
            creation.andExpect(status().isOk()).andExpect(jsonPath("$.sku").value("BY-" + role));
        } else {
            forbidden(creation, "PRODUCT_WRITE");
        }
        flushAndClear();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE sku = ?", Long.class,
                "BY-" + role)).isEqualTo(mayCreateProduct ? 1L : 0L);

        ResultActions sale = mvc.perform(post("/api/sales/{client}", company.getId())
                .cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(List.of(Map.of(
                        "productId", product.getId(), "quantity", 2, "totalValue", 20,
                        "channel", "MANUAL")))));
        if (role == Role.VIEWER) {
            forbidden(sale, "SALE_WRITE");
        } else {
            sale.andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(2));
        }
        flushAndClear();
        assertThat(products.findById(product.getId()).orElseThrow().getStock())
                .isEqualTo(role == Role.VIEWER ? 10 : 8);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            GET, /api/products/{client}, PRODUCT_READ
            GET, /api/products/{client}/{product}, PRODUCT_READ
            GET, /api/products/{client}/sku/INITIAL, PRODUCT_READ
            POST, /api/products/{client}, PRODUCT_WRITE
            PUT, /api/products/{client}/{product}, PRODUCT_WRITE
            DELETE, /api/products/{client}/{product}, PRODUCT_WRITE
            POST, /api/products/{client}/{product}/announce, LISTING_PUBLISH
            GET, /api/sales/{client}, SALE_READ
            GET, /api/sales/{client}/1, SALE_READ
            POST, /api/sales/{client}, SALE_WRITE
            PUT, /api/users/{user}, USER_MANAGE
            PATCH, /api/users/{user}/status, USER_MANAGE
            GET, /api/integrations/mercadolivre/connect-url, INTEGRATION_MANAGE
            POST, /api/integrations/mercadolivre/exchange, INTEGRATION_MANAGE
            """)
    void everyMappedEndpointRejectsAnAdminWithoutTheRequiredPermission(
            String method, String path, String permission) throws Exception {
        User restricted = user(company, "no-permissions", Role.ADMIN, Set.of());
        flushAndClear();
        String endpoint = path.replace("{client}", company.getId().toString())
                .replace("{product}", product.getId().toString())
                .replace("{user}", sellerA.getId().toString());
        String body = path.startsWith("/api/sales/") ? "[]"
                : path.endsWith("/exchange") ? "{\"code\":\"test\",\"state\":\"test\"}" : "{}";

        forbidden(mvc.perform(request(HttpMethod.valueOf(method), endpoint)
                .cookie(login(restricted)).param("systemClientId", company.getId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)), permission);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permissionEditsPersistPerUserAndApplyImmediatelyToTheSameCookie(boolean nestedLegacyPayload)
            throws Exception {
        Cookie adminCookie = login(admin);
        Cookie sellerCookie = login(sellerA);
        Cookie otherSellerCookie = login(sellerB);
        Long sellerRoleId = linkedRoleId(sellerA);
        Long otherRoleId = linkedRoleId(sellerB);
        assertThat(sellerRoleId).isNotEqualTo(otherRoleId);

        forbidden(createProduct(sellerCookie, "BEFORE", false), "PRODUCT_WRITE");
        Map<String, Object> access = Map.of("role", "editor", "permissions",
                List.of("Gestão de estoque", "Vendas"));
        Object payload = nestedLegacyPayload ? Map.of("resource", access) : access;

        mvc.perform(put("/api/users/{id}", sellerA.getId()).cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        "PRODUCT_READ", "PRODUCT_WRITE", "SALE_READ", "SALE_WRITE")));
        flushAndClear();

        assertThat(linkedRoleId(sellerA)).isEqualTo(sellerRoleId);
        assertThat(linkedRoleId(sellerB)).isEqualTo(otherRoleId);
        assertThat(roles.findById(sellerRoleId).orElseThrow().getPermissions()).containsExactlyInAnyOrder(
                Permission.PRODUCT_READ, Permission.PRODUCT_WRITE, Permission.SALE_READ, Permission.SALE_WRITE);
        assertThat(roles.findById(otherRoleId).orElseThrow().getPermissions()).containsExactlyInAnyOrder(
                Permission.PRODUCT_READ, Permission.SALE_READ);
        JsonNode userResource = json.readTree(jdbc.queryForObject(
                "SELECT resource::text FROM users WHERE id = ?", String.class, sellerA.getId()));
        assertThat(userResource.has("role")).isFalse();
        assertThat(userResource.has("permissions")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE system_client_id = ?",
                Long.class, company.getId())).isEqualTo(3L);

        mvc.perform(get("/api/users/me").cookie(sellerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource.role").value("editor"))
                .andExpect(jsonPath("$.resource.permissions", containsInAnyOrder("Gestão de estoque", "Vendas")));
        createProduct(sellerCookie, "AFTER-GRANT", false).andExpect(status().isOk());
        forbidden(createProduct(otherSellerCookie, "OTHER-SELLER", false), "PRODUCT_WRITE");

        mvc.perform(put("/api/users/{id}", sellerA.getId()).cookie(adminCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"editor\",\"permissions\":[]}"))
                .andExpect(status().isOk());
        flushAndClear();
        assertThat(roles.findById(sellerRoleId).orElseThrow().getPermissions()).isEmpty();
        mvc.perform(get("/api/users/me").cookie(sellerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions").isEmpty())
                .andExpect(jsonPath("$.resource.permissions").isEmpty());
        forbidden(createProduct(sellerCookie, "AFTER-REVOKE", false), "PRODUCT_WRITE");
        forbidden(mvc.perform(get("/api/products/{client}", company.getId()).cookie(sellerCookie)),
                "PRODUCT_READ");
        mvc.perform(get("/api/products/{client}", company.getId()).cookie(otherSellerCookie))
                .andExpect(status().isOk());
    }

    @Test
    void adminNameAloneDoesNotOverrideExplicitlyRestrictedPermissions() throws Exception {
        TenantRole role = roles.findById(linkedRoleId(admin)).orElseThrow();
        role.setResource(RoleResource.of(Set.of(Permission.PRODUCT_READ)));
        roles.save(role);
        flushAndClear();
        Cookie cookie = login(admin);

        mvc.perform(get("/api/products/{client}", company.getId()).cookie(cookie))
                .andExpect(status().isOk());
        forbidden(createProduct(cookie, "RESTRICTED-ADMIN", false), "PRODUCT_WRITE");
    }

    @Test
    void userManageIsRequiredAndStillRespectsUserTenantIsolation() throws Exception {
        Cookie sellerCookie = login(sellerA);
        forbidden(mvc.perform(patch("/api/users/{id}/status", sellerB.getId()).cookie(sellerCookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")), "USER_MANAGE");
        assertThat(users.findById(sellerB.getId()).orElseThrow().getActive()).isTrue();

        User foreignUser = user(company("foreign-users"), "foreign", Role.VIEWER,
                Role.VIEWER.defaultPermissions());
        flushAndClear();
        mvc.perform(put("/api/users/{id}", foreignUser.getId()).cookie(login(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}"))
                .andExpect(status().isNotFound());
        assertThat(users.findById(foreignUser.getId()).orElseThrow().getName()).isEqualTo("foreign");
    }

    @Test
    void deactivatingAUserImmediatelyInvalidatesAccessWithTheSameCookie() throws Exception {
        Cookie cookie = login(sellerA);
        mvc.perform(get("/api/products/{client}", company.getId()).cookie(cookie))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/users/{id}/status", sellerA.getId()).cookie(login(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        flushAndClear();
        mvc.perform(get("/api/products/{client}", company.getId()).cookie(cookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creatingMembersPreservesTheAdminSessionAndPersistsTheirIndividualPermissions() throws Exception {
        Cookie cookie = login(admin);
        MvcResult result = mvc.perform(post("/api/users").cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(memberPayload(company.getId(), "created-member@example.invalid")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("SALE_READ", "SALE_WRITE")))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andReturn();
        flushAndClear();
        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        User member = users.findById(id).orElseThrow();
        assertThat(member.getTenantRole().getPermissions())
                .containsExactlyInAnyOrder(Permission.SALE_READ, Permission.SALE_WRITE);
        assertThat(linkedRoleId(member)).isNotIn(linkedRoleId(sellerA), linkedRoleId(sellerB), linkedRoleId(admin));
        mvc.perform(get("/api/users/me").cookie(cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(admin.getId()));
        mvc.perform(get("/api/users/me").cookie(login(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void memberCreationRejectsAnonymousUsersMissingPermissionsAndForeignTenants() throws Exception {
        String email = "rejected-member@example.invalid";
        String payload = memberPayload(company.getId(), email);
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isUnauthorized());
        forbidden(mvc.perform(post("/api/users").cookie(login(sellerA))
                .contentType(MediaType.APPLICATION_JSON).content(payload)), "USER_MANAGE");
        SystemClient foreign = company("foreign-member");
        mvc.perform(post("/api/users").cookie(login(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(memberPayload(foreign.getId(), email)))
                .andExpect(status().isNotFound());
        assertThat(users.findByEmail(email)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE system_client_id = ?",
                Long.class, foreign.getId())).isZero();
    }

    @Test
    void memberCreationRejectsUnknownPermissionsBeforeWritingRoles() throws Exception {
        String payload = memberPayload(company.getId(), "invalid-member@example.invalid")
                .replace("Vendas", "ALL_POWERS");
        mvc.perform(post("/api/users").cookie(login(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Permissão desconhecida: ALL_POWERS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE system_client_id = ?",
                Long.class, company.getId())).isEqualTo(3L);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            GET, /api/products/{client}, {}
            GET, /api/products/{client}/{product}, {}
            GET, /api/products/{client}/sku/PRIVATE-FOREIGN-PRODUCT, {}
            POST, /api/products/{client}, {}
            PUT, /api/products/{client}/{product}, {}
            DELETE, /api/products/{client}/{product}, {}
            POST, /api/products/{client}/{product}/announce, {}
            GET, /api/sales/{client}, {}
            POST, /api/sales/{client}, []
            GET, /api/client/{client}, {}
            PUT, /api/client/{client}, {}
            DELETE, /api/client/{client}, {}
            PUT, /api/client/updateMarketplaces/{client}, {}
            GET, /api/integrations/mercadolivre/connect-url, {}
            GET, /api/integrations/mercadolivre/catalog/categories, {}
            GET, /api/integrations/mercadolivre/catalog/categories/suggestions, {}
            GET, /api/integrations/mercadolivre/catalog/categories/MLB1/attributes, {}
            GET, /api/integrations/mercadolivre/catalog/categories/MLB1/requirements, {}
            POST, /api/integrations/mercadolivre/catalog/{client}/sync, {}
            """)
    void adminCannotAccessOtherTenantsThroughPathsOrQueryParameters(String method, String path, String body)
            throws Exception {
        SystemClient foreign = company("foreign-path-target");
        Product foreignProduct = product(foreign, "PRIVATE-FOREIGN-PRODUCT");
        flushAndClear();
        String endpoint = path.replace("{client}", foreign.getId().toString())
                .replace("{product}", foreignProduct.getId().toString());
        mvc.perform(request(HttpMethod.valueOf(method), endpoint).cookie(login(admin))
                        .param("systemClientId", foreign.getId().toString()).param("q", "test")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        assertThat(clients.findById(foreign.getId()).orElseThrow().getActive()).isTrue();
        assertThat(products.findById(foreignProduct.getId()).orElseThrow().getActive()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(listings);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            GET, /api/client/{client}
            PUT, /api/client/{client}
            DELETE, /api/client/{client}
            PUT, /api/client/updateMarketplaces/{client}
            POST, /api/client
            """)
    void companyRoutesRequireAuthentication(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path.replace("{client}", company.getId().toString()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void companyWritesRequireTheirRespectivePermissions() throws Exception {
        Cookie cookie = login(sellerA);
        forbidden(mvc.perform(put("/api/client/{client}", company.getId()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Changed\"}")), "SETTINGS_MANAGE");
        forbidden(mvc.perform(request(HttpMethod.DELETE, "/api/client/{client}", company.getId()).cookie(cookie)),
                "SETTINGS_MANAGE");
        forbidden(mvc.perform(put("/api/client/updateMarketplaces/{client}", company.getId()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content("{\"mercado_livre\":false}")), "INTEGRATION_MANAGE");
        mvc.perform(get("/api/client/checkCNPJ/11222333000181")).andExpect(status().isOk());
    }

    @Test
    void aWriterWithPublishPermissionCanStillCreateAnAnnouncement() throws Exception {
        User writer = user(company, "authorized-publisher", Role.SELLER,
                Set.of(Permission.PRODUCT_WRITE, Permission.LISTING_PUBLISH));
        createProduct(login(writer), "AUTHORIZED-ANNOUNCEMENT", true).andExpect(status().isOk());
        verify(listings).createListing(eq(company.getId()), any(Product.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INTEGRATION_MANAGE", "PRODUCT_WRITE"})
    void catalogSyncRequiresBothPermissions(Permission onlyPermission) throws Exception {
        User account = user(company, "partial-sync", Role.SELLER, Set.of(onlyPermission));
        String missing = onlyPermission == Permission.INTEGRATION_MANAGE ? "PRODUCT_WRITE" : "INTEGRATION_MANAGE";
        forbidden(mvc.perform(post("/api/integrations/mercadolivre/catalog/{client}/sync", company.getId())
                .cookie(login(account))), missing);
        org.mockito.Mockito.verifyNoInteractions(listings);
    }

    @Test
    void productWriteAloneMustNotPublishThroughTheCreatePayload() throws Exception {
        User writer = user(company, "writer", Role.SELLER, Set.of(Permission.PRODUCT_WRITE));
        flushAndClear();
        Cookie cookie = login(writer);
        forbidden(mvc.perform(post("/api/products/{client}/{id}/announce", company.getId(), product.getId())
                .cookie(cookie).contentType(MediaType.APPLICATION_JSON).content("{}")), "LISTING_PUBLISH");

        MvcResult result = createProduct(cookie, "INLINE-ANNOUNCEMENT", true).andReturn();
        assertAll(
                () -> status().isForbidden().match(result),
                () -> verify(listings, never()).createListing(any(), any())
        );
    }

    @Test
    void productReadMustNotExposeAnotherCompanyByChangingThePath() throws Exception {
        SystemClient otherCompany = company("foreign-products");
        product(otherCompany, "PRIVATE-FOREIGN-PRODUCT");
        flushAndClear();

        MvcResult result = mvc.perform(get("/api/products/{client}", otherCompany.getId())
                .cookie(login(sellerA))).andReturn();
        assertAll(
                () -> status().isNotFound().match(result),
                () -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("PRIVATE-FOREIGN-PRODUCT")
        );
    }

    @Test
    void anonymousRegistrationMustNotGrantAdministrativeAccessToAnExistingCompany() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "systemClientId", company.getId(), "name", "Uninvited admin",
                                "email", "uninvited@example.invalid", "password", PASSWORD,
                                "role", "admin", "permissions", List.of("Acesso total")))))
                .andReturn();
        flushAndClear();
        assertAll(
                () -> status().isForbidden().match(result),
                () -> assertThat(users.findByEmail("uninvited@example.invalid")
                        .map(account -> account.getTenantRole().getPermissions())
                        .orElse(Set.of())).doesNotContain(Permission.USER_MANAGE, Permission.PRODUCT_WRITE)
        );
    }

    @Test
    void viewerMustNotMutateTheCatalogThroughMarketplaceSync() throws Exception {
        User viewer = user(company, "viewer-sync", Role.VIEWER, Role.VIEWER.defaultPermissions());
        MarketplaceIntegration integration = new MarketplaceIntegration();
        integration.setSystemClientId(company.getId());
        integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setAccessToken("test-only-unused-token");
        integration.setExpiresAt(LocalDateTime.now().plusHours(1));
        integration.setResource(Map.of("user_id", "isolated-test-seller"));
        integrations.saveAndFlush(integration);
        when(listings.listAllClientListings(company.getId())).thenReturn(Map.of("items", List.of()));
        flushAndClear();

        MvcResult result = mvc.perform(post("/api/integrations/mercadolivre/catalog/{client}/sync", company.getId())
                        .cookie(login(viewer)))
                .andReturn();
        assertAll(
                () -> status().isForbidden().match(result),
                () -> verify(listings, never()).listAllClientListings(eq(company.getId()))
        );
        mvc.perform(post("/api/integrations/mercadolivre/catalog/{client}/sync", company.getId())
                        .cookie(login(admin)))
                .andExpect(status().isOk());
        verify(listings).listAllClientListings(company.getId());
    }

    private SystemClient company(String name) {
        SystemClient client = new SystemClient();
        client.setName(name);
        client.setDocument(name);
        client.setActive(true);
        client.setResource(Map.of("mercado_livre", true));
        return clients.saveAndFlush(client);
    }

    private String memberPayload(Long clientId, String email) throws Exception {
        return json.writeValueAsString(Map.of(
                "systemClientId", clientId, "name", "New member", "email", email, "password", PASSWORD,
                "resource", Map.of("role", "editor", "permissions", List.of("Vendas"))
        ));
    }

    private User user(SystemClient client, String name, Role roleName, Set<Permission> permissions) {
        TenantRole role = new TenantRole();
        role.setSystemClientId(client.getId());
        role.setName(roleName);
        role.setResource(RoleResource.of(permissions));
        roles.saveAndFlush(role);
        User user = new User();
        user.setSystemClientId(client.getId());
        user.setName(name);
        user.setEmail(name.toLowerCase() + "@example.invalid");
        user.setPasswordHash(passwordHash);
        user.setResource(UserResource.defaults());
        user.setTenantRole(role);
        return users.saveAndFlush(user);
    }

    private Product product(SystemClient client, String sku) {
        Product product = new Product();
        product.setSystemClientId(client.getId());
        product.setName(sku);
        product.setDescription("Isolated permission test");
        product.setSku(sku);
        product.setPrice(new BigDecimal("10.00"));
        product.setStock(10);
        product.setActive(true);
        return products.saveAndFlush(product);
    }

    private Cookie login(User user) throws Exception {
        Cookie cookie = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("ACCESS_TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        return cookie;
    }

    private ResultActions createProduct(Cookie cookie, String sku, boolean announcement) throws Exception {
        return mvc.perform(post("/api/products/{client}", company.getId()).cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "system_client_id", company.getId(), "sku", sku, "name", sku,
                        "description", "Isolated permission test", "price", 10,
                        "stock", 10, "reserved_stock", 0, "announcement", announcement))));
    }

    private void forbidden(ResultActions response, String permission) throws Exception {
        response.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Permissão insuficiente: " + permission))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    private Long linkedRoleId(User user) {
        return jdbc.queryForObject("SELECT role_id FROM user_roles WHERE user_id = ?", Long.class, user.getId());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
