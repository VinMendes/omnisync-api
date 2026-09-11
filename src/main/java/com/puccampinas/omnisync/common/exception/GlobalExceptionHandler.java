package com.puccampinas.omnisync.common.exception;

import jakarta.persistence.EntityNotFoundException;
import com.puccampinas.omnisync.config.security.PermissionDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import com.puccampinas.omnisync.integration.exception.MercadoLivreSyncException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern PERMISSION_AUTHORITY = Pattern.compile("\\bPERM_([A-Z][A-Z0-9_]*)\\b");

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidParameter(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "Parâmetro inválido: " + ex.getName()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(HttpStatus.FORBIDDEN.value(), accessDeniedMessage(ex)));
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
                            HttpStatus.TOO_MANY_REQUESTS.value(),
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
                .body(new ErrorResponse(ex.getStatusCode().value(),
                        "Não foi possível concluir a comunicação com o Mercado Livre."));
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
                new ErrorResponse(status.value(), ex.getMessage(), ex.getCode(),
                        ex.getRetryAfterSeconds(), ex.getLastSyncAt()),
                headers,
                status
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
    }

    private String accessDeniedMessage(AccessDeniedException exception) {
        if (exception instanceof PermissionDeniedException) {
            return exception.getMessage();
        }
        if (exception instanceof AuthorizationDeniedException denied
                && denied.getAuthorizationResult() instanceof ExpressionAuthorizationDecision decision) {
            Matcher matcher = PERMISSION_AUTHORITY.matcher(decision.getExpression().getExpressionString());
            if (matcher.find()) {
                return "Permissão insuficiente: " + matcher.group(1);
            }
        }

        String message = exception.getMessage();
        if (message != null && message.startsWith("Não é permitido")) {
            return message;
        }
        return "Acesso negado.";
    }
}
