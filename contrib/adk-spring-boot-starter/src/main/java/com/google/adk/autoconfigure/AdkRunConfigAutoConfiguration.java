package com.google.adk.autoconfigure;

import com.google.adk.agents.RunConfig;
import com.google.adk.autoconfigure.properties.AdkRunConfigProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AdkRunConfigProperties.class)
public class AdkRunConfigAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RunConfig runConfig(AdkRunConfigProperties properties) {
        return RunConfig.builder()
                .setStreamingMode(properties.getStreamingMode())
                .build();
    }
}
