/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.starters.springboot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.web.AgentStaticLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the ADK Spring Boot Starter.
 *
 * <p>These tests use MockMvc to simulate HTTP requests and verify the expected responses from the
 * ADK API server, ensuring that the Spring Boot autoconfiguration works correctly.
 */
@SpringBootTest(properties = "adk.agents.loader=static")
@AutoConfigureMockMvc
public class AdkSpringbootStarterTest {

  @Autowired private AgentStaticLoader agentStaticLoader;

  @Autowired private MockMvc mockMvc;

  @Test
  void testAgentStaticLoaderIsLoaded() throws Exception {
    assertNotNull(agentStaticLoader);
    mockMvc
        .perform(get("/list-apps"))
        .andExpect(status().isOk())
        .andExpect(content().json("[\"test_agent\"]"));
  }

  @SpringBootApplication
  public static class TestApplication {
    public static void main(String[] args) {
      SpringApplication.run(TestApplication.class, args);
    }
  }

  @Configuration
  public static class AppConfig {
    @Bean("agentLoader")
    public AgentStaticLoader agentStaticLoader() {
      BaseAgent testAgent =
          LlmAgent.builder()
              .name("test_agent")
              .model("gemini-2.0-flash-lite")
              .description("Test agent for demonstrating AgentStaticLoader")
              .instruction("You are a test agent.")
              .build();
      return new AgentStaticLoader(testAgent);
    }
  }
}
