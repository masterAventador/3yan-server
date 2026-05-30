package com.sanyan.user.internal;

import com.sanyan.user.internal.oauth.Provider;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "user_auth_identity",
       uniqueConstraints = {
         @UniqueConstraint(name = "uk_provider_external", columnNames = {"provider", "external_id"}),
         @UniqueConstraint(name = "uk_provider_user", columnNames = {"provider", "user_id"})
       })
public class UserAuthIdentityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Provider provider;

    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "raw_openid", length = 128)
    private String rawOpenid;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
