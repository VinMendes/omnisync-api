package com.puccampinas.omnisync.integration.repository;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

class MercadoLivreSyncLockRepositoryIntegrationTest {

    @Test
    void lockIsImmediateTenantScopedAndReleasedAtTransactionEnd() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                     .setServerConfig("listen_addresses", "127.0.0.1").start();
             Connection first = postgres.getPostgresDatabase().getConnection();
             Connection second = postgres.getPostgresDatabase().getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            assertThat(tryLock(first, -7L)).isTrue();
            long started = System.nanoTime();
            assertThat(tryLock(second, -7L)).isFalse();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000);
            assertThat(tryLock(second, -8L)).isTrue();

            first.rollback();
            assertThat(tryLock(second, -7L)).isTrue();
            second.rollback();
        }
    }

    private boolean tryLock(Connection connection, long key) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
