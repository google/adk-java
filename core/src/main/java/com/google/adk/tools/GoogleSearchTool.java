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

import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.GoogleSearchRetrieval;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A built-in tool that is automatically invoked by Gemini 2 and 3 models to retrieve search results
 * from Google Search.
 *
 * <p>This tool operates internally within the model and does not require or perform local code
 * execution.
 *
 * <p>Usage example in an LlmAgent:
 *
 * <pre>{@code
 * LlmAgent agent = LlmAgent.builder()
 *     .addTool(GoogleSearchTool.INSTANCE)
 *     .build();
 * }</pre>
 */
public final class GoogleSearchTool extends BaseTool {
  private static final Logger logger = LoggerFactory.getLogger(GoogleSearchTool.class);
  public static final GoogleSearchTool INSTANCE = new GoogleSearchTool();

  public GoogleSearchTool() {
    super("google_search", "google_search");
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {

    GenerateContentConfig.Builder configBuilder =
        llmRequestBuilder
            .build()
            .config()
            .map(GenerateContentConfig::toBuilder)
            .orElseGet(GenerateContentConfig::builder);

    List<Tool> existingTools = configBuilder.build().tools().orElse(ImmutableList.of());
    ImmutableList.Builder<Tool> updatedToolsBuilder = ImmutableList.builder();
    updatedToolsBuilder.addAll(existingTools);

    String model = llmRequestBuilder.build().model().get();
    if (model != null && model.startsWith("gemini-1")) {
      if (!updatedToolsBuilder.build().isEmpty()) {
        logger.error("Tools already present: {}", configBuilder.build().tools().get());
        return Completable.error(
            new IllegalArgumentException(
                "Google search tool cannot be used with other tools in Gemini 1.x."));
      }
      updatedToolsBuilder.add(
          Tool.builder().googleSearchRetrieval(GoogleSearchRetrieval.builder().build()).build());
      configBuilder.tools(updatedToolsBuilder.build());
    } else if (model != null && (model.startsWith("gemini-2") || model.startsWith("gemini-3"))) {

      updatedToolsBuilder.add(Tool.builder().googleSearch(GoogleSearch.builder().build()).build());
      configBuilder.tools(updatedToolsBuilder.build());
    } else {
      return Completable.error(
          new IllegalArgumentException("Google search tool is not supported for model " + model));
    }

    llmRequestBuilder.config(configBuilder.build());
    return Completable.complete();
  }
}
