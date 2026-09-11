package com.puccampinas.omnisync.core.product.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record LowStockProductsResponse(
        List<ProductDto> content,
        long offset,
        int limit,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("has_next") boolean hasNext
) {
}
