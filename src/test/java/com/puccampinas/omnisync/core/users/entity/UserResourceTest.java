package com.puccampinas.omnisync.core.users.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.HashMap;
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
        assertThatThrownBy(() -> Role.ROLE_DEFAULT_PERMISSIONS.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Role.VIEWER.defaultPermissions().add(USER_MANAGE)).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void shouldApplyDefaultsWhenCreatingWithoutPermissions(Role role) {
        assertThat(UserResource.create(null, role.name(), null).permissions()).isEqualTo(role.defaultPermissions());
        assertThat(UserResource.create(Map.of("role", role.name().toLowerCase()), null, null).permissions())
                .isEqualTo(role.defaultPermissions());
    }

    @Test
    void shouldDefaultNewAccountsToViewerAndPreserveExplicitEmptyPermissions() {
        assertThat(UserResource.create(null, null, null).role()).isEqualTo(Role.VIEWER);
        assertThat(UserResource.create(null, "ADMIN", List.of()).permissions()).isEmpty();
    }

    @Test
    void shouldRejectUnknownRolesAndPermissionsInNewInput() {
        assertThatThrownBy(() -> UserResource.create(null, "superuser", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Role desconhecida: superuser");
        assertThatThrownBy(() -> UserResource.create(Map.of("role", "superuser"), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Role desconhecida: superuser");
        assertThatThrownBy(() -> UserResource.create(null, "VIEWER", List.of("ROOT_ACCESS")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Permissão desconhecida: ROOT_ACCESS");
        assertThatThrownBy(() -> UserResource.create(Map.of("permissions", "PRODUCT_READ"), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lista de strings");
        assertThatThrownBy(() -> UserResource.create(Map.of("permissions", List.of(7)), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("string válida");
    }

    @Test
    void shouldRejectConflictingTopLevelAndNestedFields() {
        assertThatThrownBy(() -> UserResource.create(Map.of("role", "admin"), "VIEWER", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mesmo papel");
        assertThatThrownBy(() -> UserResource.create(Map.of("permissions", List.of("SALE_READ")),
                "VIEWER", List.of("PRODUCT_READ"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mesmas permissões");
    }

    @Test
    void shouldNormalizeLegacyLabelsIntoTypedPermissions() {
        UserResource resource = UserResource.create(Map.of("role", "manager",
                "permissions", List.of("Gestão de estoque", "Anúncios", "Vendas"), "cpf", "test"), null, null);
        assertThat(resource.role()).isEqualTo(Role.MANAGER);
        assertThat(resource.permissions()).isEqualTo(Role.MANAGER.defaultPermissions());
        assertThat(resource.attributes()).containsExactlyEntriesOf(Map.of("cpf", "test"));
        assertThat(resource.toStoredJson()).containsEntry("role", "MANAGER");
        assertThat(resource.permissionNames()).doesNotContain("Anúncios", "Vendas");
    }

    @Test
    void shouldAcceptEditorOnlyAsALegacyAliasForCanonicalSeller() {
        UserResource resource = UserResource.create(Map.of("role", "editor"), null, null);
        assertThat(resource.role()).isEqualTo(Role.SELLER);
        assertThat(resource.toStoredJson()).containsEntry("role", "SELLER");
        assertThat(resource.toLegacyJson()).containsEntry("role", "editor");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "\"legacy\"", "17",
            "{\"permissions\":[\"USER_MANAGE\"]}",
            "{\"role\":\"superuser\",\"permissions\":[\"USER_MANAGE\"]}",
            "{\"role\":17,\"permissions\":[\"USER_MANAGE\"]}"
    })
    void shouldReadMalformedLegacyDataWithBothJacksonVersionsWithoutGrantingAdmin(String stored) throws Exception {
        for (UserResource parsed : List.of(jackson2.readValue(stored, UserResource.class), jackson3.readValue(stored, UserResource.class))) {
            assertThat(parsed.role()).isEqualTo(Role.VIEWER);
            assertThat(parsed.permissions()).containsExactlyInAnyOrder(PRODUCT_READ, SALE_READ);
        }
    }

    @Test
    void shouldNotGrantUnknownOrMalformedPermissionEntriesFromStorage() throws Exception {
        String stored = "{\"role\":\"VIEWER\",\"permissions\":[\"PRODUCT_READ\",42,null,\"ALL_POWERS\"]}";
        assertThat(jackson2.readValue(stored, UserResource.class).permissions()).containsExactly(PRODUCT_READ);
        assertThat(jackson3.readValue(stored, UserResource.class).permissions()).containsExactly(PRODUCT_READ);
        assertThat(jackson2.readValue("{\"role\":\"ADMIN\",\"permissions\":{}}", UserResource.class).permissions()).isEmpty();
        User user = new User();
        user.setResource(null);
        assertThat(user.getResource()).isEqualTo(UserResource.defaults());
    }

    @Test
    void shouldPreserveUnrelatedMetadataIncludingNullValuesThroughSerialization() throws Exception {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("cpf", "test");
        metadata.put("optional", null);
        metadata.put("preferences", Map.of("theme", "dark"));
        metadata.put("role", "superuser");
        UserResource original = new UserResource(Role.MANAGER, null, metadata);
        String persisted = jackson2.writeValueAsString(original);
        assertThat(jackson2.readValue(persisted, UserResource.class)).isEqualTo(original);
        assertThat(jackson3.readValue(jackson3.writeValueAsString(original), UserResource.class)).isEqualTo(original);
        assertThat(persisted).contains("\"role\":\"MANAGER\"").doesNotContain("attributes", "superuser");
        assertThat(original.attributes()).containsEntry("optional", null);
    }

    @Test
    void shouldPreserveCustomPermissionsOnMetadataUpdatesAndResetDefaultsOnRoleChange() {
        UserResource current = new UserResource(Role.ADMIN, Set.of(PRODUCT_READ), Map.of("cpf", "test"));
        UserResource metadataOnly = current.update(Map.of("theme", "dark"), null, null);
        assertThat(metadataOnly.role()).isEqualTo(Role.ADMIN);
        assertThat(metadataOnly.permissions()).containsExactly(PRODUCT_READ);
        assertThat(metadataOnly.attributes()).containsEntry("cpf", "test").containsEntry("theme", "dark");
        assertThat(current.update(null, "MANAGER", null).permissions()).isEqualTo(Role.MANAGER.defaultPermissions());
    }

    @Test
    void shouldRoundTripEveryPermissionSubsetThroughLegacyProjectionWithoutEscalation() {
        Permission[] all = Permission.values();
        for (Role role : Role.values()) {
            for (int mask = 0; mask < (1 << all.length); mask++) {
                Set<Permission> permissions = EnumSet.noneOf(Permission.class);
                for (int i = 0; i < all.length; i++) {
                    if ((mask & (1 << i)) != 0) permissions.add(all[i]);
                }
                UserResource original = new UserResource(role, permissions, Map.of("cpf", "test"));
                UserResource restored = UserResource.create(original.toLegacyJson(), null, null);
                assertThat(restored).as("role=%s, mask=%s", role, mask).isEqualTo(original);
            }
        }
    }
}
