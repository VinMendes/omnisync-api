package com.puccampinas.omnisync.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;

/** Não consulta DB_URL nem se conecta à VM. O servidor existe só enquanto o contexto de teste estiver aberto. */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedPostgresTestConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }
}
