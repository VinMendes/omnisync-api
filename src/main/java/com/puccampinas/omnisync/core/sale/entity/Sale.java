package com.puccampinas.omnisync.core.sale.entity;


import com.fasterxml.jackson.databind.JsonNode;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "sales")
public class Sale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;



    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_client_id", nullable = false)
    private SystemClient systemClientId;

    //   vamos usar a estrutura quando tivermos o Product!
//    @ManyToOne(fetch = FetchType.LAZY)
//    @JoinColumn(name = "system_client_id", nullable = false)
//    private SystemClient systemClient;


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "total_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> resource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SaleChannel channel;

    @Column(name = "external_reference_id", length = 150)
    private String externalReferenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private SaleStatus saleStatus;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Getters e Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public SystemClient getSystemClient() { return systemClientId; }
    public void setSystemClient(SystemClient systemClient) { this.systemClientId = systemClient; }

    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getTotalValue() { return totalValue; }
    public void setTotalValue(BigDecimal totalValue) { this.totalValue = totalValue; }

    public Map<String, Object> getResource() { return resource; }
    public void setResource(Map<String, Object> resource) { this.resource = resource; }

    public SaleChannel getChannel() { return channel; }
    public void setChannel(SaleChannel channel) { this.channel = channel; }

    public String getExternalReferenceId() { return externalReferenceId; }
    public void setExternalReferenceId(String externalReferenceId) { this.externalReferenceId = externalReferenceId; }

    public SaleStatus getStatus() { return saleStatus; }
    public void setStatus(SaleStatus status) { this.saleStatus = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
