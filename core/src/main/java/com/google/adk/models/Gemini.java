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

import com.google.adk.Version;
import com.google.common.collect.ImmutableMap;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents the Gemini Generative AI model.
 *
 * <p>This class provides methods for interacting with the Gemini model, including standard
 * request-response generation and establishing persistent bidirectional connections.
 */
public class Gemini extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(Gemini.class);
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
   * Constructs a new Gemini instance.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiClient The genai {@link com.google.genai.Client} instance for making API calls.
   */
  public Gemini(String modelName, Client apiClient) {
    super(modelName);
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient cannot be null");
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiKey The Google Gemini API key.
   */
  public Gemini(String modelName, String apiKey) {
    super(modelName);
    Objects.requireNonNull(apiKey, "apiKey cannot be null");
    this.apiClient =
        Client.builder()
            .apiKey(apiKey)
            .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
            .build();
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param vertexCredentials The Vertex AI credentials to access the Gemini model.
   */
  public Gemini(String modelName, VertexCredentials vertexCredentials) {
    super(modelName);
    Objects.requireNonNull(vertexCredentials, "vertexCredentials cannot be null");
    Client.Builder apiClientBuilder =
        Client.builder().httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build());
    vertexCredentials.project().ifPresent(apiClientBuilder::project);
    vertexCredentials.location().ifPresent(apiClientBuilder::location);
    vertexCredentials.credentials().ifPresent(apiClientBuilder::credentials);
    this.apiClient = apiClientBuilder.build();
  }

  /**
   * Returns a new Builder instance for constructing Gemini objects. Note that when building a
   * Gemini object, at least one of apiKey, vertexCredentials, or an explicit apiClient must be set.
   * If multiple are set, the explicit apiClient will take precedence.
   *
   * @return A new {@link Builder}.
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link Gemini}. */
  public static class Builder {
    private String modelName;
    private Client apiClient;
    private String apiKey;
    private VertexCredentials vertexCredentials;

    private Builder() {}

    /**
     * Sets the name of the Gemini model to use.
     *
     * @param modelName The model name (e.g., "gemini-2.0-flash").
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
     * Sets the Google Gemini API key. If {@link #apiClient(Client)} is also set, the explicit
     * client will take precedence. If {@link #vertexCredentials(VertexCredentials)} is also set,
     * this apiKey will take precedence.
     *
     * @param apiKey The API key.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the Vertex AI credentials. If {@link #apiClient(Client)} or {@link #apiKey(String)} are
     * also set, they will take precedence over these credentials.
     *
     * @param vertexCredentials The Vertex AI credentials.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder vertexCredentials(VertexCredentials vertexCredentials) {
      this.vertexCredentials = vertexCredentials;
      return this;
    }

    /**
     * Builds the {@link Gemini} instance.
     *
     * @return A new {@link Gemini} instance.
     * @throws NullPointerException if modelName is null.
     */
    public Gemini build() {
      Objects.requireNonNull(modelName, "modelName must be set.");

      if (apiClient != null) {
        return new Gemini(modelName, apiClient);
      } else if (apiKey != null) {
        return new Gemini(modelName, apiKey);
      } else if (vertexCredentials != null) {
        return new Gemini(modelName, vertexCredentials);
      } else {
        return new Gemini(
            modelName,
            Client.builder()
                .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
                .build());
      }
    }
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    llmRequest = GeminiUtil.prepareGenenerateContentRequest(llmRequest, !apiClient.vertexAI());
    GenerateContentConfig config = llmRequest.config().orElse(null);
    String effectiveModelName = llmRequest.model().orElse(model());

    logger.trace("Request Contents: {}", llmRequest.contents());
    logger.trace("Request Config: {}", config);

    if (stream) {
      logger.debug("Sending streaming generateContent request to model {}", effectiveModelName);
      CompletableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
          apiClient.async.models.generateContentStream(
              effectiveModelName, llmRequest.contents(), config);

      return Flowable.defer(
          () ->
              processRawResponses(
                  Flowable.fromFuture(streamFuture).flatMapIterable(iterable -> iterable)));
    } else {
      logger.debug("Sending generateContent request to model {}", effectiveModelName);
      return Flowable.fromFuture(
          apiClient
              .async
              .models
              .generateContent(effectiveModelName, llmRequest.contents(), config)
              .thenApplyAsync(LlmResponse::create));
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

              if (!currentTextChunk.isEmpty()) {
                if (part.get().thought().orElse(false)) {
                  accumulatedThoughtText.append(currentTextChunk);
                } else {
                  accumulatedText.append(currentTextChunk);
                }
                LlmResponse partialResponse =
                    currentProcessedLlmResponse.toBuilder().partial(true).build();
                responsesToEmit.add(partialResponse);
              } else {
                if (accumulatedThoughtText.length() > 0
                    && GeminiUtil.shouldEmitAccumulatedText(currentProcessedLlmResponse)) {
                  LlmResponse aggregatedTextResponse =
                      thinkingResponseFromText(accumulatedThoughtText.toString());
                  responsesToEmit.add(aggregatedTextResponse);
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
                  if (accumulatedText.length() > 0 && lastRawResponseHolder[0] != null) {
                    GenerateContentResponse finalRawResp = lastRawResponseHolder[0];
                    boolean isStop =
                        finalRawResp
                            .candidates()
                            .flatMap(candidates -> candidates.stream().findFirst())
                            .flatMap(Candidate::finishReason)
                            .map(
                                finishReason -> finishReason.knownEnum() == FinishReason.Known.STOP)
                            .orElse(false);

                    if (isStop) {
                      LlmResponse finalAggregatedTextResponse =
                          responseFromText(accumulatedText.toString());
                      return Flowable.just(finalAggregatedTextResponse);
                    }
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
    logger.debug("Establishing Gemini connection.");
    LiveConnectConfig liveConnectConfig = llmRequest.liveConnectConfig();
    String effectiveModelName = llmRequest.model().orElse(model());

    logger.debug("Connecting to model {}", effectiveModelName);
    logger.trace("Connection Config: {}", liveConnectConfig);

    return new GeminiLlmConnection(apiClient, effectiveModelName, liveConnectConfig);
  }
}
