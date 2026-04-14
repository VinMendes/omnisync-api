package com.puccampinas.omnisync.integration.client;

import com.puccampinas.omnisync.common.exception.ExternalApiException;
import com.puccampinas.omnisync.integration.dto.MercadoLivreTokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Component
public class MercadoLivreClient {

    private static final String OAUTH_URL = "https://auth.mercadolivre.com.br/authorization";
    private static final String TOKEN_URL = "https://api.mercadolibre.com/oauth/token";
    private static final String API_URL = "https://api.mercadolibre.com";

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

    public Map<String, Object> getMyUser(String accessToken) {
        return exchange(
                HttpMethod.GET,
                API_URL + "/users/me",
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> getItem(String accessToken, String itemId) {
        return exchange(
                HttpMethod.GET,
                API_URL + "/items/" + itemId,
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> getOrder(String accessToken, String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre order id is required.");
        }

        String url = UriComponentsBuilder.fromUriString(API_URL + "/orders/" + orderId)
                .queryParam("x-format-new", "true")
                .toUriString();

        return exchange(
                HttpMethod.GET,
                url,
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public List<Map<String, Object>> getItems(String accessToken, List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            throw new IllegalArgumentException("At least one Mercado Livre item id is required.");
        }

        String url = UriComponentsBuilder.fromUriString(API_URL + "/items")
                .queryParam("ids", String.join(",", itemIds))
                .toUriString();

        return exchange(
                HttpMethod.GET,
                url,
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> searchSellerItems(
            String accessToken,
            Long sellerId,
            Map<String, ?> queryParams
    ) {
        String url = buildSellerItemsSearchUrl(sellerId, queryParams);

        return exchange(
                HttpMethod.GET,
                url,
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> createItem(String accessToken, Map<String, Object> payload) {
        return exchange(
                HttpMethod.POST,
                API_URL + "/items",
                accessToken,
                payload,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> updateItem(String accessToken, String itemId, Map<String, Object> payload) {
        return exchange(
                HttpMethod.PUT,
                API_URL + "/items/" + itemId,
                accessToken,
                payload,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> createItemDescription(String accessToken, String itemId, String plainText) {
        return exchange(
                HttpMethod.POST,
                API_URL + "/items/" + itemId + "/description",
                accessToken,
                Map.of("plain_text", plainText),
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> updateItemDescription(String accessToken, String itemId, String plainText) {
        String url = UriComponentsBuilder.fromUriString(API_URL + "/items/" + itemId + "/description")
                .queryParam("api_version", "2")
                .toUriString();

        return exchange(
                HttpMethod.PUT,
                url,
                accessToken,
                Map.of("plain_text", plainText),
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public List<Map<String, Object>> searchCategorySuggestions(String accessToken, String siteId, String query) {
        if (siteId == null || siteId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre site id is required.");
        }

        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre category search query is required.");
        }

        String url = UriComponentsBuilder.fromUriString(API_URL + "/sites/" + siteId + "/domain_discovery/search")
                .queryParam("q", query)
                .toUriString();

        return exchange(
                HttpMethod.GET,
                url,
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public List<Map<String, Object>> getSiteCategories(String accessToken, String siteId) {
        if (siteId == null || siteId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre site id is required.");
        }

        return exchange(
                HttpMethod.GET,
                API_URL + "/sites/" + siteId + "/categories",
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public List<Map<String, Object>> getCategoryAttributes(String accessToken, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre category id is required.");
        }

        return exchange(
                HttpMethod.GET,
                API_URL + "/categories/" + categoryId + "/attributes",
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> getCategoryTechnicalSpecsInput(String accessToken, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre category id is required.");
        }

        return exchange(
                HttpMethod.GET,
                API_URL + "/categories/" + categoryId + "/technical_specs/input",
                accessToken,
                null,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    public Map<String, Object> uploadItemPicture(
            String accessToken,
            byte[] content,
            String filename,
            String contentType
    ) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Mercado Livre picture content is required.");
        }

        String resolvedFilename = filename == null || filename.isBlank() ? "image.jpg" : filename;
        MediaType resolvedContentType = parseMediaType(contentType);

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(resolvedContentType);

        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return resolvedFilename;
            }
        };

        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(fileResource, fileHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            return restTemplate.exchange(
                    API_URL + "/pictures/items/upload",
                    HttpMethod.POST,
                    request,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            ).getBody();
        } catch (RestClientResponseException ex) {
            String responseBody = ex.getResponseBodyAsString();
            String message = responseBody == null || responseBody.isBlank()
                    ? "Mercado Livre picture upload failed."
                    : "Mercado Livre picture upload failed: " + responseBody;
            throw new ExternalApiException(ex.getStatusCode(), message);
        }
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

    private <T> T exchange(
            HttpMethod method,
            String url,
            String accessToken,
            Object body,
            ParameterizedTypeReference<T> responseType
    ) {
        HttpEntity<?> request = new HttpEntity<>(body, createJsonHeaders(accessToken));

        try {
            return restTemplate.exchange(url, method, request, responseType).getBody();
        } catch (RestClientResponseException ex) {
            String responseBody = ex.getResponseBodyAsString();
            String message = responseBody == null || responseBody.isBlank()
                    ? "Mercado Livre request failed."
                    : "Mercado Livre request failed: " + responseBody;
            throw new ExternalApiException(ex.getStatusCode(), message);
        }
    }

    private HttpHeaders createJsonHeaders(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("Mercado Livre access token is required.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private String buildSellerItemsSearchUrl(Long sellerId, Map<String, ?> queryParams) {
        if (sellerId == null) {
            throw new IllegalArgumentException("Mercado Livre seller id is required.");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                API_URL + "/users/" + sellerId + "/items/search"
        );

        if (queryParams != null) {
            queryParams.forEach((key, value) -> addQueryParam(builder, key, value));
        }

        return builder.toUriString();
    }

    private void addQueryParam(UriComponentsBuilder builder, String key, Object value) {
        if (value == null) {
            return;
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    builder.queryParam(key, item);
                }
            }
            return;
        }

        builder.queryParam(key, value);
    }

    private MediaType parseMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.IMAGE_JPEG;
        }

        try {
            return MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid picture content_type: " + contentType, ex);
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
