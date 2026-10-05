package com.puccampinas.omnisync.integration.repository;

import com.puccampinas.omnisync.integration.entity.MarketplaceWebhookInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceWebhookInboxRepository extends JpaRepository<MarketplaceWebhookInbox, Long> {

    Optional<MarketplaceWebhookInbox> findByEventKey(String eventKey);

    List<MarketplaceWebhookInbox> findAllBySystemClientIdOrderByIdAsc(Long systemClientId);

    @Query(value = """
            SELECT *
              FROM marketplace_webhook_inbox
             WHERE (status IN ('RECEIVED', 'RETRY_WAIT') AND next_attempt_at <= :eligibleAt)
                OR (status = 'PROCESSING' AND locked_at <= :leaseExpiredBefore)
             ORDER BY id
             LIMIT :claimLimit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<MarketplaceWebhookInbox> findEligibleForClaim(
            @Param("eligibleAt") Instant eligibleAt,
            @Param("leaseExpiredBefore") Instant leaseExpiredBefore,
            @Param("claimLimit") int claimLimit
    );
}
