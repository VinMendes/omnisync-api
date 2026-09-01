package com.puccampinas.omnisync.core.users.repository;

import com.puccampinas.omnisync.core.users.entity.TenantRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenantRoleRepository extends JpaRepository<TenantRole, Long> {

    List<TenantRole> findAllBySystemClientIdOrderByName(Long systemClientId);
}
