package com.puccampinas.omnisync.core.sale.repository;

import com.puccampinas.omnisync.core.sale.entity.SaleLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SaleLogRepository extends JpaRepository<SaleLog, Long> {

    List<SaleLog> findAllBySaleIdOrderByCreatedAtAsc(Long saleId);
}
