package com.sanyan.user.internal;

/**
 * 短信发送底层适配接口。
 *
 * <p>当前仓库只有 {@link LogSmsProvider} 这一种实现，把验证码打到日志里，方便联调。
 * 接入真实厂商（阿里云 / 腾讯云）时新增一个实现类，给它加
 * {@code @ConditionalOnProperty(name = "sanyan.sms.provider", havingValue = "aliyun")}，
 * 同时给 {@code LogSmsProvider} 加 {@code matchIfMissing=true} 的反向条件即可切换。
 */
public interface SmsProvider {
    void send(String phone, String code);
}
