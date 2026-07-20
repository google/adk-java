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

package com.google.adk.models;

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static net.javacrumbs.futureconverter.java8guava.FutureConverter.toListenableFuture;

import com.google.adk.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the GPT OSS Generative AI model.
 *
 * <p>This class provides methods for interacting with the GPT OSS model, including standard
 * request-response generation and establishing persistent bidirectional connections.
 */
public class GptOssLlm extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(GptOssLlm.class);
  private static final ImmutableMap<String, String> TRACKING_HEADERS;

  static {
    String frameworkLabel = "google-adk/" + Version.JAVA_ADK_VERSION;
    String languageLabel = "gl-java/" + JAVA_VERSION.value();
    String versionHeaderValue = String.format("%s %s", frameworkLabel, languageLabel);

    TRACKING_HEADERS =
        ImmutableMap.of(
            "x-goog-api-client", versionHeaderValue,
            "user-agent", versionHeaderValue);
  }

  private final Client apiClient;

  /**
   * Constructs a new GptOssLlm instance.
   *
   * @param modelName The name of the GPT OSS model to use (e.g., "gpt-oss-4").
   * @param apiClient The genai {@link com.google.genai.Client} instance for making API calls.
   */
  public GptOssLlm(String modelName, Client apiClient) {
    super(modelName);
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient cannot be null");
  }

  /**
   * Constructs a new GptOssLlm instance with an API key.
   *
   * @param modelName The name of the GPT OSS model to use (e.g., "gpt-oss-4").
   */
  public GptOssLlm(String modelName) {
    super(modelName);
    this.apiClient =
        Client.builder()
            .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
            .build();
  }

  /**
   * Constructs a new GptOssLlm instance with Vertex AI credentials.
   *
   * @param modelName The name of the GPT OSS model to use (e.g., "gpt-oss-4").
   * @param vertexCredentials The Vertex AI credentials to access the model.
   */
  //   public GptOssLlm(String modelName, VertexCredentials vertexCredentials) {
  //     super(modelName);
  //     Objects.requireNonNull(vertexCredentials, "vertexCredentials cannot be null");
  //     Client.Builder apiClientBuilder =
  //         Client.builder().httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build());
  //     vertexCredentials.project().ifPresent(apiClientBuilder::project);
  //     vertexCredentials.location().ifPresent(apiClientBuilder::location);
  //     vertexCredentials.credentials().ifPresent(apiClientBuilder::credentials);
  //     this.apiClient = apiClientBuilder.build();
  //   }

  /**
   * Returns a new Builder instance for constructing GptOssLlm objects. Note that when building a
   * GptOssLlm object, at least one of apiKey, vertexCredentials, or an explicit apiClient must be
   * set. If multiple are set, the explicit apiClient will take precedence.
   *
   * @return A new {@link Builder}.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link GptOssLlm}. */
  public static class Builder {
    private String modelName;
    private Client apiClient;

    private Builder() {}

    /**
     * Sets the name of the GPT OSS model to use.
     *
     * @param modelName The model name (e.g., "gpt-oss-4").
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    /**
     * Sets the explicit {@link com.google.genai.Client} instance for making API calls. If this is
     * set, apiKey and vertexCredentials will be ignored.
     *
     * @param apiClient The client instance.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder apiClient(Client apiClient) {
      this.apiClient = apiClient;
      return this;
    }

    /**
     * Builds the {@link GptOssLlm} instance.
     *
     * @return A new {@link GptOssLlm} instance.
     * @throws NullPointerException if modelName is null.
     */
    public GptOssLlm build() {
      Objects.requireNonNull(modelName, "modelName must be set.");

      if (apiClient != null) {
        return new GptOssLlm(modelName, apiClient);
      } else {
        return new GptOssLlm(
            modelName,
            Client.builder()
                .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
                .build());
      }
    }
  }

  private static <T> Single<T> toSingle(ListenableFuture<T> future, Scheduler scheduler) {
    return Single.create(
        emitter -> {
          future.addListener(
              () -> {
                try {
                  emitter.onSuccess(Futures.getDone(future));
                } catch (ExecutionException e) {
                  emitter.onError(e.getCause());
                }
              },
              scheduler::scheduleDirect);

          emitter.setCancellable(() -> future.cancel(false));
        });
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    // Prepare the request - you may need to create a GptOssUtil similar to GeminiUtil
    // For now, using the request as-is
    llmRequest =
        GeminiUtil.prepareGenenerateContentRequest(
            llmRequest, !apiClient.vertexAI(), /* stripThoughts= */ false);
    GenerateContentConfig config = llmRequest.config().orElse(null);
    String effectiveModelName = llmRequest.model().orElse(model());

    logger.trace("Request Contents: {}", llmRequest.contents());
    logger.trace("Request Config: {}", config);

    if (stream) {
      logger.debug("Sending streaming generateContent request to model {}", effectiveModelName);
      ListenableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
          toListenableFuture(
              apiClient.async.models.generateContentStream(
                  effectiveModelName, llmRequest.contents(), config));

      return Flowable.defer(
              () ->
                  processRawResponses(
                      toSingle(streamFuture, Schedulers.io())
                          .toFlowable()
                          .flatMapIterable(iterable -> iterable)))
          .filter(
              llmResponse ->
                  llmResponse
                      .content()
                      .flatMap(Content::parts)
                      .map(
                          parts ->
                              !parts.isEmpty()
                                  && parts.stream()
                                      .anyMatch(
                                          p ->
                                              p.functionCall().isPresent()
                                                  || p.functionResponse().isPresent()
                                                  || p.text().map(t -> !t.isBlank()).orElse(false)))
                      .orElse(false));
    } else {
      logger.debug("Sending generateContent request to model {}", effectiveModelName);
      final LlmRequest finalLlmRequest = llmRequest;
      return toSingle(
              toListenableFuture(
                  apiClient
                      .async
                      .models
                      .generateContent(effectiveModelName, finalLlmRequest.contents(), config)
                      .thenApplyAsync(LlmResponse::create)),
              Schedulers.io())
          .toFlowable();
    }
  }

  static Flowable<LlmResponse> processRawResponses(Flowable<GenerateContentResponse> rawResponses) {
    final StringBuilder accumulatedText = new StringBuilder();
    final StringBuilder accumulatedThoughtText = new StringBuilder();
    // Array to bypass final local variable reassignment in lambda.
    final GenerateContentResponse[] lastRawResponseHolder = {null};
    return rawResponses
        .concatMap(
            rawResponse -> {
              lastRawResponseHolder[0] = rawResponse;
              logger.trace("Raw streaming response: {}", rawResponse);

              List<LlmResponse> responsesToEmit = new ArrayList<>();
              LlmResponse currentProcessedLlmResponse = LlmResponse.create(rawResponse);
              Optional<Part> part = GeminiUtil.getPart0FromLlmResponse(currentProcessedLlmResponse);
              String currentTextChunk = part.flatMap(Part::text).orElse("");

              if (!currentTextChunk.isBlank()) {
                if (part.get().thought().orElse(false)) {
                  accumulatedThoughtText.append(currentTextChunk);
                  responsesToEmit.add(
                      thinkingResponseFromText(currentTextChunk).toBuilder().partial(true).build());
                } else {
                  accumulatedText.append(currentTextChunk);
                  responsesToEmit.add(
                      responseFromText(currentTextChunk).toBuilder().partial(true).build());
                }
              } else {
                if (accumulatedThoughtText.length() > 0
                    && GeminiUtil.shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
                  LlmResponse aggregatedThoughtResponse =
                      thinkingResponseFromText(accumulatedThoughtText.toString());
                  responsesToEmit.add(aggregatedThoughtResponse);
                  accumulatedThoughtText.setLength(0);
                }
                if (accumulatedText.length() > 0
                    && GeminiUtil.shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
                  LlmResponse aggregatedTextResponse = responseFromText(accumulatedText.toString());
                  responsesToEmit.add(aggregatedTextResponse);
                  accumulatedText.setLength(0);
                }
                responsesToEmit.add(currentProcessedLlmResponse);
              }
              logger.debug("Responses to emit: {}", responsesToEmit);
              return Flowable.fromIterable(responsesToEmit);
            })
        .concatWith(
            Flowable.defer(
                () -> {
                  GenerateContentResponse finalRawResp = lastRawResponseHolder[0];
                  if (finalRawResp == null) {
                    return Flowable.empty();
                  }
                  boolean isStop =
                      finalRawResp
                          .candidates()
                          .flatMap(candidates -> candidates.stream().findFirst())
                          .flatMap(Candidate::finishReason)
                          .map(finishReason -> finishReason.knownEnum() == FinishReason.Known.STOP)
                          .orElse(false);

                  if (isStop) {
                    List<LlmResponse> finalResponses = new ArrayList<>();
                    if (accumulatedThoughtText.length() > 0) {
                      finalResponses.add(
                          thinkingResponseFromText(accumulatedThoughtText.toString()));
                    }
                    if (accumulatedText.length() > 0) {
                      finalResponses.add(responseFromText(accumulatedText.toString()));
                    }
                    return Flowable.fromIterable(finalResponses);
                  }
                  return Flowable.empty();
                }));
  }

  private static LlmResponse responseFromText(String accumulatedText) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(Part.fromText(accumulatedText)).build())
        .build();
  }

  private static LlmResponse thinkingResponseFromText(String accumulatedThoughtText) {
    return LlmResponse.builder()
        .content(
            Content.builder()
                .role("model")
                .parts(Part.fromText(accumulatedThoughtText).toBuilder().thought(true).build())
                .build())
        .build();
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    if (!apiClient.vertexAI()) {
      llmRequest = GeminiUtil.sanitizeRequestForGeminiApi(llmRequest);
    }
    logger.debug("Establishing GPT OSS connection.");
    LiveConnectConfig liveConnectConfig = llmRequest.liveConnectConfig();
    String effectiveModelName = llmRequest.model().orElse(model());

    logger.debug("Connecting to model {}", effectiveModelName);
    logger.trace("Connection Config: {}", liveConnectConfig);

    return new GeminiLlmConnection(apiClient, effectiveModelName, liveConnectConfig);
  }
}
