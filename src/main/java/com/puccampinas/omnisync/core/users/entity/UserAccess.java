package com.puccampinas.omnisync.core.users.entity;

import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interpreta os campos de acesso no formato novo e no resource legado da API. */
public record UserAccess(Role role, Set<Permission> permissions, boolean permissionsProvided) {

    public UserAccess {
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
    }

    public static UserAccess forCreate(Map<String, Object> resource, String role, List<String> permissions) {
        return resolve(resource, role, permissions, Role.VIEWER);
    }

    public static UserAccess forUpdate(Map<String, Object> resource, String role, List<String> permissions,
                                       Role currentRole) {
        return resolve(resource, role, permissions, currentRole);
    }

    private static UserAccess resolve(Map<String, Object> resource, String role, List<String> permissions,
                                      Role fallbackRole) {
        Map<String, Object> input = resource == null ? Map.of() : resource;
        Role nestedRole = input.get("role") == null ? null : parseRole(input.get("role"));
        Role explicitRole = role == null ? null : Role.parse(role);
        if (nestedRole != null && explicitRole != null && nestedRole != explicitRole) {
            throw new IllegalArgumentException("role e resource.role devem informar o mesmo papel.");
        }

        boolean nestedPermissionsProvided = input.get("permissions") != null;
        Set<Permission> nestedPermissions = nestedPermissionsProvided
                ? Permission.parseSet(input.get("permissions"), true) : null;
        boolean explicitPermissionsProvided = permissions != null;
        Set<Permission> explicitPermissions = explicitPermissionsProvided
                ? Permission.parseSet(permissions, true) : null;
        if (nestedPermissionsProvided && explicitPermissionsProvided
                && !nestedPermissions.equals(explicitPermissions)) {
            throw new IllegalArgumentException("permissions e resource.permissions devem informar as mesmas permissões.");
        }

        Role selectedRole = explicitRole != null ? explicitRole : nestedRole != null ? nestedRole : fallbackRole;
        Set<Permission> selectedPermissions = explicitPermissionsProvided ? explicitPermissions
                : nestedPermissionsProvided ? nestedPermissions : Set.of();
        return new UserAccess(selectedRole, selectedPermissions,
                nestedPermissionsProvided || explicitPermissionsProvided);
    }

    private static Role parseRole(Object value) {
        if (!(value instanceof String name)) {
            throw new IllegalArgumentException("role deve ser uma string: ADMIN, MANAGER, SELLER ou VIEWER.");
        }
        return Role.parse(name);
    }

    /**
     * Resolve as permissões a serem persistidas na configuração exclusiva do usuário.
     * Uma lista explícita, inclusive vazia, sempre prevalece. Sem lista, preserva as
     * permissões atuais quando o papel não mudou; nos demais casos aplica o padrão.
     */
    public Set<Permission> effectivePermissions(TenantRole currentRole) {
        if (permissionsProvided) {
            return permissions;
        }
        if (currentRole != null && currentRole.getName() == role) {
            return currentRole.getPermissions();
        }
        return role.defaultPermissions();
    }
}
