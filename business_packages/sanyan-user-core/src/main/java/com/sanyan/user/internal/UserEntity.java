package com.sanyan.user.internal;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_user_phone", columnList = "phone", unique = true)
})
public class UserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // 第三方登录账号无手机号 / 密码，故可空（DB V12 已 DROP NOT NULL）
    @Column(unique = true, length = 20)
    private String phone;
    @Column
    private String password;
    @Column(length = 50)
    private String nickname;
    private String avatar;
    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
