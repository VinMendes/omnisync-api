package com.puccampinas.omnisync.core.sale.dto;

import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;

public class SaleCreateRequest {
    @Positive
    private Long systemClientId;

    @NotNull
    @Positive
    private Long productId;

    @NotNull
    @Positive
    private Integer quantity;

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal totalValue;

    private SaleChannel channel = SaleChannel.MANUAL;

    @NotBlank
    @Size(max = 150)
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

    public SaleChannel getChannel() { return channel == null ? SaleChannel.MANUAL : channel; }
    public void setChannel(SaleChannel channel) { this.channel = channel == null ? SaleChannel.MANUAL : channel; }

    public String getExternalReferenceId() { return externalReferenceId; }
    public void setExternalReferenceId(String externalReferenceId) { this.externalReferenceId = externalReferenceId; }

    public Map<String, Object> getResource() { return resource; }
    public void setResource(Map<String, Object> resource) { this.resource = resource; }
}
