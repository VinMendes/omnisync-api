package com.puccampinas.omnisync.integration.exception;

import java.time.Instant;

public class MercadoLivreSyncException extends RuntimeException {

    public static final String SYNC_IN_PROGRESS = "ML_SYNC_IN_PROGRESS";
    public static final String REAUTH_REQUIRED = "ML_REAUTH_REQUIRED";
    public static final String RATE_LIMITED = "ML_RATE_LIMITED";

    private final String code;
    private final Integer retryAfterSeconds;
    private final Instant lastSyncAt;

    public MercadoLivreSyncException(
            String code,
            String message,
            Integer retryAfterSeconds,
            Instant lastSyncAt,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
        this.lastSyncAt = lastSyncAt;
    }

    public static MercadoLivreSyncException syncInProgress(Instant lastSyncAt) {
        return new MercadoLivreSyncException(
                SYNC_IN_PROGRESS,
                "Uma sincronização do Mercado Livre já está em andamento.",
                null,
                lastSyncAt,
                null
        );
    }

    public static MercadoLivreSyncException reauthRequired(Throwable cause) {
        return new MercadoLivreSyncException(
                REAUTH_REQUIRED,
                "Reconecte sua conta do Mercado Livre para sincronizar novamente.",
                null,
                null,
                cause
        );
    }

    public static MercadoLivreSyncException rateLimited(Integer retryAfterSeconds, Throwable cause) {
        return new MercadoLivreSyncException(
                RATE_LIMITED,
                "O Mercado Livre limitou temporariamente as sincronizações. Tente novamente mais tarde.",
                retryAfterSeconds,
                null,
                cause
        );
    }

    public String getCode() {
        return code;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }
}
