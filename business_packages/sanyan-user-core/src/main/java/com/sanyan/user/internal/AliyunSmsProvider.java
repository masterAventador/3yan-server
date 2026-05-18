package com.sanyan.user.internal;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.sanyan.common.error.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信 SmsProvider：调用 dysmsapi.SendSms 真发短信。
 *
 * <p>{@code sanyan.sms.provider=aliyun} 时激活。激活前需在 application.yml / .env 配齐 4 个值：
 * <ul>
 *   <li>{@code sanyan.sms.aliyun.access-key-id}：阿里云 RAM 用户的 AccessKey ID</li>
 *   <li>{@code sanyan.sms.aliyun.access-key-secret}：对应 Secret（千万别提交进 git）</li>
 *   <li>{@code sanyan.sms.aliyun.sign-name}：在阿里云控制台审核通过的短信签名（如 "三言"）</li>
 *   <li>{@code sanyan.sms.aliyun.template-code}：审核通过的模板 CODE（形如 SMS_xxx），模板内容必须含 {@code ${code}} 变量</li>
 * </ul>
 *
 * <p>错误处理：阿里云 SDK 调用本身可能抛 {@code com.aliyun.tea.TeaException}（网络 / 签名错误），
 * 业务失败由 response body 里的 {@code Code} 字段表示（"OK" 才是成功）。两类失败都统一转
 * {@link UserErrCode#SMS_PROVIDER_FAILED}，让用户看到友好的"请稍后重试"，server log 留详细原因。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "sanyan.sms.provider", havingValue = "aliyun")
public class AliyunSmsProvider implements SmsProvider {

    private final Client client;
    private final String signName;
    private final String templateCode;

    public AliyunSmsProvider(
            @Value("${sanyan.sms.aliyun.access-key-id}") String accessKeyId,
            @Value("${sanyan.sms.aliyun.access-key-secret}") String accessKeySecret,
            @Value("${sanyan.sms.aliyun.sign-name}") String signName,
            @Value("${sanyan.sms.aliyun.template-code}") String templateCode,
            @Value("${sanyan.sms.aliyun.endpoint:dysmsapi.aliyuncs.com}") String endpoint
    ) {
        this.signName = signName;
        this.templateCode = templateCode;
        try {
            this.client = new Client(new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(endpoint));
        } catch (Exception e) {
            // 构造失败一般是 endpoint/凭证格式不对——直接 fail-fast 让 context 起不来，避免运行时才暴
            throw new IllegalStateException("初始化阿里云短信客户端失败", e);
        }
    }

    AliyunSmsProvider(Client client, String signName, String templateCode) {
        this.client = client;
        this.signName = signName;
        this.templateCode = templateCode;
    }

    @Override
    public void send(String phone, String code) {
        SendSmsRequest req = new SendSmsRequest()
                .setPhoneNumbers(phone)
                .setSignName(signName)
                .setTemplateCode(templateCode)
                .setTemplateParam("{\"code\":\"" + code + "\"}");
        SendSmsResponseBody body;
        try {
            SendSmsResponse resp = client.sendSms(req);
            body = resp.getBody();
        } catch (Exception e) {
            log.error("阿里云短信调用异常 phone={}", phone, e);
            throw new BusinessException(UserErrCode.SMS_PROVIDER_FAILED);
        }
        if (body == null || !"OK".equals(body.getCode())) {
            log.error("阿里云短信发送失败 phone={} code={} message={}",
                    phone,
                    body == null ? "null" : body.getCode(),
                    body == null ? "null body" : body.getMessage());
            throw new BusinessException(UserErrCode.SMS_PROVIDER_FAILED);
        }
    }
}
