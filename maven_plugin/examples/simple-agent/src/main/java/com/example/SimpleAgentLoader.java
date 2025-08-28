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

package com.example;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.web.AgentLoader;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

/** Example AgentLoader that creates simple agents for demonstration. */
@ThreadSafe
public class SimpleAgentLoader implements AgentLoader {

  public static final SimpleAgentLoader INSTANCE = new SimpleAgentLoader();

  private final ImmutableMap<String, Supplier<BaseAgent>> agentSuppliers;

  public SimpleAgentLoader() {
    this.agentSuppliers =
        ImmutableMap.of(
            "chat_assistant", Suppliers.memoize(this::createChatAssistant),
            "search_agent", Suppliers.memoize(this::createSearchAgent),
            "code_helper", Suppliers.memoize(this::createCodeHelper));
  }

  @Override
  @Nonnull
  public ImmutableList<String> listAgents() {
    return ImmutableList.copyOf(agentSuppliers.keySet());
  }

  @Override
  public BaseAgent loadAgent(String name) {
    Supplier<BaseAgent> supplier = agentSuppliers.get(name);
    if (supplier == null) {
      throw new NoSuchElementException("Agent not found: " + name);
    }
    return supplier.get();
  }

  private BaseAgent createChatAssistant() {
    return LlmAgent.builder()
        .name("chat_assistant")
        .description("A friendly chat assistant")
        .model("gemini-2.0-flash")
        .instruction(
            "You are a helpful and friendly assistant. "
                + "Answer questions clearly and concisely. "
                + "Be encouraging and positive in your responses.")
        .build();
  }

  private BaseAgent createSearchAgent() {
    return LlmAgent.builder()
        .name("search_agent")
        .description("An agent that can search the web")
        .model("gemini-2.0-flash")
        .instruction(
            "You are a search assistant. "
                + "Use Google Search to find current information when users ask questions. "
                + "Provide accurate and up-to-date responses based on your search results.")
        .tools(new GoogleSearchTool())
        .build();
  }

  private BaseAgent createCodeHelper() {
    return LlmAgent.builder()
        .name("code_helper")
        .description("A coding assistant")
        .model("gemini-2.0-flash")
        .instruction(
            "You are a coding assistant. "
                + "Help users with programming questions, code reviews, and debugging. "
                + "Provide clear explanations and well-formatted code examples. "
                + "Support multiple programming languages including Java, Python, JavaScript, etc.")
        .build();
  }
}
