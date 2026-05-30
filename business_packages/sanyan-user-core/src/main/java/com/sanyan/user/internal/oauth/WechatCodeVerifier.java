package com.sanyan.user.internal.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.common.web.client.HttpClientFactory;
import com.sanyan.user.internal.UserErrCode;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 微信 code 换 openid/unionid 校验器（安全要求 S2）。
 * <p>
 * 客户端只传 code；openid/unionid <b>只信</b>服务端用 AppSecret 调微信换来的响应，无视客户端传的任何身份字段。
 * 固定校验顺序（任一失败抛对应错误码）：
 * <ol>
 *   <li>一次性占位：{@code setIfAbsent("wechat:code:" + code, "1", 5min)}，已存在直接拒（防 code 并发重放，{@link UserErrCode#OAUTH_VERIFY_FAILED}）</li>
 *   <li>服务端用 AppSecret 调微信 {@code /sns/oauth2/access_token} 换取响应</li>
 *   <li>响应无 errcode（微信失败时 HTTP 200 但 body 带 errcode/errmsg → 有则拒，{@link UserErrCode#OAUTH_VERIFY_FAILED}）</li>
 *   <li>断言 unionid 非空（为空抛 {@link UserErrCode#WECHAT_UNIONID_MISSING}，<b>严禁 fallback 用 openid 填 external_id</b>）</li>
 * </ol>
 * 返回 {@link VerifiedIdentity}：external_id = unionid，rawOpenid = openid。
 * access_token / refresh_token 用完即弃，不入库。
 * <p>
 * 可测试设计：把调微信的 HTTP 调用抽成 {@link #exchangeCode(String)}（protected），测试用子类重写注入构造响应离线验 S2 逻辑。
 */
@Slf4j
@Component
public class WechatCodeVerifier implements ThirdPartyAuthVerifier {

    /** 微信 OAuth 网关 base url。 */
    private static final String WECHAT_API_BASE = "https://api.weixin.qq.com";
    /** code 换 access_token/openid/unionid 的接口路径。 */
    private static final String ACCESS_TOKEN_PATH = "/sns/oauth2/access_token";
    /** 微信 OAuth 固定 grant_type。 */
    private static final String GRANT_TYPE = "authorization_code";

    /** code 一次性占位 key 前缀（防并发重放）。 */
    private static final String CODE_GUARD_KEY_PREFIX = "wechat:code:";
    /** code 一次性占位 TTL（微信 code 有效期 5 分钟）。 */
    private static final Duration CODE_GUARD_TTL = Duration.ofMinutes(5);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final OauthProperties properties;
    private final KvCache kvCache;
    private final RestClient restClient;

    public WechatCodeVerifier(OauthProperties properties, KvCache kvCache) {
        this.properties = properties;
        this.kvCache = kvCache;
        this.restClient = HttpClientFactory.newClient(WECHAT_API_BASE, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    @Override
    public VerifiedIdentity verify(String code, String ignoredNonce) {
        // S2.2：一次性占位，已用过的 code 直接拒（防并发重放）；此处不调微信
        if (!kvCache.setIfAbsent(CODE_GUARD_KEY_PREFIX + code, "1", CODE_GUARD_TTL)) {
            throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
        }

        // 配置守卫：本类无条件 @Component（dev 无微信配置也得能启动），不能构造期 fail-fast，
        // 故在 verify 期检查 appid/secret。漏配时给清晰日志，而非把 secret=null 发给微信拿误导性 errcode。
        if (!StringUtils.hasText(properties.getWechat().getAppid())
                || !StringUtils.hasText(properties.getWechat().getSecret())) {
            log.warn("微信 appid/secret 未配置，无法完成 code 换取");
            throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
        }

        WechatTokenResponse resp = exchangeCode(code);

        // S2.3：微信失败时 HTTP 200 但 body 带 errcode/errmsg
        if (resp.getErrcode() != null && resp.getErrcode() != 0) {
            // 不打 AppSecret；只记 errcode/errmsg 供排查
            log.warn("微信 code 换 token 失败: errcode={}, errmsg={}", resp.getErrcode(), resp.getErrmsg());
            throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
        }

        // S2.4：unionid 必需，为空抛 WECHAT_UNIONID_MISSING，严禁 fallback openid
        if (!StringUtils.hasText(resp.getUnionid())) {
            throw new BusinessException(UserErrCode.WECHAT_UNIONID_MISSING);
        }

        // S2.5：external_id = unionid，rawOpenid = openid；token 用完即弃不入库
        return new VerifiedIdentity(resp.getUnionid(), resp.getOpenid());
    }

    /**
     * 服务端用 AppSecret 调微信 {@code /sns/oauth2/access_token} 用 code 换 openid/unionid。
     * <p>
     * 抽成 protected 便于测试用子类重写注入构造响应（成功 / 带 errcode / 缺 unionid）离线验 S2 逻辑。
     * 网络 / 反序列化异常统一转 {@link UserErrCode#OAUTH_VERIFY_FAILED}（不打 AppSecret）。
     */
    protected WechatTokenResponse exchangeCode(String code) {
        try {
            WechatTokenResponse resp = callWechat(code);
            if (resp == null) {
                log.warn("微信 code 换 token 返回空 body");
                throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
            }
            return resp;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 网络 timeout / 连接拒绝 / 反序列化失败等。
            // 不打 e.getMessage()：微信 secret 放在 URL query 里，RestClient 的
            // ResourceAccessException message 含完整 URL（含 secret），打了就泄密。只记异常类型。
            log.warn("微信 code 换取失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(UserErrCode.OAUTH_VERIFY_FAILED);
        }
    }

    /**
     * 真正发起微信 HTTP 调用的最内层 seam（GET {@code /sns/oauth2/access_token}）。
     * <p>
     * 单独抽出，便于测试在不联网的前提下覆盖它抛网络异常，从而驱动 {@link #exchangeCode(String)} 的
     * 兜底 catch + log 分支（验证不泄漏 AppSecret）。
     */
    protected WechatTokenResponse callWechat(String code) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(ACCESS_TOKEN_PATH)
                        .queryParam("appid", properties.getWechat().getAppid())
                        .queryParam("secret", properties.getWechat().getSecret())
                        .queryParam("code", code)
                        .queryParam("grant_type", GRANT_TYPE)
                        .build())
                .retrieve()
                .body(WechatTokenResponse.class);
    }

    /**
     * 微信 {@code /sns/oauth2/access_token} 响应 DTO。
     * <p>
     * 字段名对齐微信返回；成功含 openid/unionid，失败时 HTTP 200 但带 errcode/errmsg。
     * access_token/refresh_token 用完即弃，本 DTO 不收（忽略未知字段）。
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WechatTokenResponse {
        private String openid;
        private String unionid;
        private Integer errcode;
        private String errmsg;
    }
}
