package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.common.util.OffsetLimitPageable;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.dto.SaleLogDto;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.entity.SaleLog;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.enums.SaleStatus;
import com.puccampinas.omnisync.core.sale.repository.SaleLogRepository;
import com.puccampinas.omnisync.core.sale.repository.SaleRepository;
import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

@Service
public class SaleService {

    private final SaleRepository saleRepository;
    private final SaleLogRepository saleLogRepository;
    private final ProductRepository productRepository;
    private final SaleLogService saleLogService;
    private final MercadoLivreListingService mercadoLivreListingService;

    public SaleService(SaleRepository saleRepository, SaleLogRepository saleLogRepository, ProductRepository productRepository, SaleLogService saleLogService, MercadoLivreListingService mercadoLivreListingService) {
        this.saleRepository = saleRepository;
        this.saleLogRepository = saleLogRepository;
        this.productRepository = productRepository;
        this.saleLogService = saleLogService;
        this.mercadoLivreListingService = mercadoLivreListingService;
    }

    @Transactional
    public SaleDto create(Long systemClientId, SaleCreateRequest request) {

        // Validações iniciais
        if (systemClientId == null || request == null || request.getProductId() == null) {
            throw new IllegalArgumentException("Dados do client, request e produto sao obrigatorios");
        }

        Product product = productRepository.findByIdAndSystemClientIdAndActiveTrue(
                        request.getProductId(), systemClientId)
                .orElseThrow( () -> new EntityNotFoundException("Produto nao encontrado, inativo, ou nao pertence a este cliente"));

        int availableStock = product.getStock() - product.getReservedStock();

        if (request.getQuantity() > availableStock) {
            throw new IllegalArgumentException("Estoque insuficiente. Disponivel: " + availableStock);
        }

        // Subtrair do estoque físico a quantidade vendida
        product.setStock(product.getStock() - request.getQuantity());
        Product savedProduct = productRepository.save(product);

        // Define o canal da venda com segurança
        String channel = request.getChannel() != null ? request.getChannel().name() : SaleChannel.MANUAL.name();

        // Se a venda não veio do ML, nós avisamos o ML que o estoque local caiu!
        if (!SaleChannel.MERCADO_LIVRE.name().equals(channel)) {
            String mlItemId = getMercadoLivreItemId(savedProduct);

            // Se encontrou o ID do anúncio, manda a requisição pra nuvem
            if (mlItemId != null) {
                try {
                    mercadoLivreListingService.updateListing(
                            systemClientId,
                            savedProduct.getId(),
                            mlItemId,
                            savedProduct
                    );
                } catch (Exception ex) {
                    // BLINDAGEM DE ARQUITETURA:
                    // Se o ML cair, demorar ou recusar a atualização (ex: anúncio free),
                    // o erro morre aqui e a venda da loja física finaliza com sucesso!
                    System.out.println("Aviso: Não foi possível sincronizar o estoque no Mercado Livre para o item "
                            + mlItemId + ". Motivo: " + ex.getMessage());
                }
            }
        }

        // Finalmente, montar e salvar a venda
        Sale sale = new Sale();
        sale.setSystemClientId(systemClientId);
        sale.setProductId(savedProduct.getId());
        sale.setQuantity(request.getQuantity());
        sale.setTotalValue(request.getTotalValue());
        sale.setChannel(channel);
        sale.setStatus(SaleStatus.CONFIRMED.name());
        sale.setResource(request.getResource());

        Sale savedSale = saleRepository.save(sale);

        // saleLogService.logCreate(savedSale);

        return toDto(savedSale, false);
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

    private String getMercadoLivreItemId(Product product) {
        if (product.getResource() == null) {
            return null;
        }

        Object mercadoLivre = product.getResource().get("mercado_livre");
        if (mercadoLivre instanceof java.util.Map<?, ?> mlMap) {
            Object itemId = mlMap.get("item_id");
            if (itemId != null && !String.valueOf(itemId).isBlank()) {
                return String.valueOf(itemId);
            }
        }
        return null;
    }
}
