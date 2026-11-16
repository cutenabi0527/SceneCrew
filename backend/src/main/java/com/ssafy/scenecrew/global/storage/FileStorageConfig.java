package com.ssafy.scenecrew.global.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(FileStorageProperties.class)
class FileStorageConfig {
}
