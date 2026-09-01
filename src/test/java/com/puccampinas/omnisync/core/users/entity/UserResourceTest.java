package com.puccampinas.omnisync.core.users.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.puccampinas.omnisync.core.users.enums.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserResourceTest {

    private final ObjectMapper jackson2 = new ObjectMapper();
    private final tools.jackson.databind.json.JsonMapper jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();

    @Test
    void shouldDefineTheInitialDefaultMatrix() {
        assertThat(Role.ROLE_DEFAULT_PERMISSIONS).containsOnlyKeys(Role.values());
        assertThat(Role.ADMIN.defaultPermissions()).containsExactlyInAnyOrder(Permission.values());
        assertThat(Role.MANAGER.defaultPermissions())
                .containsExactlyInAnyOrder(PRODUCT_READ, PRODUCT_WRITE, LISTING_PUBLISH, SALE_READ, SALE_WRITE);
        assertThat(Role.SELLER.defaultPermissions())
                .containsExactlyInAnyOrder(PRODUCT_READ, LISTING_PUBLISH, SALE_READ, SALE_WRITE);
        assertThat(Role.VIEWER.defaultPermissions()).containsExactlyInAnyOrder(PRODUCT_READ, SALE_READ);
        assertThatThrownBy(() -> Role.ROLE_DEFAULT_PERMISSIONS.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void shouldResolveRoleAndItsDefaultPermissionsSeparatelyFromUserMetadata(Role role) {
        UserAccess access = UserAccess.forCreate(Map.of("role", role.legacyName()), null, null);

        assertThat(access.role()).isEqualTo(role);
        assertThat(access.permissionsProvided()).isFalse();
        assertThat(access.effectivePermissions(null)).isEqualTo(role.defaultPermissions());
        assertThat(UserResource.create(Map.of("role", role.name(), "cpf", "test")).attributes())
                .containsExactlyEntriesOf(Map.of("cpf", "test"));
    }

    @Test
    void shouldAcceptLegacyLabelsButRejectUnknownOrConflictingAccessInput() {
        UserAccess access = UserAccess.forCreate(
                Map.of("role", "editor", "permissions", List.of("Anúncios", "Vendas")), null, null);
        assertThat(access.role()).isEqualTo(Role.SELLER);
        assertThat(access.permissions()).containsExactlyInAnyOrder(
                PRODUCT_READ, LISTING_PUBLISH, SALE_READ, SALE_WRITE);

        assertThatThrownBy(() -> UserAccess.forCreate(null, "superuser", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Role desconhecida: superuser");
        assertThatThrownBy(() -> UserAccess.forCreate(null, "VIEWER", List.of("ROOT_ACCESS")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Permissão desconhecida: ROOT_ACCESS");
        assertThatThrownBy(() -> UserAccess.forCreate(Map.of("permissions", "PRODUCT_READ"), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lista de strings");
        assertThatThrownBy(() -> UserAccess.forCreate(Map.of("role", "ADMIN"), "VIEWER", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mesmo papel");
        assertThatThrownBy(() -> UserAccess.forCreate(Map.of("permissions", List.of("SALE_READ")),
                "VIEWER", List.of("PRODUCT_READ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mesmas permissões");
    }

    @Test
    void shouldUseExplicitPermissionsOnlyForTheRequestedUser() {
        UserAccess requested = UserAccess.forCreate(null, "SELLER", List.of("SALE_READ"));
        TenantRole current = tenantRole(10L, Role.SELLER, Role.SELLER.defaultPermissions());

        assertThat(requested.effectivePermissions(current)).containsExactly(SALE_READ);
        assertThat(current.getPermissions()).isEqualTo(Role.SELLER.defaultPermissions());
    }

    @Test
    void shouldPreservePermissionsWhenOmittedAndRoleIsUnchanged() {
        TenantRole current = tenantRole(10L, Role.SELLER, Set.of(SALE_READ));
        UserAccess unchanged = UserAccess.forUpdate(null, null, null, Role.SELLER);
        UserAccess changedRole = UserAccess.forUpdate(null, "MANAGER", null, Role.SELLER);

        assertThat(unchanged.effectivePermissions(current)).containsExactly(SALE_READ);
        assertThat(changedRole.effectivePermissions(current)).isEqualTo(Role.MANAGER.defaultPermissions());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"legacy\"", "17",
            "{\"role\":\"superuser\",\"permissions\":[\"USER_MANAGE\"],\"cpf\":\"test\"}"})
    void shouldReadMalformedLegacyUserResourceWithoutKeepingAccessFields(String stored) throws Exception {
        UserResource[] parsedResources = {
                jackson2.readValue(stored, UserResource.class),
                jackson3.readValue(stored, UserResource.class)
        };
        for (UserResource parsed : parsedResources) {
            User user = new User();
            user.setResource(parsed);
            assertThat(user.getResource().attributes()).doesNotContainKeys("role", "permissions");
        }
    }

    @Test
    void shouldPersistOnlyUserCharacteristicsAndBuildLegacyApiProjection() throws Exception {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("cpf", "test");
        metadata.put("optional", null);
        metadata.put("role", "admin");
        metadata.put("permissions", List.of("Acesso total"));
        UserResource resource = new UserResource(metadata);

        String persisted = jackson2.writeValueAsString(resource);
        assertThat(persisted).contains("\"cpf\":\"test\"")
                .doesNotContain("role", "permissions", "attributes");
        assertThat(jackson2.readValue(persisted, UserResource.class)).isEqualTo(resource);
        assertThat(jackson3.readValue(jackson3.writeValueAsString(resource), UserResource.class)).isEqualTo(resource);

        assertThat(resource.toLegacyJson(Role.SELLER, Role.SELLER.defaultPermissions()))
                .containsEntry("cpf", "test")
                .containsEntry("role", "editor");
    }

    @Test
    void shouldDeserializeRoleResourceFailClosed() throws Exception {
        RoleResource valid = jackson2.readValue(
                "{\"permissions\":[\"PRODUCT_READ\",\"USER_MANAGE\"],\"label\":\"custom\"}",
                RoleResource.class);
        RoleResource malformed = jackson2.readValue(
                "{\"permissions\":[\"PRODUCT_READ\",\"ALL_POWERS\",42]}", RoleResource.class);

        assertThat(valid.permissions()).containsExactlyInAnyOrder(PRODUCT_READ, USER_MANAGE);
        assertThat(valid.attributes()).containsEntry("label", "custom");
        assertThat(malformed.permissions()).containsExactly(PRODUCT_READ);
    }

    @Test
    void shouldRoundTripEveryPermissionSubsetThroughLegacyFrontendLabels() {
        Permission[] all = Permission.values();
        for (int mask = 0; mask < (1 << all.length); mask++) {
            Set<Permission> expected = EnumSet.noneOf(Permission.class);
            for (int index = 0; index < all.length; index++) {
                if ((mask & (1 << index)) != 0) {
                    expected.add(all[index]);
                }
            }
            assertThat(Permission.parseSet(Permission.legacyNames(expected), true))
                    .as("permission mask %s", mask)
                    .isEqualTo(expected);
        }
    }

    @Test
    void shouldExpandFullAccessIntoEveryLegacyFrontendCheckbox() {
        assertThat(Permission.legacyNames(Role.ADMIN.defaultPermissions())).containsExactly(
                "Acesso total",
                "Gestão de usuários",
                "Faturamento",
                "Gestão de estoque",
                "Anúncios",
                "Vendas",
                "Marketplaces",
                "Atividade",
                "Somente leitura"
        );
    }

    private TenantRole tenantRole(Long systemClientId, Role role, Set<Permission> permissions) {
        TenantRole tenantRole = new TenantRole();
        tenantRole.setSystemClientId(systemClientId);
        tenantRole.setName(role);
        tenantRole.setResource(RoleResource.of(permissions));
        return tenantRole;
    }
}
