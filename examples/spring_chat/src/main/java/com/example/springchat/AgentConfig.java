/*
 * Copyright 2026 Google LLC
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

package com.example.springchat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for the ADK agent and runner. */
@Configuration(proxyBeanMethods = false)
class AgentConfig {

  private static final String APP_NAME = "spring-chat";

  /**
   * Returns the chat agent. Its {@link SpringAI} model is auto-configured by the ADK Spring AI
   * integration from whichever Spring AI {@code ChatModel} starter is on the classpath.
   */
  @Bean
  BaseAgent chatAgent(SpringAI model) {
    return LlmAgent.builder()
        .name("spring_chat_agent")
        .description("A friendly general-purpose chat assistant.")
        .model(model)
        .instruction("You are a friendly, concise assistant. Answer the user's questions clearly.")
        .build();
  }

  @Bean
  Runner runner(BaseAgent chatAgent) {
    return new InMemoryRunner(chatAgent, APP_NAME);
  }
}
