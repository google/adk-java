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

package com.google.adk.codeexecutors;

import com.google.adk.agents.InvocationContext;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.utils.ModelNameUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolCodeExecution;

/**
 * A code executor that uses the Model's built-in code executor.
 *
 * <p>Currently only supports Gemini 2.0+ models, but will be expanded to other models.
 */
public class BuiltInCodeExecutor extends BaseCodeExecutor {

  @Override
  public CodeExecutionResult executeCode(
      InvocationContext invocationContext, CodeExecutionInput codeExecutionInput) {
    throw new UnsupportedOperationException(
        "Code execution is not supported for built-in code executor.");
  }

  /** Pre-process the LLM request for Gemini 2.0+ models to use the code execution tool. */
  public void processLlmRequest(LlmRequest.Builder llmRequestBuilder) {
    LlmRequest llmRequest = llmRequestBuilder.build();
    if (ModelNameUtils.isGemini2Model(llmRequest.model().orElse(null))) {
      GenerateContentConfig.Builder configBuilder =
          llmRequest.config().map(c -> c.toBuilder()).orElseGet(GenerateContentConfig::builder);
      ImmutableList.Builder<Tool> toolsBuilder = ImmutableList.<Tool>builder();
      llmRequest.config().ifPresent(c -> c.tools().ifPresent(toolsBuilder::addAll));
      toolsBuilder.add(Tool.builder().codeExecution(ToolCodeExecution.builder().build()).build());
      configBuilder.tools(toolsBuilder.build());
      llmRequestBuilder.config(configBuilder.build());
      return;
    }
    throw new IllegalArgumentException(
        "Gemini code execution tool is not supported for model " + llmRequest.model().orElse(""));
  }
}
