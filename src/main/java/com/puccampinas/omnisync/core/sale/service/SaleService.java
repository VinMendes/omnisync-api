package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.common.util.OffsetLimitPageable;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.dto.SaleLogDto;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.entity.SaleLog;
import com.puccampinas.omnisync.core.sale.repository.SaleLogRepository;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SaleService {

    private final SaleRepository saleRepository;
    private final SaleLogRepository saleLogRepository;

    public SaleService(SaleRepository saleRepository, SaleLogRepository saleLogRepository) {
        this.saleRepository = saleRepository;
        this.saleLogRepository = saleLogRepository;
    }

    public Page<SaleDto> getAll(Long systemClientId, long offset, int limit) {
        validateSystemClientId(systemClientId);
        validatePagination(offset, limit);

        return saleRepository.findAllBySystemClientId(
                systemClientId,
                new OffsetLimitPageable(offset, limit)
        ).map(sale -> toDto(sale, false));
    }

    public SaleDto getById(Long systemClientId, Long id) {
        validateIdentifiers(systemClientId, id);

        Sale sale = saleRepository.findByIdAndSystemClientId(id, systemClientId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Sale not found for id=" + id + " and systemClientId=" + systemClientId
                ));

        return toDto(sale, true);
    }

    private SaleDto toDto(Sale sale, boolean includeLogs) {
        SaleDto dto = new SaleDto();
        dto.setId(sale.getId());
        dto.setSystemClientId(sale.getSystemClientId());
        dto.setProductId(sale.getProductId());
        dto.setQuantity(sale.getQuantity());
        dto.setTotalValue(sale.getTotalValue());
        dto.setResource(sale.getResource());
        dto.setChannel(sale.getChannel());
        dto.setExternalReferenceId(sale.getExternalReferenceId());
        dto.setStatus(sale.getStatus());
        dto.setCreatedAt(sale.getCreatedAt());

        if (includeLogs) {
            dto.setLogs(saleLogRepository.findAllBySaleIdOrderByCreatedAtAsc(sale.getId())
                    .stream()
                    .map(this::toLogDto)
                    .toList());
        }

        return dto;
    }

    private SaleLogDto toLogDto(SaleLog log) {
        SaleLogDto dto = new SaleLogDto();
        dto.setId(log.getId());
        dto.setSaleId(log.getSaleId());
        dto.setSystemClientId(log.getSystemClientId());
        dto.setAction(log.getAction());
        dto.setPreviousStatus(log.getPreviousStatus());
        dto.setNewStatus(log.getNewStatus());
        dto.setResource(log.getResource());
        dto.setMetadata(log.getMetadata());
        dto.setCreatedAt(log.getCreatedAt());
        return dto;
    }

    private void validateSystemClientId(Long systemClientId) {
        if (systemClientId == null) {
            throw new IllegalArgumentException("System client id is required.");
        }
    }

    private void validatePagination(long offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be greater than or equal to 0.");
        }

        if (limit < 1) {
            throw new IllegalArgumentException("Limit must be greater than 0.");
        }
    }

    private void validateIdentifiers(Long systemClientId, Long id) {
        if (systemClientId == null && id == null) {
            throw new IllegalArgumentException("System client id and id are required.");
        }

        if (systemClientId == null) {
            throw new IllegalArgumentException("System client id is required.");
        }

        if (id == null) {
            throw new IllegalArgumentException("Id is required.");
        }
    }
}
