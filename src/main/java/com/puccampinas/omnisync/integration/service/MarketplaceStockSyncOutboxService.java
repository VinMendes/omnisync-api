package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.integration.entity.MarketplaceStockSyncOutbox;
import com.puccampinas.omnisync.integration.enums.MarketplaceDeliveryStatus;
import com.puccampinas.omnisync.integration.repository.MarketplaceStockSyncOutboxRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class MarketplaceStockSyncOutboxService {

    public static final String STOCK_UPDATE = "STOCK_UPDATE";

    private final MarketplaceStockSyncOutboxRepository repository;

    public MarketplaceStockSyncOutboxService(MarketplaceStockSyncOutboxRepository repository) {
        this.repository = repository;
    }

    public Optional<MarketplaceStockSyncOutbox> enqueueIfRequired(Sale sale, Product product) {
        if (SaleChannel.MERCADO_LIVRE.name().equals(sale.getChannel()) || mercadoLivreItemId(product).isEmpty()) {
            return Optional.empty();
        }

        MarketplaceStockSyncOutbox outbox = new MarketplaceStockSyncOutbox();
        outbox.setSystemClientId(sale.getSystemClientId());
        outbox.setSaleId(sale.getId());
        outbox.setProductId(product.getId());
        outbox.setMarketplace(Marketplace.MERCADO_LIVRE);
        outbox.setOperation(STOCK_UPDATE);
        outbox.setStatus(MarketplaceDeliveryStatus.PENDING);
        outbox.setAttemptCount(0);
        outbox.setNextAttemptAt(Instant.now());
        return Optional.of(repository.save(outbox));
    }

    public Optional<String> mercadoLivreItemId(Product product) {
        return extractMercadoLivreItemId(product);
    }

    public static Optional<String> extractMercadoLivreItemId(Product product) {
        if (product.getResource() == null) {
            return Optional.empty();
        }
        Object marketplace = product.getResource().get("mercado_livre");
        if (!(marketplace instanceof Map<?, ?> values)) {
            return Optional.empty();
        }
        Object itemId = values.get("item_id");
        if (itemId == null || String.valueOf(itemId).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(itemId));
    }
}
