package com.ssafy.scenecrew.aijob.pipeline;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.ssafy.scenecrew.aijob.client.AiServerProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({AiJobProperties.class, AiServerProperties.class})
class AiJobConfig {
}
