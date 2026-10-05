package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.config.MarketplaceDeliveryConfig;
import com.puccampinas.omnisync.core.product.entity.Product;
import com.puccampinas.omnisync.core.product.repository.ProductRepository;
import com.puccampinas.omnisync.integration.entity.MarketplaceStockSyncOutbox;
import com.puccampinas.omnisync.integration.enums.MarketplaceDeliveryStatus;
import com.puccampinas.omnisync.integration.repository.MarketplaceStockSyncOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceStockSyncDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-04T15:00:00Z");

    private MarketplaceStockSyncOutboxRepository repository;
    private ProductRepository productRepository;
    private MercadoLivreListingService listingService;
    private MarketplaceDeliveryConfig config;
    private MarketplaceStockSyncDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        repository = mock(MarketplaceStockSyncOutboxRepository.class);
        productRepository = mock(ProductRepository.class);
        listingService = mock(MercadoLivreListingService.class);
        config = new MarketplaceDeliveryConfig();
        DoubleSupplier midpointJitter = () -> 0.5d;
        dispatcher = new MarketplaceStockSyncDispatcher(
                repository,
                productRepository,
                listingService,
                config,
                Clock.fixed(NOW, ZoneOffset.UTC),
                midpointJitter
        );
    }

    @Test
    void defaultsMatchTheDeliveryContractAndScheduledPollRunsEverySecond() throws Exception {
        assertThat(config.getPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.getBatchSize()).isEqualTo(50);
        assertThat(config.getLeaseDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getMaxAttempts()).isEqualTo(8);
        assertThat(config.getInitialBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getMaxBackoff()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.getJitterFactor()).isEqualTo(0.20d);

        Method poll = MarketplaceStockSyncDispatcher.class.getDeclaredMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${marketplace.delivery.poll-interval-ms:1000}");
    }

    @Test
    void claimsWithSkipLockedBatchAndLeaseCutoffThenSerializesByTenantAndProduct() {
        MarketplaceStockSyncOutbox first = delivery(1L, 11L, 101L, MarketplaceDeliveryStatus.PENDING, 0);
        MarketplaceStockSyncOutbox second = delivery(2L, 11L, 101L, MarketplaceDeliveryStatus.PENDING, 0);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50))
                .thenReturn(List.of(first, second));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(101L, 11L))
                .thenReturn(Optional.of(product(101L, 11L, 12, 3)));

        int processed = dispatcher.dispatchOnce("worker-a");

        assertThat(processed).isEqualTo(2);
        verify(repository).findEligibleForClaim(NOW, NOW.minusSeconds(60), 50);
        verify(repository).acquireProductAdvisoryLock(11L, 101L);
        verify(listingService).updateAvailableQuantity(11L, "MLB101", 9);
        assertThat(first.getStatus()).isEqualTo(MarketplaceDeliveryStatus.SUCCEEDED);
        assertThat(second.getStatus()).isEqualTo(MarketplaceDeliveryStatus.SUCCEEDED);
        assertThat(first.getCompletedAt()).isEqualTo(NOW);
        assertThat(second.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void reloadsCurrentAvailabilityInsteadOfPublishingTheSaleTimeSnapshot() {
        MarketplaceStockSyncOutbox delivery = delivery(3L, 22L, 202L, MarketplaceDeliveryStatus.PENDING, 0);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(List.of(delivery));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(202L, 22L))
                .thenReturn(Optional.of(product(202L, 22L, 11, 4)));

        dispatcher.dispatchOnce("worker-current-stock");

        verify(productRepository).findByIdAndSystemClientIdAndActiveTrue(202L, 22L);
        verify(listingService).updateAvailableQuantity(22L, "MLB202", 7);
    }

    @Test
    void coalescesEligibleRowsForTheSameProductIntoOneIdempotentProviderCommand() {
        MarketplaceStockSyncOutbox older = delivery(4L, 33L, 303L, MarketplaceDeliveryStatus.PENDING, 0);
        MarketplaceStockSyncOutbox newer = delivery(5L, 33L, 303L, MarketplaceDeliveryStatus.RETRY_WAIT, 1);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50))
                .thenReturn(List.of(older, newer));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(303L, 33L))
                .thenReturn(Optional.of(product(303L, 33L, 8, 2)));

        dispatcher.dispatchOnce("worker-coalesce");

        verify(listingService).updateAvailableQuantity(33L, "MLB303", 6);
        assertThat(older.getStatus()).isEqualTo(MarketplaceDeliveryStatus.SUCCEEDED);
        assertThat(newer.getStatus()).isEqualTo(MarketplaceDeliveryStatus.SUCCEEDED);
        assertThat(older.getCompletedAt()).isEqualTo(NOW);
        assertThat(newer.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void expiredLeaseIsRecoveredAndProcessedByAnotherWorker() {
        MarketplaceStockSyncOutbox expired = delivery(6L, 44L, 404L, MarketplaceDeliveryStatus.PROCESSING, 2);
        expired.setLockedAt(NOW.minusSeconds(61));
        expired.setLockedBy("dead-worker");
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(List.of(expired));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(404L, 44L))
                .thenReturn(Optional.of(product(404L, 44L, 5, 1)));

        dispatcher.dispatchOnce("replacement-worker");

        assertThat(expired.getStatus()).isEqualTo(MarketplaceDeliveryStatus.SUCCEEDED);
        assertThat(expired.getLockedAt()).isNull();
        assertThat(expired.getLockedBy()).isNull();
        assertThat(expired.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void transientFailureUsesExponentialBackoffAndTwentyPercentJitter() {
        MarketplaceStockSyncOutbox delivery = delivery(7L, 55L, 505L, MarketplaceDeliveryStatus.PENDING, 2);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(List.of(delivery));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(505L, 55L))
                .thenReturn(Optional.of(product(505L, 55L, 9, 0)));
        when(listingService.updateAvailableQuantity(55L, "MLB505", 9))
                .thenThrow(new ExternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider unavailable"));

        dispatcher.dispatchOnce("worker-retry");

        assertThat(delivery.getAttemptCount()).isEqualTo(3);
        assertThat(delivery.getStatus()).isEqualTo(MarketplaceDeliveryStatus.RETRY_WAIT);
        // Third attempt: 5s * 2^(3-1) = 20s. A midpoint random sample means zero jitter.
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(delivery.getLastErrorMessage()).doesNotContain("provider unavailable");
    }

    @Test
    void retryAfterOverridesCalculatedBackoff() {
        MarketplaceStockSyncOutbox delivery = delivery(8L, 66L, 606L, MarketplaceDeliveryStatus.PENDING, 0);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(List.of(delivery));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(606L, 66L))
                .thenReturn(Optional.of(product(606L, 66L, 7, 1)));
        when(listingService.updateAvailableQuantity(66L, "MLB606", 6))
                .thenThrow(new ExternalApiException(HttpStatus.TOO_MANY_REQUESTS, "rate limited", "too_many_requests", 73));

        dispatcher.dispatchOnce("worker-rate-limit");

        assertThat(delivery.getStatus()).isEqualTo(MarketplaceDeliveryStatus.RETRY_WAIT);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(73));
    }

    @Test
    void eighthFailedAttemptStopsAutomaticRetriesForReconciliation() {
        MarketplaceStockSyncOutbox delivery = delivery(9L, 77L, 707L, MarketplaceDeliveryStatus.RETRY_WAIT, 7);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(List.of(delivery));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(707L, 77L))
                .thenReturn(Optional.of(product(707L, 77L, 4, 0)));
        when(listingService.updateAvailableQuantity(77L, "MLB707", 4))
                .thenThrow(new ExternalApiException(HttpStatus.BAD_GATEWAY, "still unavailable"));

        dispatcher.dispatchOnce("worker-terminal");

        assertThat(delivery.getAttemptCount()).isEqualTo(8);
        assertThat(delivery.getStatus()).isEqualTo(MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(delivery.getLockedAt()).isNull();
        assertThat(delivery.getLockedBy()).isNull();
    }

    @Test
    void retryDelayIsCappedAtFifteenMinutesBeforeJitterAndNeverExceedsTwentyPercentBand() {
        Duration minimum = dispatcher.retryDelay(20, null, 0.0d);
        Duration midpoint = dispatcher.retryDelay(20, null, 0.5d);
        Duration maximum = dispatcher.retryDelay(20, null, 1.0d);

        assertThat(minimum).isEqualTo(Duration.ofMinutes(12));
        assertThat(midpoint).isEqualTo(Duration.ofMinutes(15));
        assertThat(maximum).isEqualTo(Duration.ofMinutes(18));
    }

    @Test
    void missingOrInactiveProductRequiresReconciliationWithoutProviderIo() {
        MarketplaceStockSyncOutbox delivery = delivery(10L, 88L, 808L, MarketplaceDeliveryStatus.PENDING, 0);
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(List.of(delivery));
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(808L, 88L)).thenReturn(Optional.empty());

        dispatcher.dispatchOnce("worker-missing-product");

        assertThat(delivery.getStatus()).isEqualTo(MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED);
        verify(listingService, never()).updateAvailableQuantity(any(), any(), any(Integer.class));
    }

    @Test
    void controlledFullBatchMeetsTheNinetyFivePercentWithinSixtySecondsTarget() {
        List<MarketplaceStockSyncOutbox> batch = LongStream.rangeClosed(1, 50)
                .mapToObj(id -> delivery(1_000L + id, 99L, 909L,
                        MarketplaceDeliveryStatus.PENDING, 0))
                .toList();
        when(repository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 50)).thenReturn(batch);
        when(productRepository.findByIdAndSystemClientIdAndActiveTrue(909L, 99L))
                .thenReturn(Optional.of(product(909L, 99L, 100, 5)));

        long started = System.nanoTime();
        int processed = dispatcher.dispatchOnce("worker-performance");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(processed).isEqualTo(50);
        assertThat(batch.stream().filter(row -> row.getStatus() == MarketplaceDeliveryStatus.SUCCEEDED).count())
                .isGreaterThanOrEqualTo(48);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
        verify(listingService).updateAvailableQuantity(99L, "MLB909", 95);
    }

    private MarketplaceStockSyncOutbox delivery(
            long id,
            long tenantId,
            long productId,
            MarketplaceDeliveryStatus status,
            int attempts
    ) {
        MarketplaceStockSyncOutbox delivery = new MarketplaceStockSyncOutbox();
        delivery.setId(id);
        delivery.setSystemClientId(tenantId);
        delivery.setSaleId(10_000L + id);
        delivery.setProductId(productId);
        delivery.setMarketplace(Marketplace.MERCADO_LIVRE);
        delivery.setOperation(MarketplaceStockSyncOutboxService.STOCK_UPDATE);
        delivery.setStatus(status);
        delivery.setAttemptCount(attempts);
        delivery.setNextAttemptAt(NOW);
        return delivery;
    }

    private Product product(long id, long tenantId, int stock, int reservedStock) {
        Product product = new Product();
        product.setId(id);
        product.setSystemClientId(tenantId);
        product.setSku("SKU-" + id);
        product.setName("Product " + id);
        product.setDescription("Dispatcher test product");
        product.setStock(stock);
        product.setReservedStock(reservedStock);
        product.setMinimumStock(0);
        product.setPrice(java.math.BigDecimal.TEN);
        product.setActive(true);
        product.setResource(Map.of("mercado_livre", Map.of("item_id", "MLB" + id)));
        return product;
    }
}
