package com.sanyan.user.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * /oauth/bind-phone 请求：第三方登录未绑账号时，客户端带 bindTicket + 手机号 + 短信码回来绑定/建号。
 * password 仅当目标手机号已注册过有密码的账号时，作为"本人证明"使用（合并安全 S4）。
 */
@Data
public class OauthBindPhoneReq {
    @NotBlank private String bindTicket;
    @NotBlank
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;
    @NotBlank private String code;
    /** 合并到已有密码账号时的本人证明（可选）。 */
    private String password;
}
