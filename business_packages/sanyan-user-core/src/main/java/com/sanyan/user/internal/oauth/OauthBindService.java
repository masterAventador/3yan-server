package com.sanyan.user.internal.oauth;

import com.sanyan.common.auth.BindTicketPayload;
import com.sanyan.common.auth.BindTicketUtil;
import com.sanyan.common.auth.JwtUtil;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.SmsCodeSendService;
import com.sanyan.user.internal.UserAuthIdentityEntity;
import com.sanyan.user.internal.UserAuthIdentityRepository;
import com.sanyan.user.internal.UserEntity;
import com.sanyan.user.internal.UserErrCode;
import com.sanyan.user.internal.UserRepository;
import com.sanyan.user.web.OauthBindPhoneReq;
import com.sanyan.user.web.OauthLoginData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 第三方登录"绑定手机号"核心编排（Task 11）：未绑账号时 /oauth/login 发了 bindTicket，
 * 客户端带手机号 + 短信码回来，本服务按<b>固定 10 步顺序</b>完成绑定 / 建号。
 *
 * <p><b>10 步顺序是安全约束，不可调换</b>（任何把校验放到写库之后都重开漏洞）：
 * <ol>
 *   <li>parse bindTicket（验签 / typ==BIND / exp 都在 {@link BindTicketUtil#parse} 内，失败转 BIND_TICKET_INVALID）</li>
 *   <li>typ==BIND（已在 parse 内）</li>
 *   <li>exp / iat（已在 parse 内）</li>
 *   <li><b>jti 一次性消费锁</b>（{@link KvCache#setIfAbsent}）——必须在验短信 / 查库 / 写库之前</li>
 *   <li>provider / externalId 合法性（String → {@link Provider}，非法 → BIND_TICKET_INVALID）</li>
 *   <li>SMS 可用性（真短信已接，verifyCode 自适应 dev/prod，无需额外 gate）</li>
 *   <li>验短信码（原子，已含失败计数 / 锁定；false → SMS_CODE_INVALID）</li>
 *   <li>按 phone 查账号 + 合并安全 S4（已有密码账号必须验原密码；无密码账号无法验本人 → needMergeAuth）</li>
 *   <li>同事务内 insert（建 user + 绑 identity），并发撞唯一约束 → 重查降级登录（insert-or-recover S7）</li>
 *   <li>签 JWT 返回</li>
 * </ol>
 *
 * <p>事务语义：本方法<b>不</b>整体 {@code @Transactional}。写库圈在 {@link OauthIdentityBinder} 的
 * {@code @Transactional} 方法里（经 Spring 代理生效）；本方法在事务外 catch
 * {@link DataIntegrityViolationException}，此时内层事务已回滚，catch 后的重查是干净的只读查询，
 * 不会落在 rollback-only 的坏事务里。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OauthBindService {

    static final String JTI_USED_KEY_PREFIX = "oauth:bindticket:used:";
    static final Duration JTI_USED_TTL = Duration.ofMinutes(10);

    private final BindTicketUtil bindTicketUtil;
    private final KvCache kvCache;
    private final SmsCodeSendService smsCodeSendService;
    private final UserRepository userRepo;
    private final UserAuthIdentityRepository identityRepo;
    private final JwtUtil jwtUtil;
    private final OauthIdentityBinder identityBinder;

    public OauthLoginData bindPhone(OauthBindPhoneReq req) {
        // ① 解析 bindTicket（typ==BIND ② / exp ③ 都在 parse 内校验）；parse 抛通用 TOKEN_INVALID → 重包成更贴切的 BIND_TICKET_INVALID
        BindTicketPayload p;
        try {
            p = bindTicketUtil.parse(req.getBindTicket());
        } catch (BusinessException e) {
            throw new BusinessException(UserErrCode.BIND_TICKET_INVALID);
        }

        // ④ jti 一次性消费锁——必须在验短信 / 查库 / 写库之前，防止同一 ticket 重放
        if (!kvCache.setIfAbsent(JTI_USED_KEY_PREFIX + p.jti(), "1", JTI_USED_TTL)) {
            throw new BusinessException(UserErrCode.BIND_TICKET_USED);
        }

        // ⑤ provider / externalId 合法性
        Provider provider;
        try {
            provider = Provider.valueOf(p.provider());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(UserErrCode.BIND_TICKET_INVALID);
        }
        if (p.externalId() == null || p.externalId().isBlank()) {
            throw new BusinessException(UserErrCode.BIND_TICKET_INVALID);
        }

        // ⑥ SMS 可用性：真短信已接，verifyCode 自适应，直接进 ⑦
        // ⑦ 验短信码（原子，已含失败计数 / 锁定）
        if (!smsCodeSendService.verifyCode(req.getPhone(), req.getCode())) {
            throw new BusinessException(UserErrCode.SMS_CODE_INVALID);
        }

        // ⑧ 按 phone 查账号 + 合并安全 S4
        Optional<UserEntity> existing = userRepo.findByPhone(req.getPhone());
        UserEntity boundUser;
        if (existing.isEmpty()) {
            // ⑨a 新建账号 + 绑 identity（同事务，insert-or-recover S7）
            boundUser = createOrRecover(req.getPhone(), provider, p);
        } else {
            UserEntity u = existing.get();
            // 账号无密码（如之前第三方建的号）——无法用密码验本人 → needMerge 数据态（引导登录后在设置里绑定，不抛错不写库）
            if (u.getPassword() == null) {
                return OauthLoginData.needMergeAuth();
            }
            // 有密码账号但客户端没传密码 → needMerge 数据态（提示去证明本人，不抛错不写库）
            if (req.getPassword() == null || req.getPassword().isBlank()) {
                return OauthLoginData.needMergeAuth();
            }
            // 传了密码但错 → 真身份校验失败 → 抛错误码
            if (!identityBinder.matchesPassword(req.getPassword(), u.getPassword())) {
                throw new BusinessException(UserErrCode.NEED_MERGE_AUTH);
            }
            // ⑨b 密码对 → 合并：把 identity 挂到该账号
            boundUser = attachOrRecover(u, provider, p);
        }

        // ⑩ 签 JWT 返回
        return OauthLoginData.loggedIn(boundUser, jwtUtil.generateToken(boundUser.getId()));
    }

    /** ⑨a 建号 + 绑定，撞唯一约束（并发已绑）→ 重查命中走登录降级，查不到则真异常 rethrow。 */
    private UserEntity createOrRecover(String phone, Provider provider, BindTicketPayload p) {
        try {
            return identityBinder.createUserWithIdentity(phone, provider, p.externalId(), p.rawOpenid());
        } catch (DataIntegrityViolationException e) {
            return recoverBoundUser(provider, p.externalId(), e);
        }
    }

    /** ⑨b 绑定到已验本人账号，撞唯一约束（并发已绑）→ 重查命中走登录降级。 */
    private UserEntity attachOrRecover(UserEntity user, Provider provider, BindTicketPayload p) {
        try {
            identityBinder.attachIdentityToUser(user.getId(), provider, p.externalId(), p.rawOpenid());
            return user;
        } catch (DataIntegrityViolationException e) {
            return recoverBoundUser(provider, p.externalId(), e);
        }
    }

    /** 写库撞唯一约束后的只读重查降级（事务外，不受 rollback-only 影响）。命中返回已绑账号，查不到 rethrow。 */
    private UserEntity recoverBoundUser(Provider provider, String externalId, DataIntegrityViolationException e) {
        UserAuthIdentityEntity bound = identityRepo.findByProviderAndExternalId(provider, externalId)
                .orElseThrow(() -> e);
        log.info("oauth bind 并发撞唯一约束，降级为登录: provider={}, userId={}", provider, bound.getUserId());
        return userRepo.findById(bound.getUserId())
                .orElseThrow(() -> new BusinessException(UserErrCode.USER_NOT_FOUND));
    }
}
