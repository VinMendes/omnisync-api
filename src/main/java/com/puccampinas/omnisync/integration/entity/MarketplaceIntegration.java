package com.puccampinas.omnisync.integration.entity;
import com.puccampinas.omnisync.common.enums.Marketplace;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(
        name = "marketplace_integrations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_client_marketplace",
                        columnNames = {"system_client_id", "marketplace"}
                )
        }
)
public class MarketplaceIntegration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system_client_id", nullable = false)
    private Long systemClientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Marketplace marketplace;

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "resource", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> resource;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public MarketplaceIntegration() {}

    // ===== GETTERS E SETTERS =====

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getSystemClientId() { return systemClientId; }

    public void setSystemClientId(Long systemClientId) {
        this.systemClientId = systemClientId;
    }

    public Marketplace getMarketplace() { return marketplace; }

    public void setMarketplace(Marketplace marketplace) {
        this.marketplace = marketplace;
    }

    public String getAccessToken() { return accessToken; }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() { return refreshToken; }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public LocalDateTime getExpiresAt() { return expiresAt; }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Map<String, Object> getResource() {
        return this.resource;
    }

    public void setResource(Map<String, Object> resource) {
        this.resource = resource;
    }

    public Boolean getActive() { return active; }

    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}