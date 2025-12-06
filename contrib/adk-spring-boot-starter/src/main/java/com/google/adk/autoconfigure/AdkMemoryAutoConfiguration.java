package com.google.adk.autoconfigure;

import com.google.adk.autoconfigure.properties.AdkMemoryProperties;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(AdkMemoryProperties.class)
public class AdkMemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BaseMemoryService memoryService(AdkMemoryProperties properties) {
        // VertexAiMemoryService does not exist yet, defaulting to InMemory
        return new InMemoryMemoryService();
    }
}
