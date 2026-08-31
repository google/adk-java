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
package com.google.adk.models.springai;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import java.util.Map;

/**
 * Resolves the ADK {@link ToolContext} used when Spring AI owns tool execution.
 *
 * <p>Spring AI's tool context does not contain ADK's invocation state. Applications opting into
 * {@link ToolExecutionMode#SPRING_AI_MANAGED} must therefore supply this resolver explicitly.
 */
@FunctionalInterface
public interface AdkToolContextResolver {

  /**
   * Resolves a non-null ADK tool context for one tool invocation.
   *
   * @param tool the ADK tool being called
   * @param arguments the decoded tool arguments
   * @param springAiToolContext the context supplied by Spring AI; it may be {@code null} when a
   *     callback is invoked directly without a context
   * @return the ADK context to pass to the tool; must not be {@code null}
   */
  ToolContext resolve(
      BaseTool tool,
      Map<String, Object> arguments,
      org.springframework.ai.chat.model.ToolContext springAiToolContext);
}
