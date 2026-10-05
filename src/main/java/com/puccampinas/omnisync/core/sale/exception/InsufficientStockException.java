package com.puccampinas.omnisync.core.sale.exception;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InsufficientStockException extends RuntimeException {

    public static final String CODE = "INSUFFICIENT_STOCK";
    public static final String MESSAGE = "Estoque insuficiente para concluir a venda.";

    private final Long productId;
    private final int requestedQuantity;
    private final int availableQuantity;

    public InsufficientStockException(Long productId, int requestedQuantity, int availableQuantity) {
        super(MESSAGE);
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public Long getProductId() {
        return productId;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public Map<String, Object> details() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("product_id", productId);
        details.put("requested_quantity", requestedQuantity);
        details.put("available_quantity", availableQuantity);
        return details;
    }
}
