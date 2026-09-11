package com.puccampinas.omnisync.integration.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

@Repository
public class MercadoLivreSyncLockRepository {

    private final EntityManager entityManager;

    public MercadoLivreSyncLockRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public boolean tryAcquire(Long systemClientId) {
        Object value = entityManager.createNativeQuery(
                        "SELECT pg_try_advisory_xact_lock(CAST(:lockKey AS BIGINT))"
                )
                .setParameter("lockKey", -systemClientId)
                .getSingleResult();
        return Boolean.TRUE.equals(value);
    }
}
