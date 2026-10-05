package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.audit.AuditAction;
import com.puccampinas.omnisync.core.audit.AuditEntityType;
import com.puccampinas.omnisync.core.audit.AuditService;
import com.puccampinas.omnisync.core.audit.AuditSnapshots;
import com.puccampinas.omnisync.core.audit.AuditSource;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.exception.InsufficientStockException;
import com.puccampinas.omnisync.core.sale.exception.IdempotencyConflictException;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.integration.service.MarketplaceStockSyncOutboxService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SaleRegistrationTransaction {

    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final SaleLogService saleLogService;
    private final AuditService audit;
    private final MarketplaceStockSyncOutboxService outboxService;
    private final SaleRequestNormalizer normalizer;

    public SaleRegistrationTransaction(
            ProductRepository productRepository,
            SaleRepository saleRepository,
            SaleLogService saleLogService,
            AuditService audit,
            MarketplaceStockSyncOutboxService outboxService,
            SaleRequestNormalizer normalizer
    ) {
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.saleLogService = saleLogService;
        this.audit = audit;
        this.outboxService = outboxService;
        this.normalizer = normalizer;
    }

    @Transactional
    public List<Sale> register(Long systemClientId, List<SaleCreateRequest> requests) {
        validate(systemClientId, requests);

        List<SaleRequestNormalizer.RequestGroup> groups = normalizer.normalizeAndGroup(requests);
        Map<SaleRequestNormalizer.RequestKey, Sale> resolved = new LinkedHashMap<>();
        List<SaleRequestNormalizer.RequestGroup> unresolved = new ArrayList<>();
        for (SaleRequestNormalizer.RequestGroup group : groups) {
            Sale existing = findExisting(systemClientId, group);
            if (existing == null) {
                unresolved.add(group);
            } else {
                resolveExisting(group, existing, resolved);
            }
        }

        List<Long> productIds = unresolved.stream()
                .map(group -> group.request().getProductId())
                .distinct()
                .sorted()
                .toList();
        Map<Long, Product> products = new LinkedHashMap<>();
        if (!productIds.isEmpty()) {
            products.putAll(productRepository
                    .findAllActiveBySystemClientIdAndIdInForUpdate(systemClientId, productIds)
                    .stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity())));
            if (products.size() != productIds.size()) {
                throw new EntityNotFoundException("Produto nao encontrado, inativo, ou nao pertence a este cliente");
            }
        }

        List<SaleRequestNormalizer.RequestGroup> newGroups = new ArrayList<>();
        for (SaleRequestNormalizer.RequestGroup group : unresolved) {
            Sale existing = findExisting(systemClientId, group);
            if (existing == null) {
                newGroups.add(group);
            } else {
                resolveExisting(group, existing, resolved);
            }
        }

        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (SaleRequestNormalizer.RequestGroup group : newGroups) {
            SaleCreateRequest request = group.request();
            quantities.merge(request.getProductId(), request.getQuantity(), Math::addExact);
        }
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            Product product = products.get(entry.getKey());
            int available = product.getStock() - product.getReservedStock();
            if (entry.getValue() > available) {
                throw new InsufficientStockException(product.getId(), entry.getValue(), available);
            }
        }

        quantities.forEach((productId, quantity) -> {
            Product product = products.get(productId);
            product.setStock(product.getStock() - quantity);
        });
        productRepository.saveAll(products.values().stream().sorted(Comparator.comparing(Product::getId)).toList());

        for (SaleRequestNormalizer.RequestGroup group : newGroups.stream()
                .sorted(java.util.Comparator.comparing(SaleRequestNormalizer.RequestGroup::key))
                .toList()) {
            SaleCreateRequest request = group.request();
            Product product = products.get(request.getProductId());
            Sale sale = new Sale();
            sale.setSystemClientId(systemClientId);
            sale.setProductId(product.getId());
            sale.setQuantity(request.getQuantity());
            sale.setTotalValue(request.getTotalValue());
            sale.setChannel(request.getChannel().name());
            sale.setExternalReferenceId(request.getExternalReferenceId().trim());
            sale.setStatus(SaleStatus.CONFIRMED.name());
            sale.setResource(request.getResource());
            Sale saved = saleRepository.save(sale);
            Map<String, Object> metadata = outboxService.enqueueIfRequired(saved, product)
                    .<Map<String, Object>>map(outbox -> Map.of("outbox_id", outbox.getId()))
                    .orElseGet(Map::of);
            saleLogService.logCreated(saved, metadata);
            audit.record(systemClientId, AuditAction.CREATE, AuditEntityType.SALE, saved.getId(),
                    null, AuditSnapshots.sale(saved), AuditSource.WEB);
            resolved.put(group.key(), saved);
        }
        saleRepository.flush();

        List<Sale> result = new ArrayList<>(java.util.Collections.nCopies(requests.size(), null));
        for (SaleRequestNormalizer.RequestGroup group : groups) {
            Sale sale = resolved.get(group.key());
            for (Integer position : group.positions()) {
                result.set(position, sale);
            }
        }
        return result;
    }

    private Sale findExisting(Long systemClientId, SaleRequestNormalizer.RequestGroup group) {
        return saleRepository.findBySystemClientIdAndChannelAndExternalReferenceId(
                systemClientId,
                group.key().channel(),
                group.key().externalReferenceId()
        ).orElse(null);
    }

    private void resolveExisting(
            SaleRequestNormalizer.RequestGroup group,
            Sale existing,
            Map<SaleRequestNormalizer.RequestKey, Sale> resolved
    ) {
        if (!normalizer.equivalent(existing, group.request())) {
            throw new IdempotencyConflictException();
        }
        resolved.put(group.key(), existing);
    }

    private void validate(Long systemClientId, List<SaleCreateRequest> requests) {
        if (systemClientId == null || systemClientId <= 0 || requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("Dados do cliente e ao menos um item sao obrigatorios.");
        }
        for (SaleCreateRequest request : requests) {
            if (request == null || request.getProductId() == null || request.getProductId() <= 0) {
                throw new IllegalArgumentException("Produto valido e obrigatorio.");
            }
            if (request.getSystemClientId() != null && !Objects.equals(systemClientId, request.getSystemClientId())) {
                throw new IllegalArgumentException("O cliente do corpo deve coincidir com o cliente da rota.");
            }
            if (request.getQuantity() == null || request.getQuantity() <= 0) {
                throw new IllegalArgumentException("Quantidade deve ser positiva.");
            }
            BigDecimal value = request.getTotalValue();
            if (value == null || value.signum() < 0 || value.scale() > 2) {
                throw new IllegalArgumentException("Valor total deve ser nao negativo e ter no maximo duas casas decimais.");
            }
            if (request.getExternalReferenceId() == null
                    || request.getExternalReferenceId().trim().isEmpty()
                    || request.getExternalReferenceId().trim().length() > 150) {
                throw new IllegalArgumentException("Referencia externa valida e obrigatoria.");
            }
        }
    }
}
