package com.sanyan.user.internal.sms;

/** 短信发送抽象。验证码内容由调用方生成，发送渠道由实现决定。 */
public interface SmsSender {
    /** 发送验证码短信；失败抛 BusinessException。 */
    void send(String phone, String code);
}
