package com.puccampinas.omnisync.common.exception;

import org.springframework.http.HttpStatusCode;

public class ExternalApiException extends RuntimeException {

    private final HttpStatusCode statusCode;
    private final String providerCode;
    private final Integer retryAfterSeconds;

    public ExternalApiException(HttpStatusCode statusCode, String message) {
        this(statusCode, message, null, null);
    }

    public ExternalApiException(
            HttpStatusCode statusCode,
            String message,
            String providerCode,
            Integer retryAfterSeconds
    ) {
        super(message);
        this.statusCode = statusCode;
        this.providerCode = providerCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isRateLimited() {
        return statusCode.value() == 429;
    }
}
