package com.puccampinas.omnisync.core.product.service;

import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.entity.ProductLog;
import com.puccampinas.omnisync.core.product.repository.ProductLogRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProductLogService {

    private static final String ACTION_CREATE = "create";
    private static final String ACTION_EDIT = "edit";
    private static final String ACTION_DELETE = "delete";

    private final ProductLogRepository productLogRepository;

    public ProductLogService(ProductLogRepository productLogRepository) {
        this.productLogRepository = productLogRepository;
    }

    public void logCreate(Product product) {
        productLogRepository.save(buildLog(
                product.getId(),
                product.getSystemClientId(),
                ACTION_CREATE,
                null,
                product.getStock(),
                null,
                product.getPrice(),
                Map.of("current", snapshot(product))
        ));
    }

    public void logEdit(Product previousState, Product currentState) {
        productLogRepository.save(buildLog(
                currentState.getId(),
                currentState.getSystemClientId(),
                ACTION_EDIT,
                previousState.getStock(),
                currentState.getStock(),
                previousState.getPrice(),
                currentState.getPrice(),
                Map.of(
                        "previous", snapshot(previousState),
                        "current", snapshot(currentState)
                )
        ));
    }

    public void logDelete(Product previousState, Product currentState) {
        productLogRepository.save(buildLog(
                currentState.getId(),
                currentState.getSystemClientId(),
                ACTION_DELETE,
                previousState.getStock(),
                null,
                previousState.getPrice(),
                null,
                Map.of(
                        "previous", snapshot(previousState),
                        "current", snapshot(currentState)
                )
        ));
    }

    private ProductLog buildLog(
            Long productId,
            Long systemClientId,
            String action,
            Integer oldStock,
            Integer newStock,
            java.math.BigDecimal oldPrice,
            java.math.BigDecimal newPrice,
            Map<String, Object> metadata
    ) {
        ProductLog log = new ProductLog();
        log.setProductId(productId);
        log.setSystemClientId(systemClientId);
        log.setAction(action);
        log.setOldStock(oldStock);
        log.setNewStock(newStock);
        log.setOldPrice(oldPrice);
        log.setNewPrice(newPrice);
        log.setMetadata(metadata);
        return log;
    }

    private Map<String, Object> snapshot(Product product) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", product.getId());
        snapshot.put("system_client_id", product.getSystemClientId());
        snapshot.put("sku", product.getSku());
        snapshot.put("name", product.getName());
        snapshot.put("description", product.getDescription());
        snapshot.put("stock", product.getStock());
        snapshot.put("reserved_stock", product.getReservedStock());
        snapshot.put("price", product.getPrice());
        snapshot.put("resource", product.getResource());
        snapshot.put("active", product.getActive());
        snapshot.put("created_at", product.getCreatedAt() == null ? null : product.getCreatedAt().toString());
        return snapshot;
    }
}
