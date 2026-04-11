package com.puccampinas.omnisync.core.sale.repository;
import com.puccampinas.omnisync.core.sale.entity.Sale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SaleRepository extends  JpaRepository<Sale, Long> {
}
