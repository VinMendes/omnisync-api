package com.puccampinas.omnisync.core.users.entity;

import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Set;

@Entity
@Table(name = "roles")
public class TenantRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "name", nullable = false, length = 50)
    private Role name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resource", nullable = false, columnDefinition = "jsonb")
    private RoleResource resource = RoleResource.of(Set.of());

    @Column(name = "system_client_id", nullable = false)
    private Long systemClientId;

    public Long getId() {
        return id;
    }

    public Role getName() {
        return name;
    }

    public void setName(Role name) {
        this.name = name;
    }

    public RoleResource getResource() {
        return resource == null ? RoleResource.of(Set.of()) : resource;
    }

    public void setResource(RoleResource resource) {
        this.resource = resource;
    }

    public Long getSystemClientId() {
        return systemClientId;
    }

    public void setSystemClientId(Long systemClientId) {
        this.systemClientId = systemClientId;
    }

    public Set<Permission> getPermissions() {
        return getResource().permissions();
    }

    public List<String> permissionNames() {
        return Permission.names(getPermissions());
    }
}
