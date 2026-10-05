package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SaleConcurrencyIntegrationTest {

    private static final int OVERSALE_ROUNDS = 100;
    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @Autowired
    private SaleService saleService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void delayStockUpdatesToMakeTheReadWriteRaceDeterministic() {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_delay_sale_stock_update()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM pg_sleep(0.05);
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_delay_sale_stock_update_trigger
                BEFORE UPDATE OF stock ON products
                FOR EACH ROW
                WHEN (OLD.stock IS DISTINCT FROM NEW.stock)
                EXECUTE FUNCTION test_delay_sale_stock_update()
                """);

        runTogether(() -> jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
                () -> jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
    }

    @AfterAll
    void removeStockUpdateDelay() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_delay_sale_stock_update_trigger ON products");
        jdbc.execute("DROP FUNCTION IF EXISTS test_delay_sale_stock_update()");
    }

    @Test
    void oneOfTwoConcurrentSalesWinsInEveryRoundWithoutOversellingReservedStock() {
        for (int round = 0; round < OVERSALE_ROUNDS; round++) {
            int currentRound = round;
            long tenantId = insertTenant("oversell-" + round);
            long productId = insertProduct(tenantId, "OVERSELL-" + round, 3, 1);

            List<Attempt> attempts = runTogether(
                    () -> register(tenantId, productId, "ROUND-" + currentRound + "-A"),
                    () -> register(tenantId, productId, "ROUND-" + currentRound + "-B")
            );

            List<Attempt> successes = attempts.stream().filter(Attempt::succeeded).toList();
            List<Throwable> failures = attempts.stream()
                    .map(Attempt::failure)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(successes)
                    .as("round %s must confirm exactly one sale", round)
                    .hasSize(1);
            assertThat(failures)
                    .as("round %s must reject exactly one sale", round)
                    .singleElement()
                    .satisfies(failure -> assertThat(failure.getClass().getSimpleName())
                            .isEqualTo("InsufficientStockException"));
            assertThat(stock(productId)).as("round %s stock", round).isEqualTo(1);
            assertThat(reservedStock(productId)).as("round %s reserved stock", round).isEqualTo(1);
            assertThat(saleCount(productId)).as("round %s persisted sales", round).isEqualTo(1);
        }
    }

    @Test
    void salesForDifferentProductsProgressIndependently() {
        long tenantId = insertTenant("different-products");
        long firstProductId = insertProduct(tenantId, "PRODUCT-A", 3, 1);
        long secondProductId = insertProduct(tenantId, "PRODUCT-B", 3, 1);

        List<Attempt> attempts = runTogether(
                () -> register(tenantId, firstProductId, "PRODUCT-A-REF"),
                () -> register(tenantId, secondProductId, "PRODUCT-B-REF")
        );

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.failure()).isNull();
            assertThat(attempt.sales()).hasSize(1);
        });
        assertThat(stock(firstProductId)).isEqualTo(1);
        assertThat(stock(secondProductId)).isEqualTo(1);
        assertThat(saleCount(firstProductId)).isEqualTo(1);
        assertThat(saleCount(secondProductId)).isEqualTo(1);
    }

    @Test
    void equalReferencesInDifferentTenantsProgressIndependently() {
        long firstTenantId = insertTenant("tenant-a");
        long secondTenantId = insertTenant("tenant-b");
        long firstProductId = insertProduct(firstTenantId, "TENANT-A-PRODUCT", 3, 1);
        long secondProductId = insertProduct(secondTenantId, "TENANT-B-PRODUCT", 3, 1);

        List<Attempt> attempts = runTogether(
                () -> register(firstTenantId, firstProductId, "SHARED-REFERENCE"),
                () -> register(secondTenantId, secondProductId, "SHARED-REFERENCE")
        );

        assertThat(attempts).allSatisfy(attempt -> {
            assertThat(attempt.failure()).isNull();
            assertThat(attempt.sales()).hasSize(1);
        });
        assertThat(stock(firstProductId)).isEqualTo(1);
        assertThat(stock(secondProductId)).isEqualTo(1);
        assertThat(salesForTenant(firstTenantId)).isEqualTo(1);
        assertThat(salesForTenant(secondTenantId)).isEqualTo(1);
    }

    private Attempt register(long tenantId, long productId, String externalReferenceId) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setProductId(productId);
        request.setQuantity(2);
        request.setTotalValue(new BigDecimal("20.00"));
        request.setChannel(SaleChannel.MANUAL);
        request.setExternalReferenceId(externalReferenceId);
        request.setResource(Map.of("origin", "CONCURRENCY_TEST"));

        try {
            return new Attempt(saleService.create(tenantId, List.of(request)), null);
        } catch (Throwable failure) {
            return new Attempt(List.of(), failure);
        }
    }

    @SafeVarargs
    private final <T> List<T> runTogether(java.util.concurrent.Callable<T>... operations) {
        ExecutorService executor = Executors.newFixedThreadPool(operations.length);
        CountDownLatch ready = new CountDownLatch(operations.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
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

    private long insertTenant(String label) {
        long suffix = FIXTURE_SEQUENCE.incrementAndGet();
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Sale concurrency " + label,
                "sale-concurrency-" + suffix
        );
    }

    private long insertProduct(long tenantId, String sku, int stock, int reservedStock) {
        long suffix = FIXTURE_SEQUENCE.incrementAndGet();
        return jdbc.queryForObject("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    minimum_stock, price, resource, active
                ) VALUES (?, ?, 'Concurrency product', 'Concurrency product', ?, ?, 0, 10.00, '{}'::jsonb, TRUE)
                RETURNING id
                """, Long.class, tenantId, sku + "-" + suffix, stock, reservedStock);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private int reservedStock(long productId) {
        return jdbc.queryForObject("SELECT reserved_stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private long saleCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE product_id = ?", Long.class, productId);
    }

    private long salesForTenant(long tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE system_client_id = ?", Long.class, tenantId);
    }

    private record Attempt(List<SaleDto> sales, Throwable failure) {
        boolean succeeded() {
            return failure == null;
        }
    }
}
