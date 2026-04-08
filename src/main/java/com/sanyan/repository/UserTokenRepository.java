package com.sanyan.repository;

import com.sanyan.entity.UserToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {
    Optional<UserToken> findByUserId(Long userId);
    @Transactional
    void deleteByUserId(Long userId);
}
