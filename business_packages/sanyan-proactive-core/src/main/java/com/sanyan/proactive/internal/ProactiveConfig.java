package com.sanyan.proactive.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 激活 {@link ProactiveProperties} 的 @ConfigurationProperties 绑定。
 * ProactiveProperties 本身不加 @Component，由本类统一注册，与 character-core 的 IntimacyConfig 模式一致。
 */
@Configuration
@EnableConfigurationProperties(ProactiveProperties.class)
class ProactiveConfig {
}
