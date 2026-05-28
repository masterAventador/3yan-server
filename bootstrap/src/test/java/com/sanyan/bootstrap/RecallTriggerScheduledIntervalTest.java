package com.sanyan.bootstrap;

import com.sanyan.proactive.trigger.RecallTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防止 {@code sanyan.proactive.recall.scan-interval-ms} 配置 key 被 typo 或漏配 ——
 * 该 key 是 dogfood plan4 env override 改 RecallTrigger 扫描间隔的核心入口。
 *
 * <p>两条静态断言，无需 Spring 上下文：
 * ① 生产 application.yml 显式声明该 key 且为正整数；
 * ② RecallTrigger 的 @Scheduled 用 fixedDelayString 引用该 key（否则 env override 失效）。
 */
class RecallTriggerScheduledIntervalTest {

    private static final String SCAN_INTERVAL_KEY = "sanyan.proactive.recall.scan-interval-ms";

    @Test
    void production_yml_should_define_scan_interval_ms_as_positive() {
        // 直接解析 bootstrap 生产 application.yml（failsafe 工作目录 = 模块根 bootstrap/），
        // 不走 Spring Environment，避免 test resources 的 application.yml 屏蔽 main yml。
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();

        assertThat(props)
                .as("应能解析到生产 application.yml")
                .isNotNull();
        String raw = props.getProperty(SCAN_INTERVAL_KEY);
        assertThat(raw)
                .as("生产 application.yml 必须显式定义 %s，否则 plan4 env override 改不到扫描间隔", SCAN_INTERVAL_KEY)
                .isNotNull();
        assertThat(Long.parseLong(raw.trim()))
                .as("默认扫描间隔应为正整数 ms")
                .isPositive();
    }

    @Test
    void recall_trigger_scan_method_should_use_fixed_delay_string() throws Exception {
        Method scan = RecallTrigger.class.getDeclaredMethod("scanAndEnqueue");
        Scheduled annotation = scan.getAnnotation(Scheduled.class);
        assertThat(annotation).as("RecallTrigger.scanAndEnqueue 必须有 @Scheduled 注解").isNotNull();
        assertThat(annotation.fixedDelayString())
                .as("RecallTrigger.scanAndEnqueue 必须用 fixedDelayString 走 property，"
                        + "硬编码 fixedDelay 会让 dogfood env override 失效")
                .contains(SCAN_INTERVAL_KEY);
    }
}
