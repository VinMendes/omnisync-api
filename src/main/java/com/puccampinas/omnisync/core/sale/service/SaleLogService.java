package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.entity.SaleLog;
import com.puccampinas.omnisync.core.sale.repository.SaleLogRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SaleLogService {

    private final SaleLogRepository saleLogRepository;

    public SaleLogService(SaleLogRepository saleLogRepository) {
        this.saleLogRepository = saleLogRepository;
    }

    public void logCreated(Sale sale, Map<String, Object> metadata) {
        saleLogRepository.save(buildLog(sale, "CREATED", null, sale.getStatus(), metadata));
    }

    public void logUpdated(Sale sale, String previousStatus, Map<String, Object> metadata) {
        saleLogRepository.save(buildLog(sale, "UPDATED", previousStatus, sale.getStatus(), metadata));
    }

    public void logCancelled(Sale sale, String previousStatus, Map<String, Object> metadata) {
        saleLogRepository.save(buildLog(sale, "CANCELLED", previousStatus, sale.getStatus(), metadata));
    }

    private SaleLog buildLog(
            Sale sale,
            String action,
            String previousStatus,
            String newStatus,
            Map<String, Object> metadata
    ) {
        SaleLog log = new SaleLog();
        log.setSaleId(sale.getId());
        log.setSystemClientId(sale.getSystemClientId());
        log.setAction(action);
        log.setPreviousStatus(previousStatus);
        log.setNewStatus(newStatus);
        log.setResource(sale.getResource());
        log.setMetadata(metadata == null ? Map.of() : metadata);
        return log;
    }

    public Map<String, Object> stockMetadata(int previousStock, int newStock, Integer quantity, Object notification) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previous_stock", previousStock);
        metadata.put("new_stock", newStock);
        metadata.put("quantity", quantity);
        metadata.put("notification", notification);
        return metadata;
    }
}
