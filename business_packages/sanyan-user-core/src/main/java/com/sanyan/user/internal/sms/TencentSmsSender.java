package com.sanyan.user.internal.sms;

import com.sanyan.common.error.BusinessException;
import com.sanyan.user.internal.UserErrCode;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 腾讯云真实短信。由 sanyan.sms.provider=tencent 激活（prod）。 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sanyan.sms.provider", havingValue = "tencent")
public class TencentSmsSender implements SmsSender {

    private final SmsProperties props;

    public TencentSmsSender(SmsProperties props) {
        this.props = props;
        SmsProperties.Tencent t = props.getTencent();
        // prod fail-fast：选了 tencent 但配置不完整 → 启动即报错，绝不退回 stub
        if (isBlank(t.getSecretId()) || isBlank(t.getSecretKey()) || isBlank(t.getSdkAppId())
                || isBlank(t.getSignName()) || isBlank(t.getTemplateId())) {
            throw new IllegalStateException(
                    "sanyan.sms.provider=tencent 但腾讯云短信配置不完整（secretId/secretKey/sdkAppId/signName/templateId 必填）");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 抽出便于测试注入 mock。 */
    protected SmsClient client() {
        SmsProperties.Tencent t = props.getTencent();
        return new SmsClient(new Credential(t.getSecretId(), t.getSecretKey()), t.getRegion());
    }

    @Override
    public void send(String phone, String code) {
        SmsProperties.Tencent t = props.getTencent();
        SendSmsRequest req = new SendSmsRequest();
        req.setSmsSdkAppId(t.getSdkAppId());
        req.setSignName(t.getSignName());
        req.setTemplateId(t.getTemplateId());
        req.setPhoneNumberSet(new String[]{toE164(phone)});
        req.setTemplateParamSet(new String[]{code});
        try {
            SendSmsResponse resp = client().SendSms(req);
            if (resp.getSendStatusSet() == null || resp.getSendStatusSet().length == 0
                    || !"Ok".equals(resp.getSendStatusSet()[0].getCode())) {
                String msg = (resp.getSendStatusSet() != null && resp.getSendStatusSet().length > 0)
                        ? resp.getSendStatusSet()[0].getMessage() : "无返回";
                log.warn("腾讯云短信发送失败 phone={} msg={}", phone, msg);
                throw new BusinessException(UserErrCode.SMS_SEND_FAILED);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("腾讯云短信调用异常 phone={}", phone, e);
            throw new BusinessException(UserErrCode.SMS_SEND_FAILED);
        }
    }

    private static String toE164(String phone) {
        return phone.startsWith("+") ? phone : "+86" + phone;
    }
}
