package com.puccampinas.omnisync.integration;

import com.puccampinas.omnisync.core.product.service.ProductLogService;
import com.puccampinas.omnisync.core.product.service.ProductService;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.service.MercadoLivreListingService;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "mercadolivre.sync.transaction-timeout-seconds=1")
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(EmbeddedPostgresTestConfig.class)
class MercadoLivreSyncTimeoutIntegrationTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private MercadoLivreListingService listingService;
    @MockitoBean
    private ProductLogService productLogService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private SystemClientService systemClientService;

    @Test
    void reducedTotalTimeoutRollsBackAndReleasesTheTenantLock() {
        long tenant = jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES ('Timeout tenant', 'timeout-doc') RETURNING id",
                Long.class
        );
        jdbc.update("""
                INSERT INTO marketplace_integrations(
                    system_client_id, marketplace, access_token, expires_at, resource, active
                ) VALUES (?, 'MERCADO_LIVRE', 'encrypted', NOW() + INTERVAL '1 hour',
                          '{"user_id":"123"}'::jsonb, TRUE)
                """, tenant);
        jdbc.update("""
                INSERT INTO products(
                    system_client_id, sku, name, description, stock, reserved_stock,
                    price, resource, active
                ) VALUES (?, 'SKU-OLD', 'Before', 'Before', 1, 0, 10.00,
                          '{"mercado_livre":{"item_id":"MLB1"}}'::jsonb, TRUE)
                """, tenant);
        User user = new User();
        user.setSystemClientId(tenant);
        when(userService.findActiveEntityByEmail("timeout@example.invalid")).thenReturn(user);
        when(listingService.listAllClientListings(tenant)).thenAnswer(invocation -> {
            Thread.sleep(1_200);
            return Map.of("items", List.of(Map.of("body", Map.of(
                    "id", "MLB1",
                    "seller_custom_field", "SKU-NEW",
                    "title", "After",
                    "available_quantity", 2,
                    "price", 20
            ))));
        });

        assertThatThrownBy(() -> productService.syncMercadoLivreProducts("timeout@example.invalid", tenant))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM products WHERE system_client_id = ?",
                String.class,
                tenant
        )).isEqualTo("Before");
        assertThat(jdbc.queryForObject(
                "SELECT last_sync_at FROM marketplace_integrations WHERE system_client_id = ?",
                java.sql.Timestamp.class,
                tenant
        )).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)",
                Boolean.class,
                -tenant
        )).isTrue();
    }
}
