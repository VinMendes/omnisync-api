package com.puccampinas.omnisync.core.auth.passwordreset;

import com.puccampinas.omnisync.core.users.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    @org.springframework.data.jpa.repository.Query("select t.user.id from PasswordResetToken t where t.token = :token")
    Optional<Long> findUserIdByToken(@org.springframework.data.repository.query.Param("token") String token);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select t from PasswordResetToken t where t.token = :token")
    Optional<PasswordResetToken> findForConsumption(@org.springframework.data.repository.query.Param("token") String token);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update PasswordResetToken t set t.used = true, t.usedAt = :now where t.user.id = :userId and t.used = false")
    int invalidateForUser(@org.springframework.data.repository.query.Param("userId") Long userId,
                          @org.springframework.data.repository.query.Param("now") java.time.Instant now);

    void deleteByUser(User user);
}
