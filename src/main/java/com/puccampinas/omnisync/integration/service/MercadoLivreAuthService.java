package com.puccampinas.omnisync.integration.service;

import com.puccampinas.omnisync.core.audit.*;
import com.puccampinas.omnisync.core.users.repository.UserRepository;
import com.puccampinas.omnisync.core.users.enums.Permission;
import org.springframework.transaction.annotation.Transactional;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.core.systemClient.service.SystemClientService;
import com.puccampinas.omnisync.core.users.entity.User;
import com.puccampinas.omnisync.core.users.service.UserService;
import com.puccampinas.omnisync.integration.client.MercadoLivreClient;
import com.puccampinas.omnisync.integration.dto.MercadoLivreIntegrationResponse;
import com.puccampinas.omnisync.integration.dto.MercadoLivreIntegrationStatusResponse;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import com.puccampinas.omnisync.integration.repository.MarketplaceIntegrationRepository;
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
    private final SystemClientService systemClientService;
    private final UserService userService;
    private final TextEncryptor encryptor;
    private final AuditService audit;
    private final UserRepository users;

    @Value("${mercadolivre.redirect-uri}")
    private String redirectUri;

    @Value("${mercadolivre.oauth.state-secret}")
    private String stateSecret;

    @Value("${mercadolivre.oauth.state-ttl-seconds:600}")
    private long stateTtlSeconds;

    public MercadoLivreAuthService(
            MercadoLivreClient client,
            MarketplaceIntegrationRepository repository,
            SystemClientService systemClientService,
            UserService userService,
            TextEncryptor encryptor,
            AuditService audit,
            UserRepository users
    ) {
        this.client = client;
        this.repository = repository;
        this.systemClientService = systemClientService;
        this.userService = userService;
        this.encryptor = encryptor;
        this.audit = audit;
        this.users = users;
    }

    public String generateAuthorizationUrl(Long systemClientId) {
        validateSystemClient(systemClientId);

        String state = generateState(systemClientId);
        return client.buildAuthorizationUrl(redirectUri, state);
    }

    @Transactional
    public Long handleCallback(String state, String code) {
        Long systemClientId = extractSystemClientIdFromState(state);
        AuditActor initiator = callbackActor(state, systemClientId);
        validateSystemClient(systemClientId);

        MercadoLivreTokenResponse token = client.exchangeCode(code, redirectUri);
        validateTokenResponse(token);

        MarketplaceIntegration integration = repository.findBySystemClientIdAndMarketplace(
                        systemClientId,
                        Marketplace.MERCADO_LIVRE
                )
                .orElseGet(MarketplaceIntegration::new);

        var before = AuditSnapshots.integration(integration);
        integration.setSystemClientId(systemClientId);
        integration.setMarketplace(Marketplace.MERCADO_LIVRE);
        integration.setAccessToken(encryptor.encrypt(token.getAccessToken()));

        if (token.getRefreshToken() != null && !token.getRefreshToken().isBlank()) {
            integration.setRefreshToken(encryptor.encrypt(token.getRefreshToken()));
        }

        integration.setExpiresAt(LocalDateTime.now().plusSeconds(token.getExpiresIn()));
        integration.setResource(buildResource(token));
        integration.setActive(true);

        repository.save(integration);

        systemClientService.markMarketplaceConnected(systemClientId, "mercado_livre");
        audit.recordAs(initiator, AuditAction.CONNECT, AuditEntityType.INTEGRATION, integration.getId(),
                before, AuditSnapshots.integration(integration), AuditSource.OAUTH_CALLBACK, Map.of());

        return systemClientId;
    }

    @Transactional
    public MercadoLivreIntegrationResponse exchangeCodeForAuthenticatedUser(
            String authenticatedEmail,
            String state,
            String code
    ) {
        User authenticatedUser = userService.findActiveEntityByEmail(authenticatedEmail);
        Long systemClientIdFromState = extractSystemClientIdFromState(state);

        if (!authenticatedUser.getSystemClientId().equals(systemClientIdFromState)) {
            throw new IllegalArgumentException(
                    "OAuth state does not belong to the authenticated user's system client."
            );
        }

        Long systemClientId = handleCallback(state, code);
        MarketplaceIntegration integration = repository.findBySystemClientIdAndMarketplace(
                        systemClientId,
                        Marketplace.MERCADO_LIVRE
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Mercado Livre integration was not persisted for systemClientId=" + systemClientId
                ));

        return new MercadoLivreIntegrationResponse(
                "Mercado Livre account connected successfully.",
                systemClientId,
                integration.getMarketplace(),
                integration.getActive(),
                integration.getExpiresAt(),
                integration.getResource()
        );
    }

    @Transactional
    public void disconnect(Long systemClientId) {
        MarketplaceIntegration integration = repository
                .findBySystemClientIdAndMarketplaceForUpdate(systemClientId, Marketplace.MERCADO_LIVRE)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Integração não encontrada."));
        if (!Boolean.TRUE.equals(integration.getActive())) return;
        var before = AuditSnapshots.integration(integration);
        integration.setActive(false);
        // Local disconnection also removes stored credentials (access_token is NOT NULL).
        integration.setAccessToken("");
        integration.setRefreshToken(null);
        repository.save(integration);
        var company = systemClientService.getById(systemClientId);
        Map<String, Object> resources = new HashMap<>();
        if (company.getResource() != null) resources.putAll(company.getResource());
        resources.put("mercado_livre", false);
        systemClientService.updateClientsMarketplaces(systemClientId, resources);
        audit.record(systemClientId, AuditAction.DISCONNECT, AuditEntityType.INTEGRATION, integration.getId(),
                before, AuditSnapshots.integration(integration), AuditSource.WEB);
    }

    public MercadoLivreIntegrationStatusResponse getStatus(Long systemClientId) {
        return repository
                .findBySystemClientIdAndMarketplace(systemClientId, Marketplace.MERCADO_LIVRE)
                .map(integration -> new MercadoLivreIntegrationStatusResponse(
                        true,
                        integration.getSystemClientId(),
                        integration.getActive(),
                        integration.getExpiresAt(),
                        integration.getMarketplace().name(),
                        integration.getLastSyncAt()
                ))
                .orElseGet(() -> MercadoLivreIntegrationStatusResponse.notConnected(systemClientId));
    }

    private void validateSystemClient(Long systemClientId) {
        if (systemClientId == null) {
            throw new IllegalArgumentException("systemClientId is required.");
        }

        systemClientService.getById(systemClientId);
    }

    private void validateTokenResponse(MercadoLivreTokenResponse token) {
        if (token == null || token.getAccessToken() == null || token.getExpiresIn() == null) {
            throw new IllegalStateException("Mercado Livre token response is invalid.");
        }
    }

    private Map<String, Object> buildResource(MercadoLivreTokenResponse token) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("scope", token.getScope());
        metadata.put("token_type", token.getTokenType());
        metadata.put("user_id", token.getUserId());
        return metadata;
    }

    private String generateState(Long systemClientId) {
        long expiresAtEpochSeconds = Instant.now().plusSeconds(stateTtlSeconds).getEpochSecond();
        AuditActor initiator = audit.actor(systemClientId);
        String payload = systemClientId + ":" + expiresAtEpochSeconds + ":" + UUID.randomUUID()
                + ":" + (initiator.id() == null ? "0" : initiator.id());
        String signature = sign(payload);
        String rawState = payload + ":" + signature;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(rawState.getBytes(StandardCharsets.UTF_8));
    }

    private Long extractSystemClientIdFromState(String state) {
        try {
            String rawState = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            String[] parts = rawState.split(":");

            if (parts.length != 4 && parts.length != 5) {
                throw new IllegalArgumentException("OAuth state is malformed.");
            }

            String payload = String.join(":", java.util.Arrays.copyOf(parts, parts.length - 1));
            String expectedSignature = sign(payload);
            String receivedSignature = parts[parts.length - 1];

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
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("OAuth state could not be validated.", ex);
        }
    }

    /** Called only after signature/expiry validation; older state has no attributable user. */
    private AuditActor callbackActor(String state, Long tenantId) {
        String[] parts = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8).split(":");
        if (parts.length == 4 || parts[3].equals("0")) return AuditActor.system(tenantId);
        User user = users.findById(Long.valueOf(parts[3]))
                .filter(u -> tenantId.equals(u.getSystemClientId()) && Boolean.TRUE.equals(u.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("Autor do OAuth inválido."));
        if (user.getTenantRole() == null || !user.getTenantRole().getPermissions().contains(Permission.INTEGRATION_MANAGE))
            throw new com.puccampinas.omnisync.config.security.PermissionDeniedException(Permission.INTEGRATION_MANAGE);
        return AuditActor.from(user);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(stateSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("HmacSHA256 algorithm is not available.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign OAuth state.", ex);
        }
    }
}
