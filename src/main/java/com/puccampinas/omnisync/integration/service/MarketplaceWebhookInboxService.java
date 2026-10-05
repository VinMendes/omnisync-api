package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.dto.MercadoLivreNotificationRequest;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.entity.MarketplaceWebhookInbox;
import com.puccampinas.omnisync.integration.enums.MarketplaceDeliveryStatus;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.puccampinas.omnisync.integration.repository.MarketplaceWebhookInboxRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Map;

@Service
public class MarketplaceWebhookInboxService {

    private final MarketplaceWebhookInboxRepository repository;
    private final MarketplaceIntegrationRepository integrationRepository;
    private final MeterRegistry meterRegistry;

    public MarketplaceWebhookInboxService(
            MarketplaceWebhookInboxRepository repository,
            MarketplaceIntegrationRepository integrationRepository,
            MeterRegistry meterRegistry
    ) {
        this.repository = repository;
        this.integrationRepository = integrationRepository;
        this.meterRegistry = meterRegistry;
    }

    public Map<String, Object> receive(MercadoLivreNotificationRequest notification) {
        validate(notification);
        MarketplaceIntegration integration = integrationRepository
                .findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
                        String.valueOf(notification.userId()), Marketplace.MERCADO_LIVRE.name())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Active Mercado Livre integration not found for user_id=" + notification.userId()));

        String eventKey = eventKey(notification);
        var existing = repository.findByEventKey(eventKey);
        if (existing.isPresent()) {
            meterRegistry.counter("omnisync.marketplace.webhook.receipt", "outcome", "duplicate").increment();
            return acknowledgement(existing.get(), true);
        }

        MarketplaceWebhookInbox inbox = new MarketplaceWebhookInbox();
        inbox.setEventKey(eventKey);
        inbox.setProviderEventId(blankToNull(notification.providerEventId()));
        inbox.setMarketplace(Marketplace.MERCADO_LIVRE);
        inbox.setSystemClientId(integration.getSystemClientId());
        inbox.setApplicationId(notification.applicationId());
        inbox.setMarketplaceUserId(String.valueOf(notification.userId()));
        inbox.setTopic(notification.topic().trim());
        inbox.setResource(notification.resource().trim());
        inbox.setProviderSentAt(parseInstant(notification.sent()));
        inbox.setStatus(MarketplaceDeliveryStatus.RECEIVED);
        inbox.setAttemptCount(0);
        inbox.setNextAttemptAt(Instant.now());
        try {
            MarketplaceWebhookInbox saved = repository.saveAndFlush(inbox);
            meterRegistry.counter("omnisync.marketplace.webhook.receipt", "outcome", "accepted").increment();
            return acknowledgement(saved, false);
        } catch (DataIntegrityViolationException duplicate) {
            MarketplaceWebhookInbox winner = repository.findByEventKey(eventKey).orElseThrow(() -> duplicate);
            meterRegistry.counter("omnisync.marketplace.webhook.receipt", "outcome", "duplicate").increment();
            return acknowledgement(winner, true);
        }
    }

    private Map<String, Object> acknowledgement(MarketplaceWebhookInbox inbox, boolean duplicate) {
        return Map.of(
                "accepted", true,
                "duplicate", duplicate,
                "correlation_id", inbox.getId()
        );
    }

    private void validate(MercadoLivreNotificationRequest notification) {
        if (notification == null || notification.userId() == null || notification.applicationId() == null
                || notification.topic() == null || notification.topic().isBlank()
                || notification.resource() == null || notification.resource().isBlank()) {
            throw new IllegalArgumentException("Mercado Livre notification payload is invalid.");
        }
        if (!"orders_v2".equals(notification.topic().trim())) {
            throw new IllegalArgumentException("Mercado Livre notification topic is unsupported.");
        }
        if (blankToNull(notification.providerEventId()) == null
                && (notification.sent() == null || notification.sent().isBlank())) {
            throw new IllegalArgumentException("Mercado Livre notification requires _id or sent.");
        }
    }

    private String eventKey(MercadoLivreNotificationRequest notification) {
        String providerId = blankToNull(notification.providerEventId());
        String canonical = providerId != null
                ? "MERCADO_LIVRE|ID|" + providerId
                : String.join("|", "MERCADO_LIVRE", "LEGACY",
                        String.valueOf(notification.userId()),
                        String.valueOf(notification.applicationId()),
                        notification.topic().trim().toLowerCase(),
                        notification.resource().trim(),
                        notification.sent() == null ? "" : notification.sent().trim());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Mercado Livre notification sent timestamp is invalid.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
