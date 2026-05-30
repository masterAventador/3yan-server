package com.sanyan.user.internal.oauth;

import com.sanyan.user.internal.UserAuthIdentityEntity;
import com.sanyan.user.internal.UserAuthIdentityRepository;
import com.sanyan.user.internal.UserEntity;
import com.sanyan.user.internal.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * OauthBind 写库边界。独立成 bean，让 {@code @Transactional} 经 Spring 代理生效，
 * 并把"建 user + 绑 identity"圈进同一事务（任一失败整体回滚，不留半条记录）。
 * <p>
 * 这样拆分是为了 insert-or-recover（S7）的事务语义干净：写库撞唯一约束时本事务被标记 rollback-only，
 * {@link OauthBindService} 在<b>事务外</b>捕获 {@code DataIntegrityViolationException} 后做只读重查降级，
 * 不受 rollback-only 影响（若把整个 bindPhone 圈进一个事务，catch 后的重查会落在已坏的事务里）。
 */
@Component
@RequiredArgsConstructor
public class OauthIdentityBinder {

    private final UserRepository userRepo;
    private final UserAuthIdentityRepository identityRepo;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 新建无密码账号并绑定第三方身份，同一事务。 */
    @Transactional
    public UserEntity createUserWithIdentity(String phone, Provider provider, String externalId, String rawOpenid) {
        UserEntity user = new UserEntity();
        user.setPhone(phone);
        // 第三方建号无密码（password 留空）；昵称兜底不依赖 phone（spec §127），用 provider 标签 + 随机短串
        user.setNickname(defaultNickname(provider));
        userRepo.save(user);
        saveIdentity(user.getId(), provider, externalId, rawOpenid);
        return user;
    }

    /** 第三方建号默认昵称：与手机号无关，按 provider 标签 + 6 位随机短串（避免泄露手机号尾号、避免重名）。 */
    private static String defaultNickname(Provider provider) {
        String label = switch (provider) {
            case WECHAT -> "微信用户";
            case APPLE -> "Apple用户";
        };
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return label + suffix;
    }

    /** 给已验证本人的已有账号绑定第三方身份。 */
    @Transactional
    public void attachIdentityToUser(Long userId, Provider provider, String externalId, String rawOpenid) {
        saveIdentity(userId, provider, externalId, rawOpenid);
    }

    private void saveIdentity(Long userId, Provider provider, String externalId, String rawOpenid) {
        UserAuthIdentityEntity identity = new UserAuthIdentityEntity();
        identity.setUserId(userId);
        identity.setProvider(provider);
        identity.setExternalId(externalId);
        identity.setRawOpenid(rawOpenid);
        identityRepo.save(identity);
    }

    /** BCrypt 比对原密码证明本人（封装在此，与建/绑同处一类，OauthBindService 不直接持有 encoder）。 */
    public boolean matchesPassword(String rawPassword, String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}
