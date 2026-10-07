package com.puccampinas.omnisync.core.users.repository;

import com.puccampinas.omnisync.core.users.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /** All password mutations lock the user before touching recovery tokens (consistent lock order). */
    @org.springframework.data.jpa.repository.Query(value = "select id from users where id = :id for update", nativeQuery = true)
    Optional<Long> lockForPasswordUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    default Optional<User> findForPasswordUpdate(Long id) {
        return lockForPasswordUpdate(id).flatMap(this::findById);
    }

    boolean existsByIdAndSystemClientId(Long id, Long systemClientId);

    @EntityGraph(attributePaths = "tenantRole")
    Optional<User> findByEmail(String email);

    @EntityGraph(attributePaths = "tenantRole")
    List<User> findAllBySystemClientId(Long systemClientId);

    @EntityGraph(attributePaths = "tenantRole")
    Optional<User> findByIdAndSystemClientId(Long id, Long systemClientId);

    @Override
    @EntityGraph(attributePaths = "tenantRole")
    Optional<User> findById(Long id);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);
}
