package com.puccampinas.omnisync.core.systemClient.repository;

import com.puccampinas.omnisync.core.systemClient.entity.SystemClient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemClientRepository extends JpaRepository<SystemClient, Long> {

    boolean existsByDocument(String document);

    boolean existsByDocumentAndIdNot(String document, Long id);
}
