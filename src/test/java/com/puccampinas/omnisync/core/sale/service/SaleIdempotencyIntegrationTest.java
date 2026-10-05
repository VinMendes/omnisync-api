package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(properties = "marketplace.delivery.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class SaleIdempotencyIntegrationTest {

    private static final int CONCURRENT_REPLAY_ROUNDS = 100;
    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @Autowired
    private SaleService saleService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentEquivalentRequestsReturnTheSameSaleInEveryRound() {
        long tenantId = insertTenant("equivalent-races");
        insertMercadoLivreIntegration(tenantId);

        for (int round = 0; round < CONCURRENT_REPLAY_ROUNDS; round++) {
            int roundNumber = round;
            long productId = insertProduct(tenantId, "EQUIVALENT-" + round, 10, 1, true);
            String reference = "EQUIVALENT-REF-" + round;

            List<Attempt> attempts = runTogether(
                    () -> register(tenantId, productId, reference, SaleChannel.MANUAL,
                            new BigDecimal("20.0"), Map.of("origin", "STORE", "round", roundNumber)),
                    () -> register(tenantId, productId, reference, SaleChannel.MANUAL,
                            new BigDecimal("20.00"), Map.of("round", roundNumber, "origin", "STORE"))
            );

            assertThat(attempts)
                    .as("round %s must return a successful response to both callers", round)
                    .allSatisfy(attempt -> {
                        assertThat(attempt.failure()).isNull();
                        assertThat(attempt.sales()).hasSize(1);
                    });
            assertThat(attempts.stream().map(attempt -> attempt.sales().getFirst().getId()).distinct())
                    .as("round %s sale identity", round)
                    .hasSize(1);
            assertThat(attempts.stream().map(attempt -> attempt.sales().getFirst().getCreatedAt()).distinct())
                    .as("round %s creation instant", round)
                    .hasSize(1);
            assertSingleEffect(productId, tenantId, 8, round + 1L);
        }
    }

    @Test
    void concurrentSameKeyForDifferentProductsReturnsOneConflictWithoutTouchingTheLoser() {
        long tenantId = insertTenant("different-product-race");
        insertMercadoLivreIntegration(tenantId);
        long firstProductId = insertProduct(tenantId, "RACE-A", 10, 0, true);
        long secondProductId = insertProduct(tenantId, "RACE-B", 10, 0, true);

        List<Attempt> attempts = runTogether(
                () -> register(tenantId, firstProductId, "SAME-KEY", SaleChannel.MANUAL,
                        new BigDecimal("20.00"), Map.of("origin", "A")),
                () -> register(tenantId, secondProductId, "SAME-KEY", SaleChannel.MANUAL,
                        new BigDecimal("20.00"), Map.of("origin", "B"))
        );

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(attempts.stream().map(Attempt::failure).filter(Objects::nonNull))
                .singleElement()
                .satisfies(failure -> assertThat(failure.getClass().getSimpleName())
                        .isEqualTo("IdempotencyConflictException"));
        assertThat(salesForReference(tenantId, SaleChannel.MANUAL, "SAME-KEY")).isEqualTo(1);
        assertThat(stock(firstProductId) + stock(secondProductId)).isEqualTo(18);
        assertThat(saleLogsForTenant(tenantId)).isEqualTo(1);
        assertThat(saleAuditsForTenant(tenantId)).isEqualTo(1);
        assertThat(outboxForTenant(tenantId)).isEqualTo(1);
    }

    @Test
    void replayReturnsTheOriginalSaleAfterProductIsInactive() {
        long tenantId = insertTenant("inactive-replay");
        insertMercadoLivreIntegration(tenantId);
        long productId = insertProduct(tenantId, "INACTIVE", 10, 0, true);
        SaleCreateRequest request = request(productId, "INACTIVE-REF", SaleChannel.MANUAL,
                new BigDecimal("20.00"), Map.of("origin", "STORE"));

        SaleDto original = saleService.create(tenantId, List.of(request)).getFirst();
        long originalLogCount = saleLogsForTenant(tenantId);
        long originalAuditCount = saleAuditsForTenant(tenantId);
        long originalOutboxCount = outboxForTenant(tenantId);
        jdbc.update("UPDATE products SET active = FALSE, name = 'Changed after sale' WHERE id = ?", productId);

        SaleDto replay = saleService.create(tenantId, List.of(request)).getFirst();

        assertThat(replay.getId()).isEqualTo(original.getId());
        assertThat(replay.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(stock(productId)).isEqualTo(8);
        assertThat(saleCount(productId)).isEqualTo(1);
        assertThat(saleLogsForTenant(tenantId)).isEqualTo(originalLogCount);
        assertThat(saleAuditsForTenant(tenantId)).isEqualTo(originalAuditCount);
        assertThat(outboxForTenant(tenantId)).isEqualTo(originalOutboxCount);
    }

    @Test
    void sameReferenceIsIndependentAcrossChannelsAndTenants() {
        long firstTenantId = insertTenant("isolation-a");
        long secondTenantId = insertTenant("isolation-b");
        insertMercadoLivreIntegration(firstTenantId);
        insertMercadoLivreIntegration(secondTenantId);
        long firstProductId = insertProduct(firstTenantId, "ISOLATION-A", 10, 0, true);
        long secondProductId = insertProduct(secondTenantId, "ISOLATION-B", 10, 0, true);

        SaleDto firstManual = register(firstTenantId, firstProductId, "SHARED-REF", SaleChannel.MANUAL,
                new BigDecimal("20.00"), Map.of("origin", "STORE")).sales().getFirst();
        SaleDto firstPhysical = register(firstTenantId, firstProductId, "SHARED-REF", SaleChannel.PHYSICAL,
                new BigDecimal("20.00"), Map.of("origin", "STORE")).sales().getFirst();
        SaleDto secondManual = register(secondTenantId, secondProductId, "SHARED-REF", SaleChannel.MANUAL,
                new BigDecimal("20.00"), Map.of("origin", "STORE")).sales().getFirst();

        assertThat(firstManual.getId()).isNotEqualTo(firstPhysical.getId());
        assertThat(firstManual.getId()).isNotEqualTo(secondManual.getId());
        assertThat(firstManual.getChannel()).isEqualTo(SaleChannel.MANUAL.name());
        assertThat(firstPhysical.getChannel()).isEqualTo(SaleChannel.PHYSICAL.name());
        assertThat(stock(firstProductId)).isEqualTo(6);
        assertThat(stock(secondProductId)).isEqualTo(8);
        assertThat(salesForTenant(firstTenantId)).isEqualTo(2);
        assertThat(salesForTenant(secondTenantId)).isEqualTo(1);
        assertThat(outboxForTenant(firstTenantId)).isEqualTo(2);
        assertThat(outboxForTenant(secondTenantId)).isEqualTo(1);
    }

    private Attempt register(
            long tenantId,
            long productId,
            String reference,
            SaleChannel channel,
            BigDecimal totalValue,
            Map<String, Object> resource
    ) {
        try {
            return new Attempt(
                    saleService.create(tenantId, List.of(request(productId, reference, channel, totalValue, resource))),
                    null
            );
        } catch (Throwable failure) {
            return new Attempt(List.of(), failure);
        }
    }

    private SaleCreateRequest request(
            long productId,
            String reference,
            SaleChannel channel,
            BigDecimal totalValue,
            Map<String, Object> resource
    ) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setProductId(productId);
        request.setQuantity(2);
        request.setTotalValue(totalValue);
        request.setChannel(channel);
        request.setExternalReferenceId(reference);
        request.setResource(resource);
        return request;
    }

    @SafeVarargs
    private final <T> List<T> runTogether(Callable<T>... operations) {
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!ready.await(5, TimeUnit.SECONDS) || !start.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent callers did not reach the start barrier");
                    }
                    return operation.call();
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } catch (Exception exception) {
            throw new AssertionError("Concurrent operations did not make progress", exception);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThatCode(() -> executor.awaitTermination(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
        }
    }

    private void assertSingleEffect(long productId, long tenantId, int expectedStock, long expectedTenantEffects) {
        assertThat(stock(productId)).isEqualTo(expectedStock);
        assertThat(saleCount(productId)).isEqualTo(1);
        assertThat(saleLogsForTenant(tenantId)).isEqualTo(expectedTenantEffects);
        assertThat(saleAuditsForTenant(tenantId)).isEqualTo(expectedTenantEffects);
        assertThat(outboxForTenant(tenantId)).isEqualTo(expectedTenantEffects);
    }

    private long insertTenant(String label) {
        long suffix = FIXTURE_SEQUENCE.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Sale idempotency " + label,
                "sale-idempotency-" + suffix
        );
    }

    private void insertMercadoLivreIntegration(long tenantId) {
        jdbc.update("""
                INSERT INTO marketplace_integrations(
                    system_client_id, marketplace, access_token, refresh_token,
                    expires_at, resource, active
                ) VALUES (?, 'MERCADO_LIVRE', 'encrypted-test-token', NULL,
                          NOW() + INTERVAL '1 hour',
                          jsonb_build_object('user_id', ?), TRUE)
                """, tenantId, String.valueOf(tenantId));
    }

    private long insertProduct(
            long tenantId,
            String sku,
            int stock,
            int reservedStock,
            boolean active
    ) {
        long suffix = FIXTURE_SEQUENCE.incrementAndGet();
        return jdbc.queryForObject("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    minimum_stock, price, resource, active
                ) VALUES (?, ?, 'Idempotency product', 'Idempotency product', ?, ?, 0, 10.00,
                          jsonb_build_object('mercado_livre', jsonb_build_object('item_id', ?)), ?)
                RETURNING id
                """, Long.class, tenantId, sku + "-" + suffix, stock, reservedStock, "MLB-" + suffix, active);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private long saleCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE product_id = ?", Long.class, productId);
    }

    private long salesForReference(long tenantId, SaleChannel channel, String reference) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales
                 WHERE system_client_id = ? AND channel = ? AND external_reference_id = ?
                """, Long.class, tenantId, channel.name(), reference);
    }

    private long salesForTenant(long tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE system_client_id = ?", Long.class, tenantId);
    }

    private long saleLogsForTenant(long tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales_logs WHERE system_client_id = ?", Long.class, tenantId);
    }

    private long saleAuditsForTenant(long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_logs
                 WHERE system_client_id = ? AND entity_type = 'SALE'
                """, Long.class, tenantId);
    }

    private long outboxForTenant(long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM marketplace_stock_sync_outbox
                 WHERE system_client_id = ?
                """, Long.class, tenantId);
    }

    private record Attempt(List<SaleDto> sales, Throwable failure) {
        boolean succeeded() {
            return failure == null;
        }
    }
}
