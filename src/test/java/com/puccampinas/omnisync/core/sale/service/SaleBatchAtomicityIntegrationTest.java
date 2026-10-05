package com.puccampinas.omnisync.core.sale.service;

import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "marketplace.delivery.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class SaleBatchAtomicityIntegrationTest {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired SaleService saleService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void lateInsufficientItemRollsBackEveryEffectFromEarlierItems() {
        Fixture fixture = fixture(10, 1);

        assertThatThrownBy(() -> saleService.create(fixture.tenant(), List.of(
                request(fixture.firstProduct(), "BATCH-OK", 2),
                request(fixture.secondProduct(), "BATCH-FAIL", 2)
        ))).isInstanceOf(InsufficientStockException.class);

        assertThat(stock(fixture.firstProduct())).isEqualTo(10);
        assertThat(stock(fixture.secondProduct())).isEqualTo(1);
        assertThat(count("sales", fixture.tenant())).isZero();
        assertThat(count("sales_logs", fixture.tenant())).isZero();
        assertThat(count("audit_logs", fixture.tenant())).isZero();
        assertThat(count("marketplace_stock_sync_outbox", fixture.tenant())).isZero();
    }

    @Test
    void mixedReplayNewAndDuplicatePositionsReturnOriginalOrderWithSingleEffects() {
        Fixture fixture = fixture(10, 10);
        SaleCreateRequest replayRequest = request(fixture.firstProduct(), "EXISTING", 2);
        SaleDto existing = saleService.create(fixture.tenant(), List.of(replayRequest)).getFirst();
        SaleCreateRequest newRequest = request(fixture.secondProduct(), "NEW", 3);

        List<SaleDto> result = saleService.create(fixture.tenant(), List.of(
                newRequest,
                replayRequest,
                newRequest,
                replayRequest
        ));

        assertThat(result).hasSize(4);
        assertThat(result.get(0).getId()).isEqualTo(result.get(2).getId());
        assertThat(result.get(1).getId()).isEqualTo(existing.getId()).isEqualTo(result.get(3).getId());
        assertThat(result.get(0).getExternalReferenceId()).isEqualTo("NEW");
        assertThat(result.get(1).getExternalReferenceId()).isEqualTo("EXISTING");
        assertThat(stock(fixture.firstProduct())).isEqualTo(8);
        assertThat(stock(fixture.secondProduct())).isEqualTo(7);
        assertThat(count("sales", fixture.tenant())).isEqualTo(2);
        assertThat(count("sales_logs", fixture.tenant())).isEqualTo(2);
        assertThat(count("marketplace_stock_sync_outbox", fixture.tenant())).isEqualTo(2);
    }

    @Test
    void distinctKeysForSameProductAreValidatedByAggregateQuantity() {
        Fixture fixture = fixture(3, 10);

        assertThatThrownBy(() -> saleService.create(fixture.tenant(), List.of(
                request(fixture.firstProduct(), "AGG-A", 2),
                request(fixture.firstProduct(), "AGG-B", 2)
        ))).isInstanceOf(InsufficientStockException.class);

        assertThat(stock(fixture.firstProduct())).isEqualTo(3);
        assertThat(count("sales", fixture.tenant())).isZero();
    }

    private Fixture fixture(int firstStock, int secondStock) {
        long suffix = SEQUENCE.incrementAndGet();
        long tenant = jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class, "Batch atomic " + suffix, "bat-a-" + suffix);
        return new Fixture(tenant, product(tenant, "BAT-A-" + suffix, firstStock),
                product(tenant, "BAT-B-" + suffix, secondStock));
    }

    private long product(long tenant, String sku, int stock) {
        return jdbc.queryForObject("""
                INSERT INTO products(system_client_id, sku, name, description, stock, reserved_stock,
                                     minimum_stock, price, resource, active)
                VALUES (?, ?, 'Batch product', 'test', ?, 0, 0, 10.00,
                        jsonb_build_object('mercado_livre', jsonb_build_object('item_id', ?)), TRUE)
                RETURNING id
                """, Long.class, tenant, sku, stock, "MLB-" + sku);
    }

    private SaleCreateRequest request(long product, String reference, int quantity) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setProductId(product);
        request.setQuantity(quantity);
        request.setTotalValue(BigDecimal.valueOf(quantity * 10L).setScale(2));
        request.setChannel(SaleChannel.MANUAL);
        request.setExternalReferenceId(reference);
        request.setResource(Map.of("origin", "BATCH_TEST"));
        return request;
    }

    private int stock(long product) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id=?", Integer.class, product);
    }

    private long count(String table, long tenant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE system_client_id=?",
                Long.class, tenant);
    }

    private record Fixture(long tenant, long firstProduct, long secondProduct) { }
}
