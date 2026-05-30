package com.sanyan.user.internal.oauth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sanyan.common.cache.KvCache;
import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.UserErrCode;
import com.sanyan.user.internal.oauth.WechatCodeVerifier.WechatTokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WechatCodeVerifier 单测（S2 安全核心，逐分支验）。
 * <p>
 * 策略：把"调微信换 openid/unionid 的 HTTP 调用"抽成 {@code exchangeCode}（protected），
 * 测试用子类重写它注入构造响应（成功 / 带 errcode / 缺 unionid），离线验 S2 逻辑；
 * {@link KvCache} 用 Mockito mock 控制一次性占位行为。
 */
class WechatCodeVerifierTest {

    private static final String CODE = "wx-auth-code-001";
    private static final String OPENID = "o1";
    private static final String UNIONID = "u1";

    private KvCache kvCache;

    @BeforeEach
    void setUp() {
        kvCache = mock(KvCache.class);
    }

    /** appid/secret 已配置的 OauthProperties（通过 verify 期配置守卫）。 */
    private static OauthProperties configuredProps() {
        OauthProperties props = new OauthProperties();
        props.getWechat().setAppid("wxapp");
        props.getWechat().setSecret("wxsecret");
        return props;
    }

    /** 构造一个用固定响应替换 exchangeCode 的 verifier 子类（appid/secret 已配置）。 */
    private WechatCodeVerifier verifierReturning(WechatTokenResponse stub) {
        return new WechatCodeVerifier(configuredProps(), kvCache) {
            @Override
            protected WechatTokenResponse exchangeCode(String code) {
                return stub;
            }
        };
    }

    private static WechatTokenResponse response(String openid, String unionid,
                                                Integer errcode, String errmsg) {
        WechatTokenResponse resp = new WechatTokenResponse();
        resp.setOpenid(openid);
        resp.setUnionid(unionid);
        resp.setErrcode(errcode);
        resp.setErrmsg(errmsg);
        return resp;
    }

    @Test
    void verify_ok() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        WechatCodeVerifier verifier = verifierReturning(response(OPENID, UNIONID, null, null));

        VerifiedIdentity result = verifier.verify(CODE, null);

        // external_id = unionid，rawOpenid = openid
        assertEquals(UNIONID, result.externalId());
        assertEquals(OPENID, result.rawOpenid());

        // 验证一次性占位：setIfAbsent("wechat:code:" + code, ...) 被调
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kvCache).setIfAbsent(keyCaptor.capture(), anyString(), any(Duration.class));
        assertEquals("wechat:code:" + CODE, keyCaptor.getValue());
    }

    @Test
    void verify_rejectsWhenErrcode() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 微信失败时 HTTP 200 但 body 带 errcode（如 40029 invalid code）
        WechatCodeVerifier verifier = verifierReturning(response(null, null, 40029, "invalid code"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(CODE, null));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
    }

    @Test
    void verify_rejectsWhenUnionidMissing() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // 微信返回有 openid 但 unionid 为空：必须抛 WECHAT_UNIONID_MISSING，严禁 fallback openid
        WechatCodeVerifier verifier = verifierReturning(response(OPENID, null, null, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(CODE, null));
        assertEquals(UserErrCode.WECHAT_UNIONID_MISSING, ex.getErrCode());
    }

    @Test
    void verify_rejectsReusedCode() {
        // setIfAbsent 返回 false → code 已被用过（并发重放），直接拒，且不调 exchangeCode
        when(kvCache.setIfAbsent(eq("wechat:code:" + CODE), anyString(), any(Duration.class)))
                .thenReturn(false);
        boolean[] exchangeCalled = {false};
        WechatCodeVerifier verifier = new WechatCodeVerifier(configuredProps(), kvCache) {
            @Override
            protected WechatTokenResponse exchangeCode(String code) {
                exchangeCalled[0] = true;
                return response(OPENID, UNIONID, null, null);
            }
        };

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(CODE, null));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
        org.junit.jupiter.api.Assertions.assertEquals(false, exchangeCalled[0],
                "code 已用应直接拒，不得调用 exchangeCode");
    }

    @Test
    void verify_rejectsWhenAppidSecretMissing() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        // appid/secret 未配置（空 OauthProperties）：守卫拒绝，不调 exchangeCode（不把 secret=null 发给微信）
        boolean[] exchangeCalled = {false};
        WechatCodeVerifier verifier = new WechatCodeVerifier(new OauthProperties(), kvCache) {
            @Override
            protected WechatTokenResponse exchangeCode(String code) {
                exchangeCalled[0] = true;
                return response(OPENID, UNIONID, null, null);
            }
        };

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifier.verify(CODE, null));
        assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());
        org.junit.jupiter.api.Assertions.assertEquals(false, exchangeCalled[0],
                "appid/secret 漏配应在调微信前拒绝");
    }

    /**
     * S2 安全回归：微信 secret 放在 URL query 里，{@link ResourceAccessException} 的 message
     * 形如 {@code I/O error on GET request for "...&secret=THE_SECRET_VALUE&...": ...}。
     * 兜底 catch 绝不能 log 该异常的 message（否则任何网络超时都会把 AppSecret 明文写进日志）。
     */
    @Test
    void exchangeCode_networkError_doesNotLeakSecretToLog() {
        when(kvCache.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        Logger logger = (Logger) LoggerFactory.getLogger(WechatCodeVerifier.class);
        ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        try {
            String secret = "THE_SECRET_VALUE";
            // 模拟 RestClient 网络异常：message 含完整含 secret 的 URL
            // 覆盖最内层 HTTP seam 抛网络异常，从而驱动真实 exchangeCode 的兜底 catch + log
            WechatCodeVerifier verifier = new WechatCodeVerifier(configuredProps(), kvCache) {
                @Override
                protected WechatTokenResponse callWechat(String code) {
                    throw new ResourceAccessException(
                            "I/O error on GET request for "
                                    + "\"https://api.weixin.qq.com/sns/oauth2/access_token"
                                    + "?appid=wxapp&secret=" + secret + "&code=" + code
                                    + "&grant_type=authorization_code\": Connection timed out");
                }
            };

            // ① 仍正常转 OAUTH_VERIFY_FAILED
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> verifier.verify(CODE, null));
            assertEquals(UserErrCode.OAUTH_VERIFY_FAILED, ex.getErrCode());

            // ② 捕获到的所有日志里没有任何一条 message 含 secret
            boolean leaked = listAppender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(msg -> msg.contains(secret));
            assertTrue(!leaked, "日志泄漏了微信 AppSecret");
        } finally {
            logger.detachAppender(listAppender);
        }
    }
}
