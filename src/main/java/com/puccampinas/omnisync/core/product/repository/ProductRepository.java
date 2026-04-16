package com.puccampinas.omnisync.core.product.repository;

import com.puccampinas.omnisync.core.product.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Page<Product> findAllBySystemClientIdAndActiveTrue(Long systemClientId, Pageable pageable);

    Optional<Product> findBySkuAndSystemClientIdAndActiveTrue(String sku, Long systemClientId);

    Optional<Product> findBySkuAndSystemClientId(String sku, Long systemClientId);

    Optional<Product> findByIdAndSystemClientIdAndActiveTrue(Long id, Long systemClientId);

    @Query(
            value = """
                    SELECT *
                    FROM products
                    WHERE system_client_id = :systemClientId
                      AND active = TRUE
                      AND resource -> 'mercado_livre' ->> 'item_id' = :itemId
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<Product> findBySystemClientIdAndMercadoLivreItemIdAndActiveTrue(
            @Param("systemClientId") Long systemClientId,
            @Param("itemId") String itemId
    );

    @Query(
            value = """
                    SELECT *
                    FROM products
                    WHERE system_client_id = :systemClientId
                      AND resource -> 'mercado_livre' ->> 'item_id' = :itemId
                    LIMIT 1
                    """,
            nativeQuery = true
    )
    Optional<Product> findBySystemClientIdAndMercadoLivreItemId(
            @Param("systemClientId") Long systemClientId,
            @Param("itemId") String itemId
    );

    @Query(
            value = """
                    SELECT *
                    FROM products
                    WHERE system_client_id = :systemClientId
                      AND jsonb_extract_path(resource, 'mercado_livre') IS NOT NULL
                    """,
            nativeQuery = true
    )
    List<Product> findAllMercadoLivreProductsBySystemClientId(@Param("systemClientId") Long systemClientId);
}
