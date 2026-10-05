package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.exception.InsufficientStockException;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "marketplace.delivery.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class SaleBatchConcurrencyIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();
    @Autowired SaleService saleService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentAggregatedBatchesCannotOversellOneProduct() {
        long tenant = tenant();
        long product = product(tenant, "AGG", 3, 1);

        List<Attempt> attempts = together(
                () -> attempt(tenant, List.of(request(product, "A-1"), request(product, "A-2"))),
                () -> attempt(tenant, List.of(request(product, "B-1"), request(product, "B-2")))
        );

        assertThat(attempts.stream().filter(Attempt::success)).hasSize(1);
        assertThat(attempts.stream().map(Attempt::failure).filter(InsufficientStockException.class::isInstance))
                .hasSize(1);
        assertThat(stock(product)).isEqualTo(1);
        assertThat(sales(tenant)).isEqualTo(2);
    }

    @Test
    void inverseProductOrderBatchesFinishWithoutDeadlock() {
        long tenant = tenant();
        long first = product(tenant, "LOCK-A", 10, 0);
        long second = product(tenant, "LOCK-B", 10, 0);

        List<Attempt> attempts = together(
                () -> attempt(tenant, List.of(request(first, "AB-A"), request(second, "AB-B"))),
                () -> attempt(tenant, List.of(request(second, "BA-B"), request(first, "BA-A")))
        );

        assertThat(attempts).allMatch(Attempt::success);
        assertThat(stock(first)).isEqualTo(8);
        assertThat(stock(second)).isEqualTo(8);
        assertThat(sales(tenant)).isEqualTo(4);
    }

    private Attempt attempt(long tenant, List<SaleCreateRequest> requests) {
        try {
            saleService.create(tenant, requests);
            return new Attempt(true, null);
        } catch (Throwable failure) {
            return new Attempt(false, failure);
        }
    }

    @SafeVarargs
    private final List<Attempt> together(Callable<Attempt>... calls) {
        CountDownLatch ready = new CountDownLatch(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(calls.length)) {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Callable<Attempt> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    ready.await(5, TimeUnit.SECONDS);
                    start.await(5, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Attempt> result = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                result.add(future.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
            }
            return result;
        } catch (Exception failure) {
            throw new AssertionError("Concurrent batches failed to finish", failure);
        }
    }

    private long tenant() {
        long suffix = SEQUENCE.incrementAndGet();
        return jdbc.queryForObject("INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class, "Batch concurrent " + suffix, "bat-c-" + suffix);
    }

    private long product(long tenant, String label, int stock, int reserved) {
        long suffix = SEQUENCE.incrementAndGet();
        return jdbc.queryForObject("""
                INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                     minimum_stock, price, resource, active)
                VALUES (?, ?, 'Concurrent product', 'test', ?, ?, 0, 10.00, '{}'::jsonb, TRUE)
                RETURNING id
                """, Long.class, tenant, label + "-" + suffix, stock, reserved);
    }

    private SaleCreateRequest request(long product, String reference) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setProductId(product);
        request.setQuantity(1);
        request.setTotalValue(new BigDecimal("10.00"));
        request.setChannel(SaleChannel.MANUAL);
        request.setExternalReferenceId(reference);
        request.setResource(Map.of("origin", "CONCURRENCY_TEST"));
        return request;
    }

    private int stock(long product) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product);
    }

    private long sales(long tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE system_client_id=?", Long.class, tenant);
    }

    private record Attempt(boolean success, Throwable failure) { }
}
