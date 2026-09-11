package com.puccampinas.omnisync.core.audit;

import com.puccampinas.omnisync.core.auth.security.OmniUserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.Objects;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void initialUserCreated(com.puccampinas.omnisync.core.users.entity.User user) {
        recordAs(AuditActor.from(user), AuditAction.CREATE, AuditEntityType.USER, user.getId(),
                null, AuditSnapshots.user(user), AuditSource.SIGNUP, Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void credentialChanged(com.puccampinas.omnisync.core.users.entity.User user) {
        recordAs(AuditActor.from(user), AuditAction.UPDATE, AuditEntityType.USER, user.getId(),
                null, null, AuditSource.PASSWORD_RESET, Map.of("changed_fields", java.util.List.of("credential")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void userCreated(com.puccampinas.omnisync.core.users.entity.User user) {
        AuditActor actor = actor(user.getSystemClientId());
        boolean signup = actor.id() == null;
        recordAs(signup ? AuditActor.from(user) : actor, AuditAction.CREATE, AuditEntityType.USER,
                user.getId(), null, AuditSnapshots.user(user), signup ? AuditSource.SIGNUP : AuditSource.WEB, Map.of());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordForUser(com.puccampinas.omnisync.core.users.entity.User user, AuditAction action,
                              AuditEntityType type, Object id, Map<String, Object> before,
                              Map<String, Object> after, AuditSource source, Map<String, Object> details) {
        recordAs(AuditActor.from(user), action, type, id, before, after, source, details);
    }

    public AuditActor actor(Long tenantId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OmniUserPrincipal principal) {
            AuditActor actor = AuditActor.from(principal);
            if (!Objects.equals(actor.tenantId(), tenantId)) {
                throw new IllegalArgumentException("O autor da auditoria pertence a outra empresa.");
            }
            return actor;
        }
        return AuditActor.system(tenantId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long tenantId, AuditAction action, AuditEntityType entityType, Object entityId,
                       Map<String, Object> before, Map<String, Object> after, AuditSource source) {
        recordAs(actor(tenantId), action, entityType, entityId, before, after, source, Map.of());
    }

    /** Same transaction as the business change, never REQUIRES_NEW or asynchronous best-effort. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(AuditActor actor, AuditAction action, AuditEntityType entityType, Object entityId,
                         Map<String, Object> before, Map<String, Object> after, AuditSource source,
                         Map<String, Object> details) {
        Objects.requireNonNull(actor.tenantId(), "Audit tenant is required");
        Objects.requireNonNull(entityId, "Audit entity id is required");
        var metadata = new java.util.LinkedHashMap<String, Object>(AuditSanitizer.clean(details));
        metadata.put("source", source.name());
        repository.save(new AuditLog(actor.tenantId(), actor, action, entityType, entityId.toString(),
                description(action, entityType), AuditSanitizer.clean(before), AuditSanitizer.clean(after), metadata));
    }

    private String description(AuditAction action, AuditEntityType type) {
        String resource = switch (type) {
            case USER -> "Usuário";
            case PRODUCT -> "Produto";
            case SALE -> "Venda";
            case INTEGRATION -> "Integração Mercado Livre";
            case LISTING -> "Anúncio";
        };
        String operation = switch (action) {
            case CREATE -> "criação";
            case UPDATE -> "alteração";
            case ACTIVATE -> "ativação";
            case DEACTIVATE -> "desativação";
            case DELETE -> "exclusão";
            case CONNECT -> "conexão";
            case DISCONNECT -> "desconexão";
            case SYNC -> "sincronização";
            case PUBLISH -> "publicação";
            case CLOSE -> "encerramento";
        };
        return resource + ": " + operation;
    }
}
