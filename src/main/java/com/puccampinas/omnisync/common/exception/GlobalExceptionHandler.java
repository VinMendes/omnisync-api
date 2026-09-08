package com.puccampinas.omnisync.common.exception;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import com.puccampinas.omnisync.integration.exception.MercadoLivreSyncException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ExternalApiException.class)
    public ResponseEntity<ErrorResponse> handleExternalApi(ExternalApiException ex) {
        if (ex.isRateLimited()) {
            HttpHeaders headers = new HttpHeaders();
            if (ex.getRetryAfterSeconds() != null) {
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
            }
            return new ResponseEntity<>(
                    new ErrorResponse(
                            "O Mercado Livre limitou temporariamente as sincronizações. Tente novamente mais tarde.",
                            MercadoLivreSyncException.RATE_LIMITED,
                            ex.getRetryAfterSeconds(),
                            null
                    ),
                    headers,
                    HttpStatus.TOO_MANY_REQUESTS
            );
        }

        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse("Não foi possível concluir a comunicação com o Mercado Livre."));
    }

    @ExceptionHandler(MercadoLivreSyncException.class)
    public ResponseEntity<ErrorResponse> handleMercadoLivreSync(MercadoLivreSyncException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case MercadoLivreSyncException.SYNC_IN_PROGRESS -> HttpStatus.ACCEPTED;
            case MercadoLivreSyncException.REAUTH_REQUIRED -> HttpStatus.CONFLICT;
            case MercadoLivreSyncException.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        HttpHeaders headers = new HttpHeaders();
        if (ex.getRetryAfterSeconds() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return new ResponseEntity<>(
                new ErrorResponse(ex.getMessage(), ex.getCode(), ex.getRetryAfterSeconds(), ex.getLastSyncAt()),
                headers,
                status
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(ex.getMessage()));
    }
}
