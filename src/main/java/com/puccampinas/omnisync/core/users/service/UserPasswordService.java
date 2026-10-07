package com.puccampinas.omnisync.core.users.service;

import com.puccampinas.omnisync.config.security.TenantAccess;
import com.puccampinas.omnisync.core.audit.AuditService;
import com.puccampinas.omnisync.core.audit.AuditSource;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordPolicy;
import com.puccampinas.omnisync.core.auth.passwordreset.PasswordResetTokenRepository;
import com.puccampinas.omnisync.core.users.dto.AdminPasswordResetRequest;
import com.puccampinas.omnisync.core.users.dto.ChangePasswordRequest;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class UserPasswordService {
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder encoder;
    private final TenantAccess access;
    private final AuditService audit;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public UserPasswordService(UserRepository users, PasswordResetTokenRepository tokens,
                               PasswordEncoder encoder, TenantAccess access, AuditService audit) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.access = access;
        this.audit = audit;
    }

    @Transactional
    public void changeOwn(ChangePasswordRequest request) {
        User user = requireUser(access.principal().getUserId());
        if (request.currentPassword() == null || request.currentPassword().isBlank())
            throw new IllegalArgumentException("A senha atual é obrigatória.");
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash()))
            throw new IllegalArgumentException("Senha atual incorreta.");
        PasswordPolicy.validateConfirmation(request.newPassword(), request.confirmation());
        update(user, request.newPassword(), AuditSource.PASSWORD_CHANGE);
    }

    @Transactional
    public void resetOther(Long userId, AdminPasswordResetRequest request) {
        access.requirePermission(Permission.USER_MANAGE);
        if (access.principal().getUserId().equals(userId))
            throw new IllegalArgumentException("Para alterar a própria senha, informe a senha atual na rota /api/users/me/password.");
        User user = requireUser(userId);
        PasswordPolicy.validateConfirmation(request.newPassword(), request.confirmation());
        update(user, request.newPassword(), AuditSource.ADMIN_PASSWORD_RESET);
    }

    private User requireUser(Long id) {
        // Tenant predicate is checked before locking so foreign rows are not touched.
        if (!users.existsByIdAndSystemClientId(id, access.principal().getSystemClientId()))
            throw new EntityNotFoundException("Usuário não encontrado.");
        User user = users.findForPasswordUpdate(id)
                .orElseThrow(() -> new EntityNotFoundException("Usuário não encontrado."));
        entityManager.refresh(user);
        access.requireTenant(user.getSystemClientId());
        return user;
    }

    private void update(User user, String password, AuditSource source) {
        user.setPasswordHash(encoder.encode(password));
        users.save(user);
        tokens.invalidateForUser(user.getId(), Instant.now());
        audit.credentialChanged(user, source);
    }
}
