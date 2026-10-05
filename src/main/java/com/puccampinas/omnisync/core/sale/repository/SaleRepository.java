package com.puccampinas.omnisync.core.sale.repository;

import com.puccampinas.omnisync.core.sale.entity.Sale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.Optional;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findBySystemClientIdAndChannelAndExternalReferenceId(
            Long systemClientId,
            String channel,
            String externalReferenceId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select sale
              from Sale sale
             where sale.systemClientId = :systemClientId
               and sale.channel = :channel
               and sale.externalReferenceId = :externalReferenceId
            """)
    Optional<Sale> findByIdempotencyKeyForUpdate(
            @Param("systemClientId") Long systemClientId,
            @Param("channel") String channel,
            @Param("externalReferenceId") String externalReferenceId
    );

    Page<Sale> findAllBySystemClientId(Long systemClientId, Pageable pageable);

    Optional<Sale> findByIdAndSystemClientId(Long id, Long systemClientId);
}
