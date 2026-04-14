package com.puccampinas.omnisync.core.sale.service;

<<<<<<< HEAD
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

=======
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.core.systemClient.repository.SystemClientRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
@Service
public class SaleService {

    private final SaleRepository saleRepository;
<<<<<<< HEAD
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
=======
    private final SystemClientRepository systemClientRepository;
    private final ProductRepository productRepository;

    public SaleService(SaleRepository saleRepository, SystemClientRepository systemClientRepository, ProductRepository productRepository) {
        this.saleRepository = saleRepository;
        this.systemClientRepository = systemClientRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Sale createSale(SaleCreateRequest request) {
        Long clientID = request.getSystemClientId();
        SystemClient client = systemClientRepository.findById(clientID)
                .orElseThrow(() -> new RuntimeException("Cliente nao encontrado com o ID: " + clientID));

        // CORREÇÃO 1 e 2: Garante que o produto existe, está ATIVO e PERTENCE a este cliente
        Product product = productRepository.findByIdAndSystemClientIdAndActiveTrue(request.getProductId(), clientID)
                .orElseThrow(() -> new RuntimeException("Produto não encontrado, inativo ou não pertence a este cliente. ID: " + request.getProductId()));

        // CORREÇÃO 3: Valida se há estoque suficiente
        if (product.getStock() < request.getQuantity()) {
            throw new RuntimeException("Estoque insuficiente para o produto: " + product.getName());
        }

        // Dá baixa no estoque do produto
        product.setStock(product.getStock() - request.getQuantity());
        productRepository.save(product); // Salva o estoque atualizado

        // Cria a venda
        Sale sale = new Sale();
        sale.setSystemClient(client);
        sale.setProduct(product);
        sale.setQuantity(request.getQuantity());
        sale.setTotalValue(request.getTotalValue());
        sale.setChannel(request.getChannel());
        sale.setExternalReferenceId(request.getExternalReferenceId());
        sale.setStatus(SaleStatus.CONFIRMED);

        return saleRepository.save(sale);
    }
}
>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
