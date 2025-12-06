package com.google.adk.autoconfigure;

import com.google.adk.autoconfigure.properties.AdkSessionProperties;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.VertexAiSessionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import java.util.Optional;

@AutoConfiguration
@EnableConfigurationProperties(AdkSessionProperties.class)
public class AdkSessionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BaseSessionService sessionService(AdkSessionProperties properties) {
        if (properties.getType() == AdkSessionProperties.Type.VERTEX_AI) {
            return new VertexAiSessionService(
                    properties.getProjectId(),
                    properties.getLocation(),
                    Optional.empty(),
                    Optional.empty());
        }
        return new InMemorySessionService();
    }
}
