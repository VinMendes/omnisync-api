package com.puccampinas.omnisync.integration.client;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class MercadoLivreClient {

    private static final String OAUTH_URL = "https://auth.mercadolivre.com.br/authorization";
    private static final String TOKEN_URL = "https://api.mercadolibre.com/oauth/token";

    private final RestTemplate restTemplate;

    @Value("${mercadolivre.client-id:}")
    private String clientId;

    @Value("${mercadolivre.client-secret:}")
    private String clientSecret;

    public MercadoLivreClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public MercadoLivreTokenResponse exchangeCode(String code, String redirectUri) {
        validateCredentials();

        LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("code", code);
        body.add("redirect_uri", redirectUri);

        return requestToken(body);
    }

    public MercadoLivreTokenResponse refreshAccessToken(String refreshToken) {
        validateCredentials();

        LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("refresh_token", refreshToken);

        return requestToken(body);
    }

    public String buildAuthorizationUrl(String redirectUri, String state) {
        validateCredentials();

        return UriComponentsBuilder.fromUriString(OAUTH_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .toUriString();
    }

    private MercadoLivreTokenResponse requestToken(LinkedMultiValueMap<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<LinkedMultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

        try {
            return restTemplate.postForObject(TOKEN_URL, request, MercadoLivreTokenResponse.class);
        } catch (RestClientResponseException ex) {
            String responseBody = ex.getResponseBodyAsString();
            String message = responseBody == null || responseBody.isBlank()
                    ? "Mercado Livre token request failed."
                    : "Mercado Livre token request failed: " + responseBody;
            throw new ExternalApiException(ex.getStatusCode(), message);
        }
    }

    private void validateCredentials() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException(
                    "Mercado Livre credentials are not configured. Define MELI_CLIENT_ID and MELI_CLIENT_SECRET."
            );
        }
    }
}
