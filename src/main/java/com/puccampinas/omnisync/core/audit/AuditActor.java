package com.puccampinas.omnisync.core.audit;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.users.entity.User;

public record AuditActor(Long tenantId, Long id, String name, String email, String role) {
    public static AuditActor system(Long tenantId) {
        return new AuditActor(tenantId, null, "Sistema", null, "SYSTEM");
    }

    public static AuditActor from(User user) {
        return new AuditActor(user.getSystemClientId(), user.getId(), user.getName(), user.getEmail(),
                user.getTenantRole().getName().name());
    }

    public static AuditActor from(OmniUserPrincipal principal) {
        String role = principal.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).findFirst().orElse("UNKNOWN");
        return new AuditActor(principal.getSystemClientId(), principal.getUserId(),
                principal.getName(), principal.getEmail(), role);
    }
}
