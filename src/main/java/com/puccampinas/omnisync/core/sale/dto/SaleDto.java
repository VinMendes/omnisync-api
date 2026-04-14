package com.puccampinas.omnisync.core.sale.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class SaleDto {

    private Long id;

    @JsonProperty("system_client_id")
    private Long systemClientId;

    @JsonProperty("product_id")
    private Long productId;

    private Integer quantity;

    @JsonProperty("total_value")
    private BigDecimal totalValue;

    private Map<String, Object> resource;

    private String channel;

    @JsonProperty("external_reference_id")
    private String externalReferenceId;

    private String status;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    private List<SaleLogDto> logs;

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

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public Map<String, Object> getResource() {
        return resource;
    }

    public void setResource(Map<String, Object> resource) {
        this.resource = resource;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getExternalReferenceId() {
        return externalReferenceId;
    }

    public void setExternalReferenceId(String externalReferenceId) {
        this.externalReferenceId = externalReferenceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<SaleLogDto> getLogs() {
        return logs;
    }

    public void setLogs(List<SaleLogDto> logs) {
        this.logs = logs;
    }
}
