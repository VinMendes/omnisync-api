package com.puccampinas.omnisync.integration.repository;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.entity.MarketplaceStockSyncOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceStockSyncOutboxRepository extends JpaRepository<MarketplaceStockSyncOutbox, Long> {

    Optional<MarketplaceStockSyncOutbox> findBySaleIdAndMarketplaceAndOperation(
            Long saleId,
            Marketplace marketplace,
            String operation
    );

    List<MarketplaceStockSyncOutbox> findAllBySystemClientIdAndProductIdOrderByIdAsc(
            Long systemClientId,
            Long productId
    );

    @Query(value = """
            SELECT *
              FROM marketplace_stock_sync_outbox
             WHERE (status IN ('PENDING', 'RETRY_WAIT') AND next_attempt_at <= :eligibleAt)
                OR (status = 'PROCESSING' AND locked_at <= :leaseExpiredBefore)
             ORDER BY id
             LIMIT :claimLimit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MarketplaceStockSyncOutbox> findEligibleForClaim(
            @Param("eligibleAt") Instant eligibleAt,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            @Param("claimLimit") int claimLimit
    );

    @Query(value = """
            SELECT pg_advisory_xact_lock(
                hashtextextended(CAST(:systemClientId AS text) || ':' || CAST(:productId AS text), 0)
            )
            """, nativeQuery = true)
    void acquireProductAdvisoryLock(
            @Param("systemClientId") Long systemClientId,
            @Param("productId") Long productId
    );
}
