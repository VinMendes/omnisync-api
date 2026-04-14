package com.puccampinas.omnisync.core.sale.repository;
<<<<<<< HEAD

import com.puccampinas.omnisync.core.sale.entity.Sale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findBySystemClientIdAndChannelAndExternalReferenceId(
            Long systemClientId,
            String channel,
            String externalReferenceId
    );

    Page<Sale> findAllBySystemClientId(Long systemClientId, Pageable pageable);

    Optional<Sale> findByIdAndSystemClientId(Long id, Long systemClientId);
=======
import com.puccampinas.omnisync.core.sale.entity.Sale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SaleRepository extends  JpaRepository<Sale, Long> {
>>>>>>> 135b12a3bcd649fcee214bfc9851ad5bb8df90a8
}
