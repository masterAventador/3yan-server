package com.sanyan.user.internal;

import com.sanyan.user.internal.oauth.Provider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAuthIdentityRepository extends JpaRepository<UserAuthIdentityEntity, Long> {
    Optional<UserAuthIdentityEntity> findByProviderAndExternalId(Provider provider, String externalId);
}
