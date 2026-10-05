package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.config.MarketplaceDeliveryConfig;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.integration.entity.MarketplaceStockSyncOutbox;
import com.puccampinas.omnisync.integration.enums.MarketplaceDeliveryStatus;
import com.puccampinas.omnisync.integration.repository.MarketplaceStockSyncOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.concurrent.ThreadLocalRandom;

@Service
@ConditionalOnProperty(name = "marketplace.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class MarketplaceStockSyncDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceStockSyncDispatcher.class);

    private final MarketplaceStockSyncOutboxRepository repository;
    private final ProductRepository productRepository;
    private final MercadoLivreListingService listingService;
    private final MarketplaceDeliveryConfig config;
    private final Clock clock;
    private final DoubleSupplier jitterSource;
    private final TransactionTemplate transaction;
    private final MeterRegistry meterRegistry;
    private final String workerId = "stock-" + UUID.randomUUID();

    @Autowired
    public MarketplaceStockSyncDispatcher(
            MarketplaceStockSyncOutboxRepository repository,
            ProductRepository productRepository,
            MercadoLivreListingService listingService,
            MarketplaceDeliveryConfig config,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this(repository, productRepository, listingService, config, Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(), new TransactionTemplate(transactionManager), meterRegistry);
    }

    public MarketplaceStockSyncDispatcher(
            MarketplaceStockSyncOutboxRepository repository,
            ProductRepository productRepository,
            MercadoLivreListingService listingService,
            MarketplaceDeliveryConfig config,
            Clock clock,
            DoubleSupplier jitterSource
    ) {
        this(repository, productRepository, listingService, config, clock, jitterSource, null, null);
    }

    private MarketplaceStockSyncDispatcher(
            MarketplaceStockSyncOutboxRepository repository,
            ProductRepository productRepository,
            MercadoLivreListingService listingService,
            MarketplaceDeliveryConfig config,
            Clock clock,
            DoubleSupplier jitterSource,
            TransactionTemplate transaction,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.productRepository = productRepository;
        this.listingService = listingService;
        this.config = config;
        this.clock = clock;
        this.jitterSource = jitterSource;
        this.transaction = transaction;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${marketplace.delivery.poll-interval-ms:1000}")
    void poll() {
        dispatchOnce(workerId);
    }

    public int dispatchOnce(String owner) {
        Instant now = clock.instant();
        List<MarketplaceStockSyncOutbox> claimed = inTransaction(() -> {
            List<MarketplaceStockSyncOutbox> rows = repository.findEligibleForClaim(
                    now,
                    now.minus(config.getLeaseDuration()),
                    config.getBatchSize()
            );
            for (MarketplaceStockSyncOutbox row : rows) {
                row.setStatus(MarketplaceDeliveryStatus.PROCESSING);
                row.setAttemptCount(row.getAttemptCount() + 1);
                row.setLastAttemptAt(now);
                row.setLockedAt(now);
                row.setLockedBy(owner);
                row.setUpdatedAt(now);
            }
            repository.saveAll(rows);
            return rows;
        });

        Map<ProductKey, List<MarketplaceStockSyncOutbox>> groups = new LinkedHashMap<>();
        increment("attempt", claimed.size());
        for (MarketplaceStockSyncOutbox row : claimed) {
            groups.computeIfAbsent(new ProductKey(row.getSystemClientId(), row.getProductId()), ignored -> new ArrayList<>())
                    .add(row);
        }

        for (Map.Entry<ProductKey, List<MarketplaceStockSyncOutbox>> entry : groups.entrySet()) {
            if (transaction == null) {
                processGroup(entry.getKey(), entry.getValue());
            } else {
                transaction.executeWithoutResult(ignored -> processGroup(entry.getKey(), entry.getValue()));
            }
        }
        return claimed.size();
    }

    private void processGroup(ProductKey key, List<MarketplaceStockSyncOutbox> rows) {
        try {
            repository.acquireProductAdvisoryLock(key.systemClientId(), key.productId());
            Optional<Product> current = productRepository.findByIdAndSystemClientIdAndActiveTrue(
                    key.productId(), key.systemClientId());
            if (current.isEmpty()) {
                reconcile(rows, "PRODUCT_UNAVAILABLE");
                return;
            }
            Product product = current.get();
            String itemId = MarketplaceStockSyncOutboxService.extractMercadoLivreItemId(product).orElse(null);
            if (itemId == null) {
                reconcile(rows, "LISTING_UNAVAILABLE");
                return;
            }

            int available = Math.max(0, product.getStock() - product.getReservedStock());
            listingService.updateAvailableQuantity(key.systemClientId(), itemId, available);
            succeed(rows);
            increment("success", rows.size());
            log.info("marketplace_outbox_success tenant={} product={} deliveries={} age_ms={}",
                    key.systemClientId(), key.productId(), rows.size(), ageMillis(rows));
        } catch (ExternalApiException exception) {
            retryOrReconcile(rows, exception.getProviderCode(), exception.getRetryAfterSeconds());
        } catch (RuntimeException exception) {
            retryOrReconcile(rows, exception.getClass().getSimpleName(), null);
        }
    }

    private void succeed(List<MarketplaceStockSyncOutbox> rows) {
        Instant now = clock.instant();
        rows.forEach(row -> {
            row.setStatus(MarketplaceDeliveryStatus.SUCCEEDED);
            row.setCompletedAt(now);
            clearLease(row);
            row.setLastErrorCode(null);
            row.setLastErrorMessage(null);
            row.setUpdatedAt(now);
        });
        repository.saveAll(rows);
    }

    private void retryOrReconcile(
            List<MarketplaceStockSyncOutbox> rows,
            String providerCode,
            Integer retryAfterSeconds
    ) {
        Instant now = clock.instant();
        for (MarketplaceStockSyncOutbox row : rows) {
            if (row.getAttemptCount() >= config.getMaxAttempts()) {
                row.setStatus(MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED);
                row.setNextAttemptAt(now);
            } else {
                row.setStatus(MarketplaceDeliveryStatus.RETRY_WAIT);
                row.setNextAttemptAt(now.plus(retryDelay(
                        row.getAttemptCount(), retryAfterSeconds, jitterSource.getAsDouble())));
            }
            row.setLastErrorCode(sanitizeCode(providerCode));
            row.setLastErrorMessage("Marketplace delivery failed; retry state recorded.");
            row.setUpdatedAt(now);
            clearLease(row);
        }
        repository.saveAll(rows);
        long reconciliationCount = rows.stream()
                .filter(row -> row.getStatus() == MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED)
                .count();
        increment("reconciliation", reconciliationCount);
        increment("retry", rows.size() - reconciliationCount);
        log.warn("marketplace_outbox_retry tenant={} product={} deliveries={} terminal={}",
                rows.getFirst().getSystemClientId(), rows.getFirst().getProductId(), rows.size(),
                rows.stream().allMatch(row -> row.getStatus() == MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED));
    }

    private void reconcile(List<MarketplaceStockSyncOutbox> rows, String code) {
        Instant now = clock.instant();
        rows.forEach(row -> {
            row.setStatus(MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED);
            row.setNextAttemptAt(now);
            row.setLastErrorCode(code);
            row.setLastErrorMessage("Marketplace delivery requires reconciliation.");
            row.setUpdatedAt(now);
            clearLease(row);
        });
        repository.saveAll(rows);
        increment("reconciliation", rows.size());
    }

    Duration retryDelay(int attempt, Integer retryAfterSeconds, double sample) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            return Duration.ofSeconds(retryAfterSeconds);
        }
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 30);
        long baseMillis;
        try {
            baseMillis = Math.multiplyExact(config.getInitialBackoff().toMillis(), multiplier);
        } catch (ArithmeticException ignored) {
            baseMillis = config.getMaxBackoff().toMillis();
        }
        baseMillis = Math.min(baseMillis, config.getMaxBackoff().toMillis());
        double bounded = Math.max(0d, Math.min(1d, sample));
        double jitter = 1d - config.getJitterFactor() + (2d * config.getJitterFactor() * bounded);
        return Duration.ofMillis(Math.round(baseMillis * jitter));
    }

    private void clearLease(MarketplaceStockSyncOutbox row) {
        row.setLockedAt(null);
        row.setLockedBy(null);
    }

    private String sanitizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "PROVIDER_ERROR";
        }
        String sanitized = code.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }

    private long ageMillis(List<MarketplaceStockSyncOutbox> rows) {
        Instant oldest = rows.stream().map(MarketplaceStockSyncOutbox::getCreatedAt)
                .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(clock.instant());
        return Math.max(0, Duration.between(oldest, clock.instant()).toMillis());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        if (transaction == null) {
            return action.get();
        }
        return transaction.execute(ignored -> action.get());
    }

    private void increment(String outcome, double amount) {
        if (meterRegistry != null && amount > 0) {
            meterRegistry.counter("omnisync.marketplace.stock.delivery", "outcome", outcome).increment(amount);
        }
    }

    private record ProductKey(Long systemClientId, Long productId) { }
}
