package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.config.MarketplaceDeliveryConfig;
import com.puccampinas.omnisync.integration.entity.MarketplaceWebhookInbox;
import com.puccampinas.omnisync.integration.enums.MarketplaceDeliveryStatus;
import com.puccampinas.omnisync.integration.repository.MarketplaceWebhookInboxRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
@ConditionalOnProperty(name = "marketplace.delivery.enabled", havingValue = "true", matchIfMissing = true)
public class MarketplaceWebhookInboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceWebhookInboxDispatcher.class);

    private final MarketplaceWebhookInboxRepository repository;
    private final MercadoLivreOrderWebhookService orderService;
    private final MarketplaceDeliveryConfig config;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final String workerId = "webhook-" + UUID.randomUUID();

    public MarketplaceWebhookInboxDispatcher(
            MarketplaceWebhookInboxRepository repository,
            MercadoLivreOrderWebhookService orderService,
            MarketplaceDeliveryConfig config,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.orderService = orderService;
        this.config = config;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = Clock.systemUTC();
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${marketplace.delivery.poll-interval-ms:1000}")
    void poll() {
        dispatchOnce(workerId);
    }

    public int dispatchOnce(String owner) {
        Instant now = clock.instant();
        List<MarketplaceWebhookInbox> claimed = transaction.execute(ignored -> {
            List<MarketplaceWebhookInbox> rows = repository.findEligibleForClaim(
                    now, now.minus(config.getLeaseDuration()), config.getBatchSize());
            rows.forEach(row -> {
                row.setStatus(MarketplaceDeliveryStatus.PROCESSING);
                row.setAttemptCount(row.getAttemptCount() + 1);
                row.setLockedAt(now);
                row.setLockedBy(owner);
                row.setUpdatedAt(now);
            });
            repository.saveAll(rows);
            return rows;
        });

        if (claimed == null) {
            return 0;
        }
        increment("attempt", claimed.size());
        claimed.forEach(this::process);
        return claimed.size();
    }

    private void process(MarketplaceWebhookInbox inbox) {
        try {
            // This call fetches the provider order before entering the local
            // stock/sale transaction boundary.
            orderService.processInbox(inbox);
            finalizeState(inbox, MarketplaceDeliveryStatus.SUCCEEDED, null, null, true);
            increment("success", 1);
            log.info("marketplace_inbox_success correlation={} tenant={} attempt={} age_ms={}",
                    inbox.getId(), inbox.getSystemClientId(), inbox.getAttemptCount(), ageMillis(inbox));
        } catch (IllegalArgumentException | EntityNotFoundException rejected) {
            finalizeState(inbox, MarketplaceDeliveryStatus.REJECTED,
                    rejected.getClass().getSimpleName(), "Webhook rejected by ownership or payload validation.", true);
            increment("rejected", 1);
            log.warn("marketplace_inbox_rejected correlation={} tenant={} code={}",
                    inbox.getId(), inbox.getSystemClientId(), rejected.getClass().getSimpleName());
        } catch (ExternalApiException providerFailure) {
            retry(inbox, providerFailure.getProviderCode(), providerFailure.getRetryAfterSeconds());
        } catch (RuntimeException transientFailure) {
            retry(inbox, transientFailure.getClass().getSimpleName(), null);
        }
    }

    private void retry(MarketplaceWebhookInbox inbox, String code, Integer retryAfterSeconds) {
        Instant now = clock.instant();
        if (inbox.getAttemptCount() >= config.getMaxAttempts()) {
            finalizeState(inbox, MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED,
                    sanitizeCode(code), "Webhook processing requires reconciliation.", false);
            increment("reconciliation", 1);
            return;
        }
        long delaySeconds;
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            delaySeconds = retryAfterSeconds;
        } else {
            long multiplier = 1L << Math.min(Math.max(inbox.getAttemptCount() - 1, 0), 30);
            long base;
            try {
                base = Math.multiplyExact(config.getInitialBackoff().toMillis(), multiplier);
            } catch (ArithmeticException ignored) {
                base = config.getMaxBackoff().toMillis();
            }
            base = Math.min(base, config.getMaxBackoff().toMillis());
            double sample = ThreadLocalRandom.current().nextDouble();
            double factor = 1d - config.getJitterFactor() + 2d * config.getJitterFactor() * sample;
            delaySeconds = Math.max(1, Math.round(base * factor / 1000d));
        }
        inbox.setNextAttemptAt(now.plusSeconds(delaySeconds));
        finalizeState(inbox, MarketplaceDeliveryStatus.RETRY_WAIT,
                sanitizeCode(code), "Webhook processing failed; retry state recorded.", false);
        increment("retry", 1);
        log.warn("marketplace_inbox_retry correlation={} tenant={} attempt={} delay_s={}",
                inbox.getId(), inbox.getSystemClientId(), inbox.getAttemptCount(), delaySeconds);
    }

    private void finalizeState(
            MarketplaceWebhookInbox inbox,
            MarketplaceDeliveryStatus status,
            String code,
            String message,
            boolean completed
    ) {
        Instant now = clock.instant();
        transaction.executeWithoutResult(ignored -> {
            MarketplaceWebhookInbox managed = repository.findById(inbox.getId()).orElse(inbox);
            managed.setStatus(status);
            managed.setLastErrorCode(code);
            managed.setLastErrorMessage(message);
            managed.setLockedAt(null);
            managed.setLockedBy(null);
            managed.setUpdatedAt(now);
            if (completed) {
                managed.setCompletedAt(now);
            }
            if (status == MarketplaceDeliveryStatus.RECONCILIATION_REQUIRED) {
                managed.setNextAttemptAt(now);
            } else if (status == MarketplaceDeliveryStatus.RETRY_WAIT) {
                managed.setNextAttemptAt(inbox.getNextAttemptAt());
            }
            repository.save(managed);
            copyState(managed, inbox);
        });
    }

    private void copyState(MarketplaceWebhookInbox source, MarketplaceWebhookInbox target) {
        target.setStatus(source.getStatus());
        target.setNextAttemptAt(source.getNextAttemptAt());
        target.setLockedAt(source.getLockedAt());
        target.setLockedBy(source.getLockedBy());
        target.setCompletedAt(source.getCompletedAt());
        target.setLastErrorCode(source.getLastErrorCode());
        target.setLastErrorMessage(source.getLastErrorMessage());
        target.setUpdatedAt(source.getUpdatedAt());
    }

    private String sanitizeCode(String code) {
        if (code == null || code.isBlank()) {
            return "PROVIDER_ERROR";
        }
        String sanitized = code.replaceAll("[^A-Za-z0-9_.-]", "_");
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }

    private long ageMillis(MarketplaceWebhookInbox inbox) {
        Instant created = inbox.getCreatedAt() == null ? clock.instant() : inbox.getCreatedAt();
        return Math.max(0, Duration.between(created, clock.instant()).toMillis());
    }

    private void increment(String outcome, double amount) {
        meterRegistry.counter("omnisync.marketplace.webhook.processing", "outcome", outcome).increment(amount);
    }
}
