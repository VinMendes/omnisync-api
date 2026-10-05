package com.puccampinas.omnisync.core.sale.exception;

public final class IdempotencyConflictException extends RuntimeException {

    public static final String CODE = "IDEMPOTENCY_CONFLICT";
    public static final String MESSAGE = "A referência externa já foi utilizada por outra venda.";

    public IdempotencyConflictException() {
        super(MESSAGE);
    }
}
