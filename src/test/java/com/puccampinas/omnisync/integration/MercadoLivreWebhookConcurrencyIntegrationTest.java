package com.puccampinas.omnisync.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.support.EmbeddedPostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = "mercadolivre.client-id=999")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresTestConfig.class)
class MercadoLivreWebhookConcurrencyIntegrationTest {

    private static final String WEBHOOK_PATH = "/api/integrations/mercadolivre/webhooks/orders";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void concurrentDuplicateWebhookReturnsOneDurableCorrelationWithoutDuplicatingInbox() throws Exception {
        long tenant = tenant("duplicate");
        integration(tenant, 91001L);
        String payload = notification("evt-duplicate", 91001L, "/orders/9001");

        List<MvcResult> results = postConcurrently(payload, payload);

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 200);

        List<JsonNode> responses = results.stream()
                .map(this::responseBody)
                .toList();
        assertThat(responses).allSatisfy(response ->
                assertThat(response.path("accepted").asBoolean()).isTrue()
        );
        assertThat(responses).extracting(response -> response.path("duplicate").asBoolean())
                .containsExactlyInAnyOrder(false, true);
        assertThat(responses).extracting(response -> response.path("correlation_id").asLong())
                .doesNotContain(0L)
                .containsOnly(responses.getFirst().path("correlation_id").asLong());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM marketplace_webhook_inbox WHERE provider_event_id = ?",
                Long.class,
                "evt-duplicate"
        )).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT system_client_id FROM marketplace_webhook_inbox WHERE provider_event_id = ?",
                Long.class,
                "evt-duplicate"
        )).isEqualTo(tenant);
    }

    @Test
    void simultaneousWebhooksResolveAndPersistOnlyTheirOwningTenant() throws Exception {
        long firstTenant = tenant("first");
        long secondTenant = tenant("second");
        integration(firstTenant, 92001L);
        integration(secondTenant, 92002L);

        List<MvcResult> results = postConcurrently(
                notification("evt-first", 92001L, "/orders/shared"),
                notification("evt-second", 92002L, "/orders/shared")
        );

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 200);
        assertThat(results.stream().map(this::responseBody).toList()).allSatisfy(response -> {
            assertThat(response.path("accepted").asBoolean()).isTrue();
            assertThat(response.path("duplicate").asBoolean()).isFalse();
            assertThat(response.path("correlation_id").asLong()).isPositive();
        });

        Map<String, Long> tenantsByEvent = jdbc.query(
                """
                SELECT provider_event_id, system_client_id
                  FROM marketplace_webhook_inbox
                 WHERE provider_event_id IN ('evt-first', 'evt-second')
                """,
                resultSet -> {
                    Map<String, Long> resolved = new java.util.HashMap<>();
                    while (resultSet.next()) {
                        resolved.put(resultSet.getString("provider_event_id"), resultSet.getLong("system_client_id"));
                    }
                    return resolved;
                }
        );
        assertThat(tenantsByEvent)
                .containsEntry("evt-first", firstTenant)
                .containsEntry("evt-second", secondTenant)
                .hasSize(2);
    }

    @Test
    void webhookCannotOverrideSellerOwnershipWithAClientField() throws Exception {
        long ownerTenant = tenant("owner");
        long attemptedTenant = tenant("attempted");
        integration(ownerTenant, 93001L);
        String payload = notification("evt-owner", 93001L, "/orders/owner")
                .replaceFirst("\\{", "{\"system_client_id\":" + attemptedTenant + ",");

        MvcResult result = mvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT system_client_id FROM marketplace_webhook_inbox WHERE provider_event_id='evt-owner'",
                Long.class)).isEqualTo(ownerTenant);
    }

    private List<MvcResult> postConcurrently(String firstPayload, String secondPayload) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<MvcResult> first = executor.submit(() -> postAfterBarrier(firstPayload, ready, start));
            Future<MvcResult> second = executor.submit(() -> postAfterBarrier(secondPayload, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private MvcResult postAfterBarrier(
            String payload,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent webhook start barrier timed out");
        }
        return mvc.perform(post(WEBHOOK_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private JsonNode responseBody(MvcResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook response is not valid JSON", exception);
        }
    }

    private long tenant(String suffix) {
        return jdbc.queryForObject(
                "INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class,
                "Webhook tenant " + suffix,
                "webhook-" + suffix
        );
    }

    private void integration(long tenant, long sellerId) {
        jdbc.update("""
                INSERT INTO marketplace_integrations(
                    system_client_id, marketplace, access_token, expires_at, resource, active
                ) VALUES (?, 'MERCADO_LIVRE', 'encrypted', NOW() + INTERVAL '1 hour',
                          jsonb_build_object('user_id', ?), TRUE)
                """, tenant, sellerId);
    }

    private String notification(String eventId, long sellerId, String resource) throws Exception {
        return json.writeValueAsString(Map.of(
                "_id", eventId,
                "resource", resource,
                "user_id", sellerId,
                "topic", "orders_v2",
                "application_id", 999,
                "attempts", 1,
                "sent", "2026-10-04T15:00:00Z",
                "received", "2026-10-04T15:00:01Z"
        ));
    }
}
