package com.puccampinas.omnisync.config.security;

import com.puccampinas.omnisync.core.users.enums.Permission;
import org.springframework.security.access.AccessDeniedException;

/** Negação de uma permissão de domínio, com mensagem segura para a API. */
public final class PermissionDeniedException extends AccessDeniedException {
    public PermissionDeniedException(Permission permission) {
        super("Permissão insuficiente: " + permission.name());
    }
}
