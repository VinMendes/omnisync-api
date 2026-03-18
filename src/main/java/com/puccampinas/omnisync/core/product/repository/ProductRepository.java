package com.puccampinas.omnisync.core.product.repository;

import com.puccampinas.omnisync.core.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findAllBySystemClientIdAndActiveTrue(Long systemClientId, Pageable pageable);

    Optional<Product> findBySkuAndSystemClientIdAndActiveTrue(String sku, Long systemClientId);

    Optional<Product> findByIdAndSystemClientIdAndActiveTrue(Long id, Long systemClientId);
}
