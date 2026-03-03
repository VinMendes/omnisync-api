package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class MercadoLivreAuthService {

    private final MercadoLivreClient client;
    private final MarketplaceIntegrationRepository repository;
    private final TextEncryptor encryptor;
    private final ObjectMapper objectMapper;

    @Value("${mercadolivre.redirect-uri}")
    private String redirectUri;

    @Value("${mercadolivre.oauth.state-secret}")
    private String stateSecret;

    @Value("${mercadolivre.oauth.state-ttl-seconds:600}")
    private long stateTtlSeconds;

    public MercadoLivreAuthService(
            MercadoLivreClient client,
            MarketplaceIntegrationRepository repository,
            TextEncryptor encryptor,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.repository = repository;
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }

    public String generateAuthorizationUrl(Long systemClientId) {
        String state = generateState(systemClientId);
        return client.buildAuthorizationUrl(redirectUri, state);
    }

    public Long handleCallback(String state, String code) {
        Long systemClientId = extractSystemClientIdFromState(state);

        MercadoLivreTokenResponse token =
                client.exchangeCode(code, redirectUri);
        validateTokenResponse(token);

        MarketplaceIntegration integration =
                repository.findBySystemClientIdAndMarketplace(
                        systemClientId,
                        Marketplace.MERCADO_LIVRE
                ).orElse(new MarketplaceIntegration());

        integration.setSystemClientId(systemClientId);
        integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setAccessToken(
                encryptor.encrypt(token.getAccessToken())
        );
        if (token.getRefreshToken() != null) {
            integration.setRefreshToken(
                    encryptor.encrypt(token.getRefreshToken())
            );
        }
        integration.setExpiresAt(
                LocalDateTime.now().plusSeconds(token.getExpiresIn())
        );
        integration.setResource(buildResource(token));
        integration.setActive(true);

        repository.save(integration);
        return systemClientId;
    }

    private void validateTokenResponse(MercadoLivreTokenResponse token) {
        if (token == null || token.getAccessToken() == null || token.getExpiresIn() == null) {
            throw new IllegalArgumentException("Mercado Livre token response is invalid.");
        }
    }

    private String buildResource(MercadoLivreTokenResponse token) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("scope", token.getScope());
            metadata.put("token_type", token.getTokenType());
            metadata.put("user_id", token.getUserId());

            return objectMapper.writeValueAsString(
                    metadata
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize marketplace resource metadata.", e);
        }
    }

    private String generateState(Long systemClientId) {
        long expiresAtEpochSeconds = Instant.now().plusSeconds(stateTtlSeconds).getEpochSecond();
        String payload = systemClientId + ":" + expiresAtEpochSeconds + ":" + UUID.randomUUID();
        String signature = sign(payload);
        String rawState = payload + ":" + signature;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawState.getBytes(StandardCharsets.UTF_8));
    }

    private Long extractSystemClientIdFromState(String state) {
        try {
            String rawState = new String(
                    Base64.getUrlDecoder().decode(state),
                    StandardCharsets.UTF_8
            );
            String[] parts = rawState.split(":");

            if (parts.length != 4) {
                throw new IllegalArgumentException("OAuth state is malformed.");
            }

            String payload = parts[0] + ":" + parts[1] + ":" + parts[2];
            String expectedSignature = sign(payload);
            String receivedSignature = parts[3];

            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8)
            )) {
                throw new IllegalArgumentException("OAuth state signature is invalid.");
            }

            long expiresAtEpochSeconds = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > expiresAtEpochSeconds) {
                throw new IllegalArgumentException("OAuth state is expired.");
            }

            return Long.parseLong(parts[0]);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("OAuth state could not be validated.", e);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    stateSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(key);
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 algorithm is not available.", e);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign OAuth state.", e);
        }
    }
}
