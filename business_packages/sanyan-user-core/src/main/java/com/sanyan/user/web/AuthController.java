package com.sanyan.user.web;

import com.sanyan.common.web.BaseResp;
import com.sanyan.user.internal.SmsCodeSendService;
import com.sanyan.user.internal.UserLoginService;
import com.sanyan.user.internal.UserRegisterService;
import com.sanyan.user.internal.oauth.OauthBindService;
import com.sanyan.user.internal.oauth.OauthChallengeService;
import com.sanyan.user.internal.oauth.OauthLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRegisterService userRegisterService;
    private final UserLoginService userLoginService;
    private final SmsCodeSendService smsCodeSendService;
    private final OauthChallengeService oauthChallengeService;
    private final OauthLoginService oauthLoginService;
    private final OauthBindService oauthBindService;

    @PostMapping("/sms/send")
    public BaseResp<Void> sendSms(@Valid @RequestBody SmsSendReq req) {
        smsCodeSendService.sendCode(req.getPhone());
        return BaseResp.success(null);
    }

    @PostMapping("/register")
    public BaseResp<LoginData> register(@Valid @RequestBody RegisterReq req) {
        return BaseResp.success(userRegisterService.register(req));
    }

    @PostMapping("/login")
    public BaseResp<LoginData> login(@Valid @RequestBody LoginReq req) {
        return BaseResp.success(userLoginService.login(req));
    }

    /** 下发一次性 nonce（S8 防重放），客户端 Apple 登录前调用。 */
    @PostMapping("/oauth/challenge")
    public BaseResp<OauthChallengeData> oauthChallenge() {
        return BaseResp.success(new OauthChallengeData(oauthChallengeService.issueNonce()));
    }

    /** 第三方登录：命中身份直接登录，未绑则返回 bindTicket 走 /oauth/bind-phone。 */
    @PostMapping("/oauth/login")
    public BaseResp<OauthLoginData> oauthLogin(@Valid @RequestBody OauthLoginReq req) {
        return BaseResp.success(oauthLoginService.login(req));
    }

    /** 第三方登录绑定手机号：带 bindTicket + 手机号 + 短信码完成绑定 / 建号，成功返回登录态。 */
    @PostMapping("/oauth/bind-phone")
    public BaseResp<OauthLoginData> oauthBindPhone(@Valid @RequestBody OauthBindPhoneReq req) {
        return BaseResp.success(oauthBindService.bindPhone(req));
    }
}
