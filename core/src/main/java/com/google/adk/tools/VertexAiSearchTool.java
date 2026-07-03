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
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Retrieval;
import com.google.genai.types.Tool;
import com.google.genai.types.VertexAISearch;
import com.google.genai.types.VertexAISearchDataStoreSpec;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;
import java.util.Optional;

/**
 * A built-in tool using Vertex AI Search.
 *
 * <p>This tool can be configured with either a {@code dataStoreId} (the Vertex AI search data store
 * resource ID) or a {@code searchEngineId} (the Vertex AI search engine resource ID).
 */
@AutoValue
public abstract class VertexAiSearchTool extends BaseTool {
  public abstract Optional<String> dataStoreId();

  public abstract ImmutableList<VertexAISearchDataStoreSpec> dataStoreSpecs();

  public abstract Optional<String> searchEngineId();

  public abstract Optional<String> filter();

  public abstract Optional<Integer> maxResults();

  public abstract Optional<String> project();

  public abstract Optional<String> location();

  public abstract Optional<String> dataStore();

  public static Builder builder() {
    return new AutoValue_VertexAiSearchTool.Builder().dataStoreSpecs(ImmutableList.of());
  }

  VertexAiSearchTool() {
    super("vertex_ai_search", "vertex_ai_search");
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    LlmRequest llmRequest = llmRequestBuilder.build();

    if (llmRequest
        .model()
        .map(model -> !model.startsWith("gemini") && !model.contains("/gemini"))
        .orElse(false)) {
      return Completable.error(
          new IllegalArgumentException(
              "Vertex AI Search tool is only supported for Gemini models."));
    }

    VertexAISearch.Builder vertexAiSearchBuilder = VertexAISearch.builder();
    dataStoreId().ifPresent(vertexAiSearchBuilder::datastore);
    searchEngineId().ifPresent(vertexAiSearchBuilder::engine);
    filter().ifPresent(vertexAiSearchBuilder::filter);
    maxResults().ifPresent(vertexAiSearchBuilder::maxResults);
    if (!dataStoreSpecs().isEmpty()) {
      vertexAiSearchBuilder.dataStoreSpecs(dataStoreSpecs());
    }

    Tool retrievalTool =
        Tool.builder()
            .retrieval(Retrieval.builder().vertexAiSearch(vertexAiSearchBuilder.build()).build())
            .build();
    ImmutableList<Tool> currentTools =
        ImmutableList.<Tool>builder()
            .addAll(
                llmRequest
                    .config()
                    .flatMap(GenerateContentConfig::tools)
                    .orElse(ImmutableList.of()))
            .add(retrievalTool)
            .build();
    GenerateContentConfig newConfig =
        llmRequest
            .config()
            .map(GenerateContentConfig::toBuilder)
            .orElse(GenerateContentConfig.builder())
            .tools(currentTools)
            .build();
    llmRequestBuilder.config(newConfig);
    return Completable.complete();
  }

  /** Builder for {@link VertexAiSearchTool}. */
  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder dataStoreId(String dataStoreId);

    public abstract Builder dataStoreSpecs(List<VertexAISearchDataStoreSpec> dataStoreSpecs);

    public abstract Builder searchEngineId(String searchEngineId);

    public abstract Builder filter(String filter);

    public abstract Builder maxResults(Integer maxResults);

    public abstract Builder project(String project);

    public abstract Builder location(String location);

    public abstract Builder dataStore(String dataStore);

    abstract VertexAiSearchTool autoBuild();

    public final VertexAiSearchTool build() {
      VertexAiSearchTool tool = autoBuild();
      boolean hasDataStoreId =
          tool.dataStoreId().isPresent() && !tool.dataStoreId().get().isEmpty();
      boolean hasSearchEngineId =
          tool.searchEngineId().isPresent() && !tool.searchEngineId().get().isEmpty();
      if (hasDataStoreId == hasSearchEngineId) {
        throw new IllegalArgumentException(
            "One and only one of dataStoreId or searchEngineId must not be empty.");
      }
      boolean hasDataStoreSpecs = !tool.dataStoreSpecs().isEmpty();
      if (hasDataStoreSpecs && !hasSearchEngineId) {
        throw new IllegalArgumentException(
            "searchEngineId must not be empty if dataStoreSpecs is not empty.");
      }
      return tool;
    }
  }
}
