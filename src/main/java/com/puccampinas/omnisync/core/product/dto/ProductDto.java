package com.puccampinas.omnisync.core.product.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public class ProductDto {

    private Long id;
    private String sku;
    private String name;
    private String description;
    private int stock;
    @JsonProperty("reserved_stock")
    private int reservedStock;
    private BigDecimal price;
    private Map<String, Object> resource;
    @JsonProperty("system_client_id")
    private Long systemClientId;
    private boolean active;
    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public int getStock() {
        return this.stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public int getReservedStock() {
        return reservedStock;
    }

    public BigDecimal getPrice() {
        return this.price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public void setReservedStock(int reservedStock) {
        this.reservedStock = reservedStock;
    }

    public Map<String, Object> getResource() {
        return this.resource;
    }

    public void setResource(Map<String, Object> resource) {
        this.resource = resource;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
