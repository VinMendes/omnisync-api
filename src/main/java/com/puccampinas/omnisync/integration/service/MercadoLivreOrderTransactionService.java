package com.puccampinas.omnisync.integration.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Boundary for the short local transaction that applies an already fetched
 * provider order. Provider network I/O is intentionally completed before this
 * boundary is entered.
 */
@Service
public class MercadoLivreOrderTransactionService {

    @Transactional
    public <T> T execute(Supplier<T> localOrderApplication) {
        return localOrderApplication.get();
    }
}
