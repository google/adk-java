package com.google.adk.autoconfigure;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.GcsArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.autoconfigure.properties.AdkArtifactProperties;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AdkArtifactProperties.class)
public class AdkArtifactsAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "adk.artifacts", name = "gcs-enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public Storage googleCloudStorage() {
        return StorageOptions.getDefaultInstance().getService();
    }

    @Bean
    @ConditionalOnMissingBean
    public BaseArtifactService artifactService(AdkArtifactProperties properties) {
        if (properties.isGcsEnabled()) {
            Storage storage = StorageOptions.getDefaultInstance().getService();
            return new GcsArtifactService(properties.getBucketName(), storage);
        }
        return new InMemoryArtifactService();
    }
}
