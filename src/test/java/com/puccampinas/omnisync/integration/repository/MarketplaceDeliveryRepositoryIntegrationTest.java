package com.puccampinas.omnisync.integration.repository;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.entity.MarketplaceStockSyncOutbox;
import com.puccampinas.omnisync.integration.entity.MarketplaceWebhookInbox;
import com.puccampinas.omnisync.integration.enums.MarketplaceDeliveryStatus;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@Transactional
class MarketplaceDeliveryRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-04T15:00:00Z");

    @Autowired
    private MarketplaceStockSyncOutboxRepository outboxRepository;

    @Autowired
    private MarketplaceWebhookInboxRepository inboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void outboxEnforcesDeliveryIdentityAndFiltersByTenantAndProduct() {
        long firstTenant = tenant("outbox-first");
        long secondTenant = tenant("outbox-second");
        long firstProduct = product(firstTenant, "OUTBOX-1");
        long secondProduct = product(secondTenant, "OUTBOX-2");
        long firstSale = sale(firstTenant, firstProduct, "OUTBOX-SALE-1");
        long secondSale = sale(secondTenant, secondProduct, "OUTBOX-SALE-2");

        MarketplaceStockSyncOutbox first = outbox(firstTenant, firstSale, firstProduct);
        MarketplaceStockSyncOutbox second = outbox(secondTenant, secondSale, secondProduct);
        outboxRepository.saveAndFlush(first);
        outboxRepository.saveAndFlush(second);

        assertThat(outboxRepository.findAllBySystemClientIdAndProductIdOrderByIdAsc(firstTenant, firstProduct))
                .extracting(MarketplaceStockSyncOutbox::getId)
                .containsExactly(first.getId());
        assertThat(outboxRepository.findAllBySystemClientIdAndProductIdOrderByIdAsc(firstTenant, secondProduct))
                .isEmpty();

        MarketplaceStockSyncOutbox duplicate = outbox(firstTenant, firstSale, firstProduct);
        assertThatThrownBy(() -> outboxRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_stock_sync_outbox_delivery");
    }

    @Test
    void outboxClaimsDueAndExpiredLeaseRowsOnlyUpToLimit() {
        long tenant = tenant("outbox-claim");
        long product = product(tenant, "OUTBOX-CLAIM");

        MarketplaceStockSyncOutbox pendingDue = outbox(tenant, sale(tenant, product, "OUTBOX-PENDING"), product);
        pendingDue.setNextAttemptAt(NOW.minusSeconds(1));
        MarketplaceStockSyncOutbox retryDue = outbox(tenant, sale(tenant, product, "OUTBOX-RETRY"), product);
        retryDue.setStatus(MarketplaceDeliveryStatus.RETRY_WAIT);
        retryDue.setNextAttemptAt(NOW);
        MarketplaceStockSyncOutbox expiredLease = outbox(tenant, sale(tenant, product, "OUTBOX-EXPIRED"), product);
        expiredLease.setStatus(MarketplaceDeliveryStatus.PROCESSING);
        expiredLease.setLockedAt(NOW.minusSeconds(61));
        expiredLease.setLockedBy("dead-worker");
        MarketplaceStockSyncOutbox future = outbox(tenant, sale(tenant, product, "OUTBOX-FUTURE"), product);
        future.setNextAttemptAt(NOW.plusSeconds(1));
        MarketplaceStockSyncOutbox activeLease = outbox(tenant, sale(tenant, product, "OUTBOX-ACTIVE"), product);
        activeLease.setStatus(MarketplaceDeliveryStatus.PROCESSING);
        activeLease.setLockedAt(NOW.minusSeconds(30));
        activeLease.setLockedBy("live-worker");
        MarketplaceStockSyncOutbox succeeded = outbox(tenant, sale(tenant, product, "OUTBOX-DONE"), product);
        succeeded.setStatus(MarketplaceDeliveryStatus.SUCCEEDED);
        succeeded.setCompletedAt(NOW.minusSeconds(5));

        outboxRepository.saveAllAndFlush(java.util.List.of(
                pendingDue, retryDue, expiredLease, future, activeLease, succeeded
        ));

        assertThat(outboxRepository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 2))
                .extracting(MarketplaceStockSyncOutbox::getId)
                .containsExactly(pendingDue.getId(), retryDue.getId());
        assertThat(outboxRepository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 10))
                .extracting(MarketplaceStockSyncOutbox::getId)
                .containsExactly(pendingDue.getId(), retryDue.getId(), expiredLease.getId());
    }

    @Test
    void inboxEnforcesEventIdentityAndClaimsOnlyTenantEligibleRows() {
        long firstTenant = tenant("inbox-first");
        long secondTenant = tenant("inbox-second");

        MarketplaceWebhookInbox received = inbox(firstTenant, key('a'));
        received.setNextAttemptAt(NOW.minusSeconds(1));
        MarketplaceWebhookInbox expiredLease = inbox(firstTenant, key('b'));
        expiredLease.setStatus(MarketplaceDeliveryStatus.PROCESSING);
        expiredLease.setLockedAt(NOW.minusSeconds(61));
        expiredLease.setLockedBy("dead-worker");
        MarketplaceWebhookInbox future = inbox(firstTenant, key('c'));
        future.setNextAttemptAt(NOW.plusSeconds(1));
        MarketplaceWebhookInbox otherTenant = inbox(secondTenant, key('d'));
        otherTenant.setNextAttemptAt(NOW.minusSeconds(1));

        inboxRepository.saveAllAndFlush(java.util.List.of(received, expiredLease, future, otherTenant));

        assertThat(inboxRepository.findAllBySystemClientIdOrderByIdAsc(firstTenant))
                .extracting(MarketplaceWebhookInbox::getId)
                .containsExactly(received.getId(), expiredLease.getId(), future.getId());
        assertThat(inboxRepository.findEligibleForClaim(NOW, NOW.minusSeconds(60), 10))
                .extracting(MarketplaceWebhookInbox::getId)
                .containsExactly(received.getId(), expiredLease.getId(), otherTenant.getId());

        MarketplaceWebhookInbox duplicate = inbox(firstTenant, key('a'));
        assertThatThrownBy(() -> inboxRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_webhook_inbox_event_key");
    }

    private MarketplaceStockSyncOutbox outbox(long tenant, long sale, long product) {
        MarketplaceStockSyncOutbox delivery = new MarketplaceStockSyncOutbox();
        delivery.setSystemClientId(tenant);
        delivery.setSaleId(sale);
        delivery.setProductId(product);
        delivery.setMarketplace(Marketplace.MERCADO_LIVRE);
        delivery.setOperation("STOCK_UPDATE");
        delivery.setStatus(MarketplaceDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(NOW);
        return delivery;
    }

    private MarketplaceWebhookInbox inbox(long tenant, String eventKey) {
        MarketplaceWebhookInbox delivery = new MarketplaceWebhookInbox();
        delivery.setEventKey(eventKey);
        delivery.setProviderEventId("provider-" + eventKey.substring(0, 4));
        delivery.setMarketplace(Marketplace.MERCADO_LIVRE);
        delivery.setSystemClientId(tenant);
        delivery.setApplicationId(1000L);
        delivery.setMarketplaceUserId("seller-" + tenant);
        delivery.setTopic("orders_v2");
        delivery.setResource("/orders/" + tenant);
        delivery.setProviderSentAt(NOW.minusSeconds(5));
        delivery.setStatus(MarketplaceDeliveryStatus.RECEIVED);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(NOW);
        return delivery;
    }

    private long tenant(String suffix) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Tenant " + suffix,
                "doc-" + suffix
        );
    }

    private long product(long tenant, String sku) {
        return jdbc.queryForObject("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    minimum_stock, price, resource, active
                ) VALUES (?, ?, 'Product', 'Description', 10, 0, 0, 10.00, '{}'::jsonb, TRUE)
                RETURNING id
                """, Long.class, tenant, sku);
    }

    private long sale(long tenant, long product, String reference) {
        return jdbc.queryForObject("""
                INSERT INTO sales(
                    system_client_id, product_id, quantity, total_value, resource,
                    channel, external_reference_id, status
                ) VALUES (?, ?, 1, ?, '{}'::jsonb, 'MANUAL', ?, 'CONFIRMED')
                RETURNING id
                """, Long.class, tenant, product, BigDecimal.TEN, reference);
    }

    private String key(char character) {
        return String.valueOf(character).repeat(64);
    }
}
