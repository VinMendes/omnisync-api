package com.puccampinas.omnisync.integration.repository;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketplaceIntegrationRepository extends JpaRepository<MarketplaceIntegration, Long> {

    Optional<MarketplaceIntegration> findBySystemClientIdAndMarketplace(
            Long systemClientId,
            Marketplace marketplace
    );

    Optional<MarketplaceIntegration> findBySystemClientIdAndMarketplaceAndActiveTrue(
            Long systemClientId,
            Marketplace marketplace
    );

    @Query(
            value = """
                    SELECT *
                    FROM marketplace_integrations
                    WHERE resource ->> 'user_id' = :userId
                      AND marketplace = :marketplace
                      AND active = TRUE
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<MarketplaceIntegration> findByMarketplaceUserIdAndMarketplaceAndActiveTrue(
            @Param("userId") String userId,
            @Param("marketplace") String marketplace
    );

    @Query(
            value = """
                    SELECT *
                    FROM marketplace_integrations
                    WHERE system_client_id = :systemClientId
                      AND marketplace = :marketplace
                      AND resource ->> 'user_id' IS NOT NULL
                      AND active = TRUE
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<MarketplaceIntegration> findMercadoLivreActiveIntegrationForSync(
            @Param("systemClientId") Long systemClientId,
            @Param("marketplace") String marketplace
    );
}
