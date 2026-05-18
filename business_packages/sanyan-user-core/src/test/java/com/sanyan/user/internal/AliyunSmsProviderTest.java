package com.sanyan.user.internal;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.sanyan.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AliyunSmsProviderTest {

    @Mock private Client client;

    private AliyunSmsProvider newProvider() {
        return new AliyunSmsProvider(client, "三言", "SMS_123456");
    }

    @Test
    void shouldPassPhoneSignTemplateAndCodeParamToAliyun() throws Exception {
        when(client.sendSms(any())).thenReturn(okResponse());

        newProvider().send("13800138000", "123456");

        ArgumentCaptor<SendSmsRequest> captor = ArgumentCaptor.forClass(SendSmsRequest.class);
        verify(client).sendSms(captor.capture());
        SendSmsRequest req = captor.getValue();
        assertThat(req.getPhoneNumbers()).isEqualTo("13800138000");
        assertThat(req.getSignName()).isEqualTo("三言");
        assertThat(req.getTemplateCode()).isEqualTo("SMS_123456");
        // 模板变量必须叫 code（模板内容用 ${code}），值是 6 位验证码
        assertThat(req.getTemplateParam()).isEqualTo("{\"code\":\"123456\"}");
    }

    @Test
    void shouldThrowProviderFailedWhenAliyunReturnsNonOk() throws Exception {
        // 阿里云 isv.MOBILE_NUMBER_ILLEGAL / isv.BUSINESS_LIMIT_CONTROL 等等都走这条
        SendSmsResponse resp = new SendSmsResponse();
        resp.setBody(new SendSmsResponseBody()
                .setCode("isv.BUSINESS_LIMIT_CONTROL")
                .setMessage("触发分钟级流控"));
        when(client.sendSms(any())).thenReturn(resp);

        assertThatThrownBy(() -> newProvider().send("13800138000", "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(UserErrCode.SMS_PROVIDER_FAILED);
    }

    @Test
    void shouldThrowProviderFailedWhenSdkThrows() throws Exception {
        // 网络 / 签名错误：SDK 直接抛 com.aliyun.tea.TeaException 等
        when(client.sendSms(any())).thenThrow(new RuntimeException("connect timeout"));

        assertThatThrownBy(() -> newProvider().send("13800138000", "123456"))
                .isInstanceOf(BusinessException.class)
                .extracting("errCode").isEqualTo(UserErrCode.SMS_PROVIDER_FAILED);
    }

    private SendSmsResponse okResponse() {
        SendSmsResponse resp = new SendSmsResponse();
        resp.setBody(new SendSmsResponseBody().setCode("OK").setMessage("OK"));
        return resp;
    }
}
