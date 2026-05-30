package com.sanyan.user.internal.sms;

import com.sanyan.common.error.BusinessException;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TencentSmsSenderTest {

    private SmsProperties props() {
        SmsProperties p = new SmsProperties();
        p.setProvider("tencent");
        SmsProperties.Tencent t = p.getTencent();
        t.setSecretId("sid");
        t.setSecretKey("skey");
        t.setSdkAppId("1400000000");
        t.setSignName("三言");
        t.setTemplateId("123456");
        return p;
    }

    @Test
    void send_buildsRequestAndSucceedsWhenStatusOk() throws Exception {
        SmsClient client = mock(SmsClient.class);
        SendStatus ok = new SendStatus();
        ok.setCode("Ok");
        SendSmsResponse resp = new SendSmsResponse();
        resp.setSendStatusSet(new SendStatus[]{ok});
        when(client.SendSms(any(SendSmsRequest.class))).thenReturn(resp);

        TencentSmsSender sender = new TencentSmsSender(props()) {
            @Override
            protected SmsClient client() {
                return client;
            }
        };
        sender.send("13800138000", "123456");

        verify(client).SendSms(argThat(req ->
                req.getSmsSdkAppId().equals("1400000000")
                        && req.getSignName().equals("三言")
                        && req.getTemplateId().equals("123456")
                        && req.getPhoneNumberSet()[0].equals("+8613800138000")
                        && req.getTemplateParamSet()[0].equals("123456")));
    }

    @Test
    void send_throwsWhenStatusNotOk() throws Exception {
        SmsClient client = mock(SmsClient.class);
        SendStatus fail = new SendStatus();
        fail.setCode("LimitExceeded");
        fail.setMessage("超频");
        SendSmsResponse resp = new SendSmsResponse();
        resp.setSendStatusSet(new SendStatus[]{fail});
        when(client.SendSms(any(SendSmsRequest.class))).thenReturn(resp);

        TencentSmsSender sender = new TencentSmsSender(props()) {
            @Override
            protected SmsClient client() {
                return client;
            }
        };
        assertThatThrownBy(() -> sender.send("13800138000", "123456")).isInstanceOf(BusinessException.class);
    }

    @Test
    void constructor_failsFastWhenConfigMissing() {
        SmsProperties p = new SmsProperties();
        p.setProvider("tencent"); // tencent 配置全空
        assertThatThrownBy(() -> new TencentSmsSender(p)).isInstanceOf(IllegalStateException.class);
    }
}
