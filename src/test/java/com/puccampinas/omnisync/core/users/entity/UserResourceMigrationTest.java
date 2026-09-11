package com.puccampinas.omnisync.core.users.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.puccampinas.omnisync.core.users.enums.Permission;
import com.puccampinas.omnisync.core.users.enums.Role;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserResourceMigrationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldMigrateLegacyUser38AndCreateOneExclusiveRolePerUser() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).target("7").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            company(jdbc, 25L, "Empresa A");
            company(jdbc, 26L, "Empresa B");

            // Mesmo formato do registro legado informado, porém com PII e hash deliberadamente sanitizados.
            legacyUser(jdbc, 38L, 25L, "migration-user-38@example.invalid",
                    "{\"cpf\":\"00000000000\",\"role\":\"admin\",\"permissions\":["
                            + "\"Somente leitura\",\"Acesso total\",\"Faturamento\",\"Marketplaces\","
                            + "\"Atividade\",\"Vendas\",\"Gestão de estoque\",\"Gestão de usuários\",\"Anúncios\"]}",
                    2026);
            legacyUser(jdbc, 39L, 25L, "seller-a@example.invalid",
                    "{\"role\":\"editor\",\"permissions\":[\"Anúncios\",\"Vendas\"]}", 2026);
            legacyUser(jdbc, 40L, 25L, "seller-b@example.invalid",
                    "{\"role\":\"seller\",\"permissions\":[\"Anúncios\",\"Vendas\"]}", 2026);
            legacyUser(jdbc, 41L, 26L, "viewer-b@example.invalid",
                    "{\"role\":\"viewer\"}", 2026);

            Flyway flyway = Flyway.configure().dataSource(dataSource).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);

            assertThat(roleOf(jdbc, 38L)).isEqualTo(Role.ADMIN);
            // V13 does not silently grant new permissions to existing customized roles.
            assertThat(permissionsOf(jdbc, 38L)).containsExactlyInAnyOrder(
                    java.util.Arrays.stream(Permission.values()).filter(p -> p != Permission.AUDIT_READ).toArray(Permission[]::new));
            assertThat(userResource(jdbc, 38L).attributes())
                    .containsExactlyEntriesOf(Map.of("cpf", "00000000000"));

            assertThat(roleOf(jdbc, 39L)).isEqualTo(Role.SELLER);
            assertThat(permissionsOf(jdbc, 39L)).containsExactlyInAnyOrder(
                    Permission.PRODUCT_READ, Permission.LISTING_PUBLISH,
                    Permission.SALE_READ, Permission.SALE_WRITE);
            assertThat(roleOf(jdbc, 40L)).isEqualTo(Role.SELLER);
            assertThat(permissionsOf(jdbc, 40L)).containsExactlyInAnyOrder(
                    Permission.PRODUCT_READ, Permission.LISTING_PUBLISH,
                    Permission.SALE_READ, Permission.SALE_WRITE);
            assertThat(roleIdOf(jdbc, 39L)).isNotEqualTo(roleIdOf(jdbc, 40L));
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM roles WHERE system_client_id = 25 AND name = 'SELLER'",
                    Integer.class)).isEqualTo(2);

            jdbc.update("UPDATE roles SET resource = jsonb_set(resource, '{permissions}', ?::jsonb) WHERE id = ?",
                    "[\"PRODUCT_READ\",\"PRODUCT_WRITE\",\"LISTING_PUBLISH\",\"SALE_READ\",\"SALE_WRITE\"]",
                    roleIdOf(jdbc, 40L));
            assertThat(permissionsOf(jdbc, 39L)).doesNotContain(Permission.PRODUCT_WRITE);
            assertThat(permissionsOf(jdbc, 40L)).contains(Permission.PRODUCT_WRITE);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class)).isEqualTo(4);
            String verification = new ClassPathResource("db/verification/verify_relational_user_roles.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(jdbc.queryForList(verification)).isEmpty();

            Long tenantBViewer = roleIdOf(jdbc, 41L);
            assertThatThrownBy(() -> jdbc.update("UPDATE user_roles SET role_id = ? WHERE user_id = 38", tenantBViewer))
                    .hasStackTraceContaining("mesma empresa");

            Long tenantAAdmin = roleIdOf(jdbc, 38L);
            assertThatThrownBy(() -> jdbc.update("UPDATE user_roles SET role_id = ? WHERE user_id = 39", tenantAAdmin))
                    .hasStackTraceContaining("uq_user_roles_role");

            Long futureCompany = jdbc.queryForObject(
                    "INSERT INTO system_client(name, document) VALUES ('Empresa futura', 'future') RETURNING id",
                    Long.class);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM roles WHERE system_client_id = ?", Integer.class, futureCompany)).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void shouldConvertSharedTenantRoleIntoExclusiveUserRoles() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).target("7").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            company(jdbc, 50L, "Empresa de migração");
            legacyUser(jdbc, 51L, 50L, "seller-one@example.invalid",
                    "{\"role\":\"SELLER\",\"permissions\":[\"SALE_READ\"]}", 2025);
            legacyUser(jdbc, 52L, 50L, "seller-two@example.invalid",
                    "{\"role\":\"SELLER\",\"permissions\":[\"SALE_READ\"]}", 2026);

            assertThat(Flyway.configure().dataSource(dataSource).target("8").load()
                    .migrate().migrationsExecuted).isEqualTo(1);
            assertThat(roleIdOf(jdbc, 51L)).isEqualTo(roleIdOf(jdbc, 52L));
            jdbc.update("UPDATE users SET resource = ?::jsonb WHERE id = 52",
                    "{\"role\":\"editor\",\"permissions\":[\"SALE_READ\",\"SALE_WRITE\"],\"theme\":\"dark\"}");

            assertThat(Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted).isEqualTo(6);
            assertThat(roleOf(jdbc, 51L)).isEqualTo(Role.SELLER);
            assertThat(roleOf(jdbc, 52L)).isEqualTo(Role.SELLER);
            assertThat(roleIdOf(jdbc, 51L)).isNotEqualTo(roleIdOf(jdbc, 52L));
            assertThat(permissionsOf(jdbc, 51L)).containsExactly(Permission.SALE_READ);
            assertThat(permissionsOf(jdbc, 52L)).containsExactlyInAnyOrder(
                    Permission.SALE_READ, Permission.SALE_WRITE);
            assertThat(userResource(jdbc, 52L).attributes())
                    .containsExactlyEntriesOf(Map.of("theme", "dark"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM roles", Integer.class)).isEqualTo(2);
        }
    }

    private void company(JdbcTemplate jdbc, Long id, String name) {
        jdbc.update("INSERT INTO system_client(id, name, document) VALUES (?, ?, ?)", id, name, "tenant-" + id);
    }

    private void legacyUser(JdbcTemplate jdbc, Long id, Long companyId, String email, String resource, int year) {
        jdbc.update("INSERT INTO users(id, system_client_id, name, email, password_hash, resource, created_at) "
                        + "VALUES (?, ?, 'Usuário sanitizado', ?, 'not-a-real-password-hash', ?::jsonb, ?)",
                id, companyId, email, resource, Timestamp.valueOf(LocalDateTime.of(year, 1, 1, 0, 0)));
    }

    private Role roleOf(JdbcTemplate jdbc, Long userId) {
        return Role.valueOf(jdbc.queryForObject(
                "SELECT role.name FROM roles role JOIN user_roles link ON link.role_id = role.id WHERE link.user_id = ?",
                String.class, userId));
    }

    private Long roleIdOf(JdbcTemplate jdbc, Long userId) {
        return jdbc.queryForObject("SELECT role_id FROM user_roles WHERE user_id = ?", Long.class, userId);
    }

    private List<Permission> permissionsOf(JdbcTemplate jdbc, Long userId) throws Exception {
        String stored = jdbc.queryForObject(
                "SELECT role.resource::text FROM roles role JOIN user_roles link ON link.role_id = role.id WHERE link.user_id = ?",
                String.class, userId);
        return json.readValue(stored, RoleResource.class).permissions().stream().toList();
    }

    private UserResource userResource(JdbcTemplate jdbc, Long userId) throws Exception {
        return json.readValue(jdbc.queryForObject(
                "SELECT resource::text FROM users WHERE id = ?", String.class, userId), UserResource.class);
    }
}
