package com.puccampinas.omnisync.core.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "system_client_id")
    private Long systemClientId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "user_role")
    private String userRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action")
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type")
    private AuditEntityType entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "previous_data", columnDefinition = "jsonb")
    private Map<String, Object> previousData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_data", columnDefinition = "jsonb")
    private Map<String, Object> newData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    protected AuditLog() {}

    AuditLog(Long tenantId, AuditActor actor, AuditAction action, AuditEntityType entityType,
             String entityId, String description, Map<String, Object> previousData,
             Map<String, Object> newData, Map<String, Object> metadata) {
        this.systemClientId = tenantId;
        this.userId = actor.id();
        this.userName = actor.name();
        this.userEmail = actor.email();
        this.userRole = actor.role();
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.description = description;
        this.previousData = previousData;
        this.newData = newData;
        this.metadata = metadata;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public AuditResponse response() {
        return new AuditResponse(id, systemClientId, new AuditResponse.Actor(userId, userName, userEmail, userRole),
                action, entityType, entityId, description, previousData, newData, metadata, createdAt);
    }
}
