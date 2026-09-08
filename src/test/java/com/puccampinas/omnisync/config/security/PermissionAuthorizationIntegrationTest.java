package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.auth.jwt.JwtService;
import com.puccampinas.omnisync.core.product.dto.ProductDto;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import com.puccampinas.omnisync.core.users.dto.UserResponse;
import com.puccampinas.omnisync.core.users.entity.RoleResource;
import com.puccampinas.omnisync.core.users.entity.TenantRole;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.entity.UserResource;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import com.puccampinas.omnisync.core.users.repository.TenantRoleRepository;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.dto.MercadoLivreIntegrationResponse;
import com.puccampinas.omnisync.integration.service.MercadoLivreAuthService;
import com.puccampinas.omnisync.integration.service.MercadoLivreOrderWebhookService;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@Transactional
class PermissionAuthorizationIntegrationTest {

    private static final String VIEWER_EMAIL = "authorization-viewer@example.invalid";
    private static final String SELLER_EMAIL = "authorization-seller@example.invalid";
    private static final String SELLER_WITH_USER_MANAGE_EMAIL =
            "authorization-seller-user-manager@example.invalid";
    private static final String ADMIN_EMAIL = "authorization-admin@example.invalid";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private SystemClientRepository systemClientRepository;
    @Autowired
    private TenantRoleRepository tenantRoleRepository;
    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private SaleService saleService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private MercadoLivreAuthService mercadoLivreAuthService;
    @MockitoBean
    private MercadoLivreOrderWebhookService mercadoLivreOrderWebhookService;

    private Long systemClientId;
    private User viewer;
    private User seller;
    private User sellerWithUserManage;
    private User admin;
    private User managedUser;
    private ProductDto product;
    private UserResponse managedUserResponse;

    @BeforeEach
    void setUp() {
        SystemClient systemClient = new SystemClient();
        systemClient.setName("Empresa de autorização");
        systemClient.setDocument("authorization-test");
        systemClient.setActive(true);
        systemClientId = systemClientRepository.saveAndFlush(systemClient).getId();

        viewer = user(VIEWER_EMAIL, Role.VIEWER, Role.VIEWER.defaultPermissions());
        seller = user(SELLER_EMAIL, Role.SELLER, Role.SELLER.defaultPermissions());
        sellerWithUserManage = user(SELLER_WITH_USER_MANAGE_EMAIL, Role.SELLER,
                Set.of(Permission.PRODUCT_READ, Permission.USER_MANAGE));
        admin = user(ADMIN_EMAIL, Role.ADMIN, Role.ADMIN.defaultPermissions());
        managedUser = user("authorization-managed@example.invalid", Role.VIEWER,
                Role.VIEWER.defaultPermissions());

        product = new ProductDto();
        product.setId(1L);
        product.setSystemClientId(systemClientId);
        product.setSku("AUTH-1");
        product.setName("Produto autorizado");

        managedUserResponse = new UserResponse(
                managedUser.getId(), systemClientId, managedUser.getName(), managedUser.getEmail(),
                managedUser.getResource().toLegacyJson(Role.VIEWER, Role.VIEWER.defaultPermissions()),
                true, managedUser.getCreatedAt(), Role.VIEWER.name(),
                Permission.names(Role.VIEWER.defaultPermissions())
        );

        when(productService.getAll(systemClientId, 0, 20)).thenReturn(Page.empty());
        when(productService.create(eq(systemClientId), any(ProductDto.class))).thenReturn(product);
        when(productService.update(eq(systemClientId), eq(1L), any(ProductDto.class))).thenReturn(product);
        when(productService.announce(eq(systemClientId), eq(1L), any())).thenReturn(product);
        when(productService.delete(systemClientId, 1L)).thenReturn(product);
        when(saleService.getAll(systemClientId, 0, 20)).thenReturn(Page.empty());
        when(saleService.create(eq(systemClientId), anyList())).thenReturn(List.of());
        when(userService.update(eq(ADMIN_EMAIL), eq(managedUser.getId()), any())).thenReturn(managedUserResponse);
        when(userService.update(eq(SELLER_WITH_USER_MANAGE_EMAIL), eq(managedUser.getId()), any()))
                .thenReturn(managedUserResponse);
        when(userService.updateStatus(eq(ADMIN_EMAIL), eq(managedUser.getId()), any()))
                .thenReturn(managedUserResponse);
        when(mercadoLivreAuthService.generateAuthorizationUrl(systemClientId))
                .thenReturn("https://auth.example.invalid/authorize");
        when(mercadoLivreAuthService.exchangeCodeForAuthenticatedUser(ADMIN_EMAIL, "state", "code"))
                .thenReturn(new MercadoLivreIntegrationResponse(
                        "connected", systemClientId, Marketplace.MERCADO_LIVRE, true,
                        LocalDateTime.parse("2026-09-08T19:00:00"), Map.of()
                ));
        when(mercadoLivreOrderWebhookService.handleNotification(any()))
                .thenReturn(Map.of("processed", false));
    }

    @Test
    void viewerCanReadProductsButCannotCreateThem() throws Exception {
        mvc.perform(get("/api/products/{systemClientId}", systemClientId).cookie(access(viewer)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/products/{systemClientId}", systemClientId)
                        .cookie(access(viewer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Permissão insuficiente: PRODUCT_WRITE"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(content().string(not(containsString("AuthorizationDeniedException"))));

        verify(productService).getAll(systemClientId, 0, 20);
        verify(productService, never()).create(eq(systemClientId), any(ProductDto.class));
    }

    @Test
    void sellerWithoutUserManageCannotUpdateAUser() throws Exception {
        mvc.perform(put("/api/users/{id}", managedUser.getId())
                        .cookie(access(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nome bloqueado\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Permissão insuficiente: USER_MANAGE"));

        verify(userService, never()).update(eq(SELLER_EMAIL), eq(managedUser.getId()), any());
    }

    @Test
    void sellersWithTheSameRoleNameCanHaveDifferentRelationalPermissions() throws Exception {
        mvc.perform(put("/api/users/{id}", managedUser.getId())
                        .cookie(access(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sem permissão\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Permissão insuficiente: USER_MANAGE"));

        mvc.perform(put("/api/users/{id}", managedUser.getId())
                        .cookie(access(sellerWithUserManage))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Com permissão\"}"))
                .andExpect(status().isOk());

        verify(userService).update(eq(SELLER_WITH_USER_MANAGE_EMAIL),
                eq(managedUser.getId()), any());
    }

    @Test
    void adminRetainsAccessToEveryMappedCapability() throws Exception {
        Cookie access = access(admin);

        mvc.perform(get("/api/products/{systemClientId}", systemClientId).cookie(access))
                .andExpect(status().isOk());
        mvc.perform(post("/api/products/{systemClientId}", systemClientId).cookie(access)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/products/{systemClientId}/1", systemClientId).cookie(access)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/products/{systemClientId}/1", systemClientId).cookie(access))
                .andExpect(status().isOk());
        mvc.perform(post("/api/products/{systemClientId}/1/announce", systemClientId).cookie(access)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/sales/{systemClientId}", systemClientId).cookie(access))
                .andExpect(status().isOk());
        mvc.perform(post("/api/sales/{systemClientId}", systemClientId).cookie(access)
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/users/{id}", managedUser.getId()).cookie(access)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/users/{id}/status", managedUser.getId()).cookie(access)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/integrations/mercadolivre/connect-url")
                        .param("systemClientId", systemClientId.toString()).cookie(access))
                .andExpect(status().isOk());
        mvc.perform(post("/api/integrations/mercadolivre/exchange").cookie(access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"code\",\"state\":\"state\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void documentedPublicRoutesRemainPublic() throws Exception {
        mvc.perform(get("/api/auth/ping")).andExpect(status().isOk());
        mvc.perform(get("/api/integrations/mercadolivre/callback"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/integrations/mercadolivre/webhooks/orders")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private User user(String email, Role roleName, Set<Permission> permissions) {
        TenantRole tenantRole = new TenantRole();
        tenantRole.setSystemClientId(systemClientId);
        tenantRole.setName(roleName);
        tenantRole.setResource(RoleResource.of(permissions));
        tenantRole = tenantRoleRepository.saveAndFlush(tenantRole);

        User user = new User();
        user.setSystemClientId(systemClientId);
        user.setName(roleName + " de autorização");
        user.setEmail(email);
        user.setPasswordHash("unused-test-password-hash");
        user.setResource(UserResource.defaults());
        user.setTenantRole(tenantRole);
        user.setActive(true);
        return userRepository.saveAndFlush(user);
    }

    private Cookie access(User user) {
        return new Cookie("ACCESS_TOKEN", jwtService.generateAccessToken(user.getEmail()));
    }
}
