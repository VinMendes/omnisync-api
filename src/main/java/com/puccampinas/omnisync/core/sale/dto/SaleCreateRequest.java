package com.puccampinas.omnisync.core.sale.dto;

import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import java.math.BigDecimal;
import java.util.Map;

public class SaleCreateRequest {
    private Long systemClientId;
    private Long productId;
    private Integer quantity;
    private BigDecimal totalValue;
    private SaleChannel channel;
    private String externalReferenceId;
    private Map<String, Object> resource;

    // Getters e Setters
    public Long getSystemClientId() { return systemClientId; }
    public void setSystemClientId(Long systemClientId) { this.systemClientId = systemClientId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getTotalValue() { return totalValue; }
    public void setTotalValue(BigDecimal totalValue) { this.totalValue = totalValue; }

    public SaleChannel getChannel() { return channel; }
    public void setChannel(SaleChannel channel) { this.channel = channel; }

    public String getExternalReferenceId() { return externalReferenceId; }
    public void setExternalReferenceId(String externalReferenceId) { this.externalReferenceId = externalReferenceId; }

    public Map<String, Object> getResource() { return resource; }
    public void setResource(Map<String, Object> resource) { this.resource = resource; }
}