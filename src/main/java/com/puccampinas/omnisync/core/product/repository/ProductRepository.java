package com.puccampinas.omnisync.core.product.repository;

import com.puccampinas.omnisync.core.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findAllBySystemClientIdAndActiveTrue(Long systemClientId);

    Optional<Product> findByIdAndSystemClientIdAndActiveTrue(Long id, Long systemClientId);
}
