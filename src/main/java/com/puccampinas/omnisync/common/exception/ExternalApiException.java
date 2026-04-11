package com.puccampinas.omnisync.common.exception;

import org.springframework.http.HttpStatusCode;

public class ExternalApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public ExternalApiException(HttpStatusCode statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }
}
