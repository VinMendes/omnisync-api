package com.puccampinas.omnisync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

@Configuration
public class CryptoConfig {

    @Value("${app.crypto.secret}")
    private String secret;

    @Bean
    public TextEncryptor textEncryptor() {
        return Encryptors.text(secret, "12345678");
    }
}