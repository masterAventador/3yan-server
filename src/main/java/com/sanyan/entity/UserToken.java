package com.sanyan.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_token")
public class UserToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 500)
    private String token;
    @Column(length = 10)
    private String deviceType;
    private String pushToken;
    private LocalDateTime expiredAt;
}
