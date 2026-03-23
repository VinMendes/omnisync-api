package com.puccampinas.omnisync.core.product.repository;

import com.puccampinas.omnisync.core.product.entity.ProductLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductLogRepository extends JpaRepository<ProductLog, Long> {
}
