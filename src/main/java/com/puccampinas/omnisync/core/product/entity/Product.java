package com.puccampinas.omnisync.core.product.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "products")
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system_client_id", nullable = false, columnDefinition = "LONG")
    private Long systemClientId;

    @Column(name = "sku", nullable = false, columnDefinition = "TEXT")
    private String sku;

    @Column(name = "name", nullable = false, columnDefinition = "TEXT")
    private String name;

    @Column(name="description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name="stock", nullable = false, columnDefinition = "INT")
    private int stock;

    @Column(name="reserved_stock", nullable = false, columnDefinition = "INT")
    private int reservedStock;

    @Column(name="price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "resource", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> resource;

    @Column(name = "active", nullable = false, columnDefinition = "bool")
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public Long getSystemClientId() {
        return systemClientId;
    }

    public void setSystemClientId(Long systemClientId) {
        this.systemClientId = systemClientId;
    }

    public String getSku() {
        return this.sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName(){ return this.name; }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getPrice() {
        return this.price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public int getStock() {
        return this.stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public int getReservedStock() {
        return reservedStock;
    }

    public void setReservedStock(int reservedStock) {
        this.reservedStock = reservedStock;
    }

    public boolean getActive() {
        return this.active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<String, Object> getResource() {
        return this.resource;
    }

    public void setResource(Map<String, Object> resource) {
        this.resource = resource;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
