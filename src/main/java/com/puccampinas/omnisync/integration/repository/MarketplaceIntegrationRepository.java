package com.puccampinas.omnisync.integration.repository;

import com.puccampinas.omnisync.common.enums.Marketplace;
import com.puccampinas.omnisync.integration.entity.MarketplaceIntegration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketplaceIntegrationRepository
        extends JpaRepository<MarketplaceIntegration, Long> {

    Optional<MarketplaceIntegration>
    findBySystemClientIdAndMarketplace(
            Long systemClientId,
            Marketplace marketplace
    );

    Optional<MarketplaceIntegration>
    findBySystemClientIdAndMarketplaceAndActiveTrue(
            Long systemClientId,
            Marketplace marketplace
    );
}
