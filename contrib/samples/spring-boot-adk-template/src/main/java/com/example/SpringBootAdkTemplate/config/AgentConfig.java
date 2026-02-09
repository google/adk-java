package com.example.SpringBootAdkTemplate.config;

import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public LlmAgent llmAgent() {
        return LlmAgent.builder()
                .name("Agent")
                .model("gemini-2.5-flash")
                .instruction("Answer user questions to the best of your knowledge")
                .build();
    }

    @Bean
    public Runner runner(BaseArtifactService artifactService, BaseSessionService sessionService,
                         BaseMemoryService memoryService) {
        return new Runner(
                llmAgent(),
                "appName",
                artifactService,
                sessionService,
                memoryService);
    }
}
