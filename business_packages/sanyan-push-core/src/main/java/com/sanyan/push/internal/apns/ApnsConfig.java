package com.sanyan.push.internal.apns;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ApnsProperties.class)
class ApnsConfig {
}
