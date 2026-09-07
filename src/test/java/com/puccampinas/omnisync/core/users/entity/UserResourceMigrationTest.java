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
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserResourceMigrationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void shouldBackfillLegacyUsersInRealPostgresWithoutNewTablesOrRepeatedPrivilegeChanges() throws Exception {
        // Esta instância é local e descartável. Nenhuma variável de conexão da aplicação é lida.
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).target("6").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Long firstCompany = company(jdbc, "Empresa A");
            Long secondCompany = company(jdbc, "Empresa B");
            Long thirdCompany = company(jdbc, "Empresa C");

            Long first = user(jdbc, firstCompany, null, 2020);
            Long second = user(jdbc, firstCompany, "{\"cpf\":\"preserve-me\"}", 2021);
            Long legacyEditor = user(jdbc, firstCompany,
                    "{\"role\":\"editor\",\"permissions\":[\"Anúncios\",\"Gestão de estoque\"],\"theme\":\"dark\"}", 2022);
            Long malformed = user(jdbc, firstCompany,
                    "{\"role\":\"superuser\",\"permissions\":[\"USER_MANAGE\"]}", 2022);
            Long emptyPermissions = user(jdbc, firstCompany, "{\"role\":\"ADMIN\",\"permissions\":[]}", 2022);
            Long badPermissions = user(jdbc, firstCompany, "{\"role\":\"MANAGER\",\"permissions\":{}}", 2022);
            Long mixedPermissions = user(jdbc, firstCompany,
                    "{\"role\":\"VIEWER\",\"permissions\":[\"PRODUCT_READ\",42,null,\"ALL_POWERS\"]}", 2022);
            Long invalidRoot = user(jdbc, secondCompany, "[\"preserve-legacy\"]", 2020);
            Long nullJson = user(jdbc, secondCompany, "null", 2020);
            Long newerInsertedFirst = user(jdbc, thirdCompany, "{}", 2022);
            Long olderInsertedLater = user(jdbc, thirdCompany, "{}", 2019);
            Map<Role, Long> defaultUsers = new HashMap<>();
            for (Role role : Role.values()) {
                defaultUsers.put(role, user(jdbc, firstCompany, json.writeValueAsString(Map.of("role", role.name())), 2023));
            }

            Flyway permissionMigration = Flyway.configure().dataSource(dataSource).target("7").load();
            assertThat(permissionMigration.migrate().migrationsExecuted).isEqualTo(1);
            Flyway flyway = Flyway.configure().dataSource(dataSource).load();

            assertThat(resource(jdbc, first).role()).isEqualTo(Role.ADMIN);
            assertThat(resource(jdbc, second).role()).isEqualTo(Role.VIEWER);
            assertThat(resource(jdbc, second).attributes()).containsEntry("cpf", "preserve-me");
            assertThat(resource(jdbc, legacyEditor).role()).isEqualTo(Role.SELLER);
            assertThat(resource(jdbc, legacyEditor).permissions()).containsExactlyInAnyOrder(
                    Permission.PRODUCT_READ, Permission.PRODUCT_WRITE, Permission.LISTING_PUBLISH);
            assertThat(resource(jdbc, legacyEditor).attributes()).containsEntry("theme", "dark");
            assertThat(resource(jdbc, malformed).role()).isEqualTo(Role.VIEWER);
            assertThat(resource(jdbc, malformed).permissions()).isEqualTo(Role.VIEWER.defaultPermissions());
            assertThat(resource(jdbc, emptyPermissions).permissions()).isEmpty();
            assertThat(resource(jdbc, badPermissions).permissions()).isEmpty();
            assertThat(resource(jdbc, mixedPermissions).permissions()).containsExactly(Permission.PRODUCT_READ);
            assertThat(resource(jdbc, invalidRoot).role()).isEqualTo(Role.ADMIN);
            assertThat(resource(jdbc, invalidRoot).attributes()).containsKey("_legacy_resource");
            assertThat(resource(jdbc, nullJson).role()).isEqualTo(Role.VIEWER);
            assertThat(resource(jdbc, newerInsertedFirst).role()).isEqualTo(Role.VIEWER);
            assertThat(resource(jdbc, olderInsertedLater).role()).isEqualTo(Role.ADMIN);
            for (var entry : defaultUsers.entrySet()) {
                assertThat(resource(jdbc, entry.getValue()).role()).isEqualTo(entry.getKey());
                assertThat(resource(jdbc, entry.getValue()).permissions()).isEqualTo(entry.getKey().defaultPermissions());
            }

            String verification = new ClassPathResource("db/verification/verify_user_resource_permissions.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(jdbc.queryForList(verification)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                    + "AND table_name IN ('permissions', 'role_permissions')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_roles", Integer.class)).isZero();

            // Migrações posteriores (como os índices do dashboard) não alteram as permissões.
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

            // Atribuições posteriores não são revertidas quando a aplicação inicia novamente.
            jdbc.update("UPDATE users SET resource = ?::jsonb WHERE id = ?",
                    json.writeValueAsString(UserResource.defaults()), first);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(resource(jdbc, first).role()).isEqualTo(Role.VIEWER);
            assertThat(jdbc.queryForList(verification)).isEmpty();
        }
    }

    private Long company(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("INSERT INTO system_client(name, document) VALUES (?, ?) RETURNING id",
                Long.class, name, name);
    }

    private Long user(JdbcTemplate jdbc, Long company, String resource, int year) {
        return jdbc.queryForObject("INSERT INTO users(system_client_id, name, email, password_hash, resource, created_at) "
                        + "VALUES (?, 'Teste de migração', ?, 'not-a-real-password-hash', ?::jsonb, ?) RETURNING id",
                Long.class, company, java.util.UUID.randomUUID() + "@example.invalid", resource,
                Timestamp.valueOf(LocalDateTime.of(year, 1, 1, 0, 0)));
    }

    private UserResource resource(JdbcTemplate jdbc, Long id) throws Exception {
        return json.readValue(jdbc.queryForObject("SELECT resource::text FROM users WHERE id = ?", String.class, id),
                UserResource.class);
    }
}
