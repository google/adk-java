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

package com.google.adk.tools;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRequest;
import com.google.adk.utils.ModelNameUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolCodeExecution;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A built-in code execution tool that is automatically invoked by Gemini 2 models.
 *
 * <p>This tool operates internally within the model and does not require or perform local code
 * execution.
 */
public final class BuiltInCodeExecutionTool extends BaseTool {
  public static final BuiltInCodeExecutionTool INSTANCE = new BuiltInCodeExecutionTool();
  private static final Logger LOG = LoggerFactory.getLogger(BuiltInCodeExecutionTool.class);

  public BuiltInCodeExecutionTool() {
    super("code_execution", "code_execution");
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {

    Optional<BaseLlm> model =
        Optional.ofNullable(toolContext)
            .flatMap(tCtx -> Optional.ofNullable(tCtx.invocationContext()))
            .flatMap(
                iCtx -> {
                  if (iCtx.agent() instanceof LlmAgent llmAgent) {
                    return Optional.of(llmAgent);
                  } else {
                    return Optional.empty();
                  }
                })
            .flatMap(llmAgent -> llmAgent.resolvedModel().model());

    String modelName = llmRequestBuilder.build().model().get();
    if (!ModelNameUtils.isGeminiModel(modelName)
        || model.filter(ModelNameUtils::isInstanceOfGemini).isEmpty()) {
      // model name is not a gemini model, or the model isn't an instance of Gemini class (eg.
      // LangChain case).
      LOG.warn(
          "Code execution tool is not supported for model: {} ({}).",
          modelName,
          model.map(Object::getClass).map(Class::toString).orElse("<unknown class>"));
    }
    GenerateContentConfig.Builder configBuilder =
        llmRequestBuilder
            .build()
            .config()
            .map(GenerateContentConfig::toBuilder)
            .orElseGet(GenerateContentConfig::builder);

    List<Tool> existingTools = configBuilder.build().tools().orElse(ImmutableList.of());
    ImmutableList.Builder<Tool> updatedToolsBuilder = ImmutableList.builder();
    updatedToolsBuilder
        .addAll(existingTools)
        .add(Tool.builder().codeExecution(ToolCodeExecution.builder().build()).build());
    configBuilder.tools(updatedToolsBuilder.build());
    llmRequestBuilder.config(configBuilder.build());
    return Completable.complete();
  }
}
