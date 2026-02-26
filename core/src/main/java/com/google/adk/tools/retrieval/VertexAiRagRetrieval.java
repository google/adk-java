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

package com.google.adk.tools.retrieval;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.models.LlmRequest;
import com.google.adk.tools.ToolContext;
import com.google.adk.utils.ModelNameUtils;
import com.google.cloud.aiplatform.v1.RagContexts;
import com.google.cloud.aiplatform.v1.RagQuery;
import com.google.cloud.aiplatform.v1.RetrieveContextsRequest;
import com.google.cloud.aiplatform.v1.RetrieveContextsRequest.VertexRagStore.RagResource;
import com.google.cloud.aiplatform.v1.RetrieveContextsResponse;
import com.google.cloud.aiplatform.v1.VertexRagServiceClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Retrieval;
import com.google.genai.types.Tool;
import com.google.genai.types.VertexRagStore;
import com.google.genai.types.VertexRagStoreRagResource;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A retrieval tool that fetches context from Vertex AI RAG.
 *
 * <p>This tool allows to retrieve relevant information based on a query using Vertex AI RAG
 * service. It supports configuration of rag resources and a vector distance threshold.
 */
public class VertexAiRagRetrieval extends BaseRetrievalTool {
  private static final Logger logger = LoggerFactory.getLogger(VertexAiRagRetrieval.class);
  private final VertexRagServiceClient vertexRagServiceClient;
  private final String parent;
  private final List<RagResource> ragResources;
  private final Double vectorDistanceThreshold;
  private final VertexRagStore vertexRagStore;
  private final RetrieveContextsRequest.VertexRagStore apiVertexRagStore;

  public VertexAiRagRetrieval(
      @Nonnull String name,
      @Nonnull String description,
      @Nonnull VertexRagServiceClient vertexRagServiceClient,
      @Nonnull String parent,
      @Nullable List<RagResource> ragResources,
      @Nullable Double vectorDistanceThreshold) {
    super(name, description);
    this.vertexRagServiceClient = vertexRagServiceClient;
    this.parent = parent;
    this.ragResources = ragResources;
    this.vectorDistanceThreshold = vectorDistanceThreshold;

    // For Gemini 2
    VertexRagStore.Builder vertexRagStoreBuilder = VertexRagStore.builder();
    if (this.ragResources != null) {
      vertexRagStoreBuilder.ragResources(
          this.ragResources.stream()
              .map(
                  ragResource ->
                      VertexRagStoreRagResource.builder()
                          .ragCorpus(ragResource.getRagCorpus())
                          .build())
              .collect(toImmutableList()));
    }
    if (this.vectorDistanceThreshold != null) {
      vertexRagStoreBuilder.vectorDistanceThreshold(this.vectorDistanceThreshold);
    }
    this.vertexRagStore = vertexRagStoreBuilder.build();

    // For runAsync
    RetrieveContextsRequest.VertexRagStore.Builder apiVertexRagStoreBuilder =
        RetrieveContextsRequest.VertexRagStore.newBuilder();
    if (this.ragResources != null) {
      apiVertexRagStoreBuilder.addAllRagResources(this.ragResources);
    }
    if (this.vectorDistanceThreshold != null) {
      apiVertexRagStoreBuilder.setVectorDistanceThreshold(this.vectorDistanceThreshold);
    }
    this.apiVertexRagStore = apiVertexRagStoreBuilder.build();
  }

  @Override
  @CanIgnoreReturnValue
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    LlmRequest llmRequest = llmRequestBuilder.build();
    // Use Gemini built-in Vertex AI RAG tool for Gemini models when using Vertex AI API Model
    boolean useVertexAi = Boolean.parseBoolean(System.getenv("GOOGLE_GENAI_USE_VERTEXAI"));
    if (useVertexAi && llmRequest.model().filter(ModelNameUtils::isGeminiModel).isPresent()) {
      GenerateContentConfig config =
          llmRequest.config().orElseGet(() -> GenerateContentConfig.builder().build());
      ImmutableList.Builder<Tool> toolsBuilder = ImmutableList.builder();
      if (config.tools().isPresent()) {
        toolsBuilder.addAll(config.tools().get());
      }
      toolsBuilder.add(
          Tool.builder()
              .retrieval(Retrieval.builder().vertexRagStore(this.vertexRagStore).build())
              .build());
      logger.info(
          "Using Gemini built-in Vertex AI RAG tool for model: {}", llmRequest.model().get());
      llmRequestBuilder.config(config.toBuilder().tools(toolsBuilder.build()).build());
      return Completable.complete();
    } else {
      // Add the function declaration to the tools
      return super.processLlmRequest(llmRequestBuilder, toolContext);
    }
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    String query = (String) args.get("query");
    logger.info("VertexAiRagRetrieval.runAsync called with query: {}", query);
    return Single.fromCallable(
        () -> {
          logger.info("Retrieving context for query: {}", query);
          RetrieveContextsRequest retrieveContextsRequest =
              RetrieveContextsRequest.newBuilder()
                  .setParent(this.parent)
                  .setQuery(RagQuery.newBuilder().setText(query))
                  .setVertexRagStore(this.apiVertexRagStore)
                  .build();
          logger.info("Request to VertexRagService: {}", retrieveContextsRequest);
          RetrieveContextsResponse response =
              this.vertexRagServiceClient.retrieveContexts(retrieveContextsRequest);
          logger.info("Response from VertexRagService: {}", response);
          if (response.getContexts().getContextsList().isEmpty()) {
            logger.warn("No matching result found for query: {}", query);
            return ImmutableMap.of(
                "response",
                String.format(
                    "No matching result found with the config: resources: %s", this.ragResources));
          } else {
            logger.info(
                "Found {} matching results for query: {}",
                response.getContexts().getContextsCount(),
                query);
            ImmutableList<String> contexts =
                response.getContexts().getContextsList().stream()
                    .map(RagContexts.Context::getText)
                    .collect(toImmutableList());
            logger.info("Returning contexts: {}", contexts);
            return ImmutableMap.of("response", contexts);
          }
        });
  }
}
