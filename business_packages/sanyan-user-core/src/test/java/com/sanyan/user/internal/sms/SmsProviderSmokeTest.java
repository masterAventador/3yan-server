package com.sanyan.user.internal.sms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * provider 切换的启动期冒烟测试（不连真腾讯云）：
 * 用 ApplicationContextRunner 在 Spring 容器装配层面验证 @ConditionalOnProperty 行为，
 * 覆盖纯构造测试（TencentSmsSenderTest）触及不到的"启动期生效"这一层。
 */
class SmsProviderSmokeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SmsConfig.class, StubSmsSender.class, TencentSmsSender.class);

    @Test
    void providerMissing_activatesStubOnly() {
        // dev 默认：provider 缺省（matchIfMissing=true）→ 仅 StubSmsSender 激活，TencentSmsSender 不激活
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SmsSender.class);
            assertThat(ctx).hasSingleBean(StubSmsSender.class);
            assertThat(ctx).doesNotHaveBean(TencentSmsSender.class);
        });
    }

    @Test
    void providerStub_activatesStubOnly() {
        runner.withPropertyValues("sanyan.sms.provider=stub").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(StubSmsSender.class);
            assertThat(ctx).doesNotHaveBean(TencentSmsSender.class);
        });
    }

    @Test
    void providerTencentWithoutKeys_failsContextStartup() {
        // prod 误配 fail-fast：provider=tencent 但密钥缺失 → TencentSmsSender 构造抛 IllegalStateException
        // → bean 创建失败 → 上下文启动失败（绝不退回 stub）
        runner.withPropertyValues("sanyan.sms.provider=tencent").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx).getFailure().hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }
}
