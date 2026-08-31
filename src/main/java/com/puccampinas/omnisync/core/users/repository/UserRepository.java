package com.puccampinas.omnisync.core.users.repository;

import com.puccampinas.omnisync.core.users.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    List<User> findAllBySystemClientId(Long systemClientId);

    Optional<User> findByIdAndSystemClientId(Long id, Long systemClientId);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);
}