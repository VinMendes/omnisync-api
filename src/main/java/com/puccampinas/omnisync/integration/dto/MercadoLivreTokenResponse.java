package com.puccampinas.omnisync.integration.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MercadoLivreTokenResponse {

    @JsonProperty("access_token")
    private String accessToken;

    @JsonProperty("refresh_token")
    private String refreshToken;

    @JsonProperty("expires_in")
    private Long expiresIn;

    @JsonProperty("token_type")
    private String tokenType;

    @JsonProperty("user_id")
    private Long userId;

    private String scope;

    public MercadoLivreTokenResponse() {}

    public String getAccessToken() { return accessToken; }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() { return refreshToken; }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Long getExpiresIn() { return expiresIn; }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getTokenType() { return tokenType; }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public Long getUserId() { return userId; }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getScope() { return scope; }

    public void setScope(String scope) { this.scope = scope; }
}
