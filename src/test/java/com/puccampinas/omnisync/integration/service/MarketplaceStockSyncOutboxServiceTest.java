package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.core.sale.dto.SaleCreateRequest;
import com.puccampinas.omnisync.core.sale.dto.SaleDto;
import com.puccampinas.omnisync.core.sale.enums.SaleChannel;
import com.puccampinas.omnisync.core.sale.service.SaleService;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "marketplace.delivery.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class MarketplaceStockSyncOutboxServiceTest {

    private static final AtomicLong FIXTURE_SEQUENCE = new AtomicLong();

    @Autowired
    private SaleService saleService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private MercadoLivreClient mercadoLivreClient;

    @Test
    void saleStockLogAuditAndOutboxCommitInTheSameLocalTransaction() {
        Fixture fixture = fixture("commit-together", 9, 2);

        SaleDto created = saleService.create(fixture.tenantId(), List.of(
                request(fixture.productId(), "LOCAL-COMMIT-1", 3)
        )).getFirst();

        assertThat(created.getId()).isPositive();
        assertThat(stock(fixture.productId())).isEqualTo(6);
        assertThat(count("sales", fixture.tenantId())).isEqualTo(1);
        assertThat(count("sales_logs", fixture.tenantId())).isEqualTo(1);
        assertThat(saleAuditCount(fixture.tenantId())).isEqualTo(1);
        assertThat(count("marketplace_stock_sync_outbox", fixture.tenantId())).isEqualTo(1);
        assertThat(outboxStatus(created.getId())).isEqualTo("PENDING");
        verifyNoInteractions(mercadoLivreClient);
    }

    @Test
    void rollbackAfterRegistrationRemovesEveryLocalEffect() {
        Fixture fixture = fixture("rollback-together", 9, 2);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored -> {
            saleService.create(fixture.tenantId(), List.of(
                    request(fixture.productId(), "LOCAL-ROLLBACK-1", 3)
            ));
            throw new IntentionalRollback();
        })).isInstanceOf(IntentionalRollback.class);

        assertThat(stock(fixture.productId())).isEqualTo(9);
        assertThat(count("sales", fixture.tenantId())).isZero();
        assertThat(count("sales_logs", fixture.tenantId())).isZero();
        assertThat(saleAuditCount(fixture.tenantId())).isZero();
        assertThat(count("marketplace_stock_sync_outbox", fixture.tenantId())).isZero();
        verifyNoInteractions(mercadoLivreClient);
    }

    @Test
    void providerOutageIsOutsideTheSaleTransactionAndReplayDoesNotDuplicateEffects() {
        Fixture fixture = fixture("provider-outage", 9, 2);
        SaleCreateRequest request = request(fixture.productId(), "LOCAL-PROVIDER-DOWN-1", 3);
        when(mercadoLivreClient.updateItem(anyString(), anyString(), any()))
                .thenThrow(new ExternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider unavailable"));

        SaleDto first = saleService.create(fixture.tenantId(), List.of(request)).getFirst();
        SaleDto replay = saleService.create(fixture.tenantId(), List.of(request)).getFirst();

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(stock(fixture.productId())).isEqualTo(6);
        assertThat(count("sales", fixture.tenantId())).isEqualTo(1);
        assertThat(count("sales_logs", fixture.tenantId())).isEqualTo(1);
        assertThat(saleAuditCount(fixture.tenantId())).isEqualTo(1);
        assertThat(count("marketplace_stock_sync_outbox", fixture.tenantId())).isEqualTo(1);
        assertThat(outboxStatus(first.getId())).isEqualTo("PENDING");
        verifyNoInteractions(mercadoLivreClient);
    }

    private Fixture fixture(String label, int stock, int reservedStock) {
        long suffix = FIXTURE_SEQUENCE.incrementAndGet();
        long tenantId = jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Outbox " + label,
                "obx-" + suffix
        );
        long productId = jdbc.queryForObject("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    minimum_stock, price, resource, active
                ) VALUES (?, ?, 'Product', 'Description', ?, ?, 0, 10.00,
                          jsonb_build_object('mercado_livre', jsonb_build_object('item_id', ?)), TRUE)
                RETURNING id
                """, Long.class, tenantId, "OUTBOX-" + suffix, stock, reservedStock, "MLB" + suffix);
        return new Fixture(tenantId, productId);
    }

    private SaleCreateRequest request(long productId, String reference, int quantity) {
        SaleCreateRequest request = new SaleCreateRequest();
        request.setProductId(productId);
        request.setQuantity(quantity);
        request.setTotalValue(new BigDecimal("30.00"));
        request.setChannel(SaleChannel.MANUAL);
        request.setExternalReferenceId(reference);
        request.setResource(Map.of("origin", "PHYSICAL_STORE"));
        return request;
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private long count(String table, long tenantId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE system_client_id = ?",
                Long.class,
                tenantId
        );
    }

    private long saleAuditCount(long tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM audit_logs
                 WHERE system_client_id = ?
                   AND entity_type = 'SALE'
                   AND action = 'CREATE'
                """, Long.class, tenantId);
    }

    private String outboxStatus(long saleId) {
        return jdbc.queryForObject(
                "SELECT status FROM marketplace_stock_sync_outbox WHERE sale_id = ?",
                String.class,
                saleId
        );
    }

    private record Fixture(long tenantId, long productId) {
    }

    private static final class IntentionalRollback extends RuntimeException {
    }
}
