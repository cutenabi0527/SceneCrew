package com.ssafy.scenecrew.global.idempotency.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyConfig {
}
