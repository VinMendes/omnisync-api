package com.puccampinas.omnisync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*
 * ===============================================================
 *  OmniSync - Boot Application
 * ===============================================================
 *
 *  Observações importantes:
 *
 *  1) Auto-configuração de BANCO DE DADOS está TEMPORARIAMENTE DESATIVADA.
 *     Isso foi feito para permitir que a aplicação suba sem exigir
 *     DataSource, JPA ou Flyway configurados neste momento inicial.
 *
 *     A desativação está configurada em:
 *     -> src/main/resources/application.properties
 *
 *  2) Spring Boot 4.x trouxe mudanças na estrutura de pacotes e nomes
 *     de classes de auto-configuração. Alguns caminhos usados em versões
 *     antigas NÃO são mais válidos. Sempre conferir os imports e paths
 *     corretos antes de usar spring.autoconfigure.exclude.
 *
 *  3) Quando o banco (PostgreSQL) for ativado futuramente, REMOVER a
 *     exclusão de auto-configuração no application.properties para que:
 *        - DataSource
 *        - JPA / Hibernate
 *        - Flyway
 *     voltem a ser inicializados normalmente.
 *
 * ===============================================================
 */

@SpringBootApplication
public class OmniSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmniSyncApplication.class, args);
    }

}