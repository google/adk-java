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

package com.example.springchat.advanced;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.models.springai.SpringAiToolset;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring configuration for the ADK agent, its tools and the runner. */
@Configuration(proxyBeanMethods = false)
class AgentConfig {

  private static final String APP_NAME = "spring-chat-advanced";

  /**
   * Returns the order support agent. It mixes ADK tools built from {@link OrderService} methods
   * with the Spring AI {@code @Tool} methods of {@link ShippingTools}, exposed as an ADK toolset.
   */
  @Bean
  BaseAgent orderAgent(SpringAI model, OrderService orderService, ShippingTools shippingTools) {
    ImmutableList<Object> tools =
        ImmutableList.of(
            FunctionTool.create(orderService, "getOrderStatus"),
            FunctionTool.create(orderService, "getDeliveryEstimate"),
            new SpringAiToolset(
                MethodToolCallbackProvider.builder().toolObjects(shippingTools).build()));
    return LlmAgent.builder()
        .name("order_support_agent")
        .description("Answers questions about the user's orders.")
        .model(model)
        .instruction(
            "You are a concise customer support assistant. Use the tools to look up orders; never"
                + " guess order details. The order the user last tracked, if any: {"
                + ShippingTools.LAST_TRACKED_ORDER
                + "?}")
        .tools(tools)
        .build();
  }

  @Bean
  Runner runner(BaseAgent orderAgent) {
    return new InMemoryRunner(orderAgent, APP_NAME);
  }
}
