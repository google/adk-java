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
package com.google.adk.models.springai;

import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Spring AI embedding model wrapper that provides ADK-compatible embedding generation.
 *
 * <p>This wrapper allows Spring AI embedding models to be used within the ADK framework by
 * providing reactive embedding generation with observability and error handling.
 */
public class SpringAIEmbedding {

  private final EmbeddingModel embeddingModel;
  private final String modelName;
  private final SpringAIObservabilityHandler observabilityHandler;

  public SpringAIEmbedding(EmbeddingModel embeddingModel) {
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel cannot be null");
    this.modelName = extractModelName(embeddingModel);
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAIEmbedding(EmbeddingModel embeddingModel, String modelName) {
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel cannot be null");
    this.modelName = Objects.requireNonNull(modelName, "model name cannot be null");
    this.observabilityHandler =
        new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
  }

  public SpringAIEmbedding(
      EmbeddingModel embeddingModel,
      String modelName,
      SpringAIProperties.Observability observabilityConfig) {
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel cannot be null");
    this.modelName = Objects.requireNonNull(modelName, "model name cannot be null");
    this.observabilityHandler =
        new SpringAIObservabilityHandler(
            Objects.requireNonNull(observabilityConfig, "observabilityConfig cannot be null"));
  }

  /**
   * Generate embeddings for a single text input.
   *
   * @param text The input text to embed
   * @return Single emitting the embedding vector
   */
  public Single<float[]> embed(String text) {
    SpringAIObservabilityHandler.RequestContext context =
        observabilityHandler.startRequest(modelName, "embedding");

    return Single.fromCallable(
            () -> {
              observabilityHandler.logRequest(text, modelName);
              float[] embedding = embeddingModel.embed(text);
              observabilityHandler.logResponse(
                  "Embedding vector (dimensions: " + embedding.length + ")", modelName);
              return embedding;
            })
        .doOnSuccess(
            embedding -> {
              observabilityHandler.recordSuccess(context, 0, 0, 0);
            })
        .doOnError(
            error -> {
              observabilityHandler.recordError(context, error);
            })
        .onErrorResumeNext(
            error -> {
              SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(error);
              return Single.error(new RuntimeException(mappedError.getNormalizedMessage(), error));
            });
  }

  /**
   * Generate embeddings for multiple text inputs.
   *
   * @param texts The input texts to embed
   * @return Single emitting the list of embedding vectors
   */
  public Single<List<float[]>> embed(List<String> texts) {
    SpringAIObservabilityHandler.RequestContext context =
        observabilityHandler.startRequest(modelName, "batch_embedding");

    return Single.fromCallable(
            () -> {
              observabilityHandler.logRequest(
                  "Batch embedding request (" + texts.size() + " texts)", modelName);
              List<float[]> embeddings = embeddingModel.embed(texts);
              observabilityHandler.logResponse(
                  "Batch embedding response (" + embeddings.size() + " embeddings)", modelName);
              return embeddings;
            })
        .doOnSuccess(
            embeddings -> {
              observabilityHandler.recordSuccess(context, 0, 0, 0);
            })
        .doOnError(
            error -> {
              observabilityHandler.recordError(context, error);
            })
        .onErrorResumeNext(
            error -> {
              SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(error);
              return Single.error(new RuntimeException(mappedError.getNormalizedMessage(), error));
            });
  }

  /**
   * Generate embeddings using a full EmbeddingRequest.
   *
   * @param request The embedding request
   * @return Single emitting the embedding response
   */
  public Single<EmbeddingResponse> embedForResponse(EmbeddingRequest request) {
    SpringAIObservabilityHandler.RequestContext context =
        observabilityHandler.startRequest(modelName, "embedding_request");

    return Single.fromCallable(
            () -> {
              observabilityHandler.logRequest(request.toString(), modelName);
              EmbeddingResponse response = embeddingModel.call(request);
              observabilityHandler.logResponse(
                  "Embedding response (" + response.getResults().size() + " results)", modelName);
              return response;
            })
        .doOnSuccess(
            response -> {
              // Extract token usage if available
              int totalTokens = 0;
              if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                totalTokens = response.getMetadata().getUsage().getTotalTokens();
              }
              observabilityHandler.recordSuccess(context, totalTokens, totalTokens, 0);
            })
        .doOnError(
            error -> {
              observabilityHandler.recordError(context, error);
            })
        .onErrorResumeNext(
            error -> {
              SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(error);
              return Single.error(new RuntimeException(mappedError.getNormalizedMessage(), error));
            });
  }

  /**
   * Get the embedding dimensions for this model.
   *
   * @return The number of dimensions in the embedding vectors
   */
  public int dimensions() {
    return embeddingModel.dimensions();
  }

  /**
   * Get the model name.
   *
   * @return The model name
   */
  public String modelName() {
    return modelName;
  }

  /**
   * Get the underlying Spring AI embedding model.
   *
   * @return The Spring AI EmbeddingModel instance
   */
  public EmbeddingModel getEmbeddingModel() {
    return embeddingModel;
  }

  private static String extractModelName(EmbeddingModel model) {
    // Spring AI models may not always have a straightforward way to get model name
    // This is a fallback that can be overridden by providing explicit model name
    String className = model.getClass().getSimpleName();
    return className.toLowerCase().replace("embeddingmodel", "").replace("model", "");
  }

  private SpringAIProperties.Observability createDefaultObservabilityConfig() {
    SpringAIProperties.Observability config = new SpringAIProperties.Observability();
    config.setEnabled(true);
    config.setMetricsEnabled(true);
    config.setIncludeContent(false); // Don't log embedding content by default for privacy
    return config;
  }
}
