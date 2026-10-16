/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.models.springai.bridge;

import com.google.adk.tools.BaseTool;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures a {@code List<BaseTool>} exposing every Spring AI {@link ToolCallback} bean in
 * the context as an ADK {@link BaseTool}, wrapped via {@link
 * SpringAiToolCallbackBackedAdkTool#wrapAll(List)}.
 *
 * <p>This spares users the boilerplate of manually calling {@code wrapAll(...)}: with this
 * autoconfig on the classpath, an application can inject {@code List<BaseTool>} named {@code
 * springAiTools} directly into an agent factory.
 *
 * <pre>{@code
 * @Bean
 * public LlmAgent rootAgent(
 *     SpringAI llm,
 *     @Qualifier("springAiTools") List<BaseTool> springAiTools) {
 *   return LlmAgent.builder().name("root").model(llm).tools(springAiTools).build();
 * }
 * }</pre>
 *
 * <p>Active only when at least one {@link ToolCallback} bean exists (e.g. from {@code
 * spring-ai-starter-mcp-client}) — otherwise it stays out of the way.
 */
@AutoConfiguration
@ConditionalOnClass({ToolCallback.class, SpringAiToolCallbackBackedAdkTool.class})
public class SpringAiToolBridgeAutoConfiguration {

  @Bean(name = "springAiTools")
  @ConditionalOnBean(ToolCallback.class)
  @ConditionalOnMissingBean(name = "springAiTools")
  public List<BaseTool> springAiTools(List<ToolCallback> toolCallbacks) {
    return SpringAiToolCallbackBackedAdkTool.wrapAll(toolCallbacks);
  }
}
