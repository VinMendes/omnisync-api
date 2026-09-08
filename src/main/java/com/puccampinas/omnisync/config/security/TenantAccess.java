package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import com.puccampinas.omnisync.core.users.enums.Permission;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Valida o cliente informado nas rotas autenticadas antes de acessar os serviços. */
@Component
public class TenantAccess {
    public void requireTenant(Long systemClientId) {
        if (!principal().getSystemClientId().equals(systemClientId)) {
            throw new EntityNotFoundException("Recurso não encontrado.");
        }
    }

    public void requirePermission(Permission permission) {
        boolean allowed = principal().getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(PermissionAuthority.of(permission)));
        if (!allowed) {
            throw new PermissionDeniedException(permission);
        }
    }

    public OmniUserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OmniUserPrincipal principal)) {
            throw new AccessDeniedException("Acesso negado.");
        }
        return principal;
    }
}
