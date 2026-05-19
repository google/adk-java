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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
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
    llmRequest =
        GeminiUtil.prepareGenenerateContentRequest(
            llmRequest, !apiClient.vertexAI(), /* stripThoughts= */ false);
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
                      Flowable.fromFuture(streamFuture).flatMapIterable(iterable -> iterable)))
          .filter(Gemini::shouldEmit);
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
    return Flowable.defer(() -> new StreamingResponseAggregator().process(rawResponses));
  }

  /**
   * Returns true if {@code response} should be emitted downstream by the streaming pipeline.
   *
   * <p>Drops chunks that carry neither semantic content (i.e. they are an empty-text-only response
   * per {@link #isEmptyTextOnlyResponse}) nor any useful metadata (per {@link #hasUsefulMetadata}).
   *
   * <p>Package-private for testing.
   */
  static boolean shouldEmit(LlmResponse response) {
    return !isEmptyTextOnlyResponse(response) || hasUsefulMetadata(response);
  }

  /**
   * Returns true if {@code response} carries any non-content metadata that should be propagated
   * downstream (e.g. {@code usageMetadata}, {@code finishReason}, transcriptions, grounding or
   * error info). Inspects only top-level {@link LlmResponse} fields; the response's content/parts
   * are intentionally not considered here.
   */
  private static boolean hasUsefulMetadata(LlmResponse response) {
    return response.usageMetadata().isPresent()
        || response.finishReason().isPresent()
        || response.errorCode().isPresent()
        || response.groundingMetadata().isPresent()
        || response.inputTranscription().isPresent()
        || response.outputTranscription().isPresent();
  }

  /**
   * Returns true if {@code response} consists of exactly one {@link Part} whose only meaningful
   * payload is an empty text string (i.e. {@code parts:[{text:""}]}). Such a chunk can be safely
   * dropped from the streaming aggregator because it carries no semantic content for the agent
   * pipeline. A part is considered to carry semantic content if any of its non-text payloads
   * ({@code functionCall}, {@code functionResponse}, {@code inlineData}, {@code executableCode},
   * {@code codeExecutionResult}, {@code fileData}, {@code thoughtSignature}, {@code videoMetadata},
   * {@code toolCall}, {@code toolResponse}) is present.
   */
  private static boolean isEmptyTextOnlyResponse(LlmResponse response) {
    return response
        .content()
        .flatMap(Content::parts)
        .map(
            parts -> {
              if (parts.size() != 1) {
                return false;
              }
              Part part = parts.get(0);
              return part.text().map(String::isEmpty).orElse(false)
                  && part.functionCall().isEmpty()
                  && part.functionResponse().isEmpty()
                  && part.inlineData().isEmpty()
                  && part.executableCode().isEmpty()
                  && part.codeExecutionResult().isEmpty()
                  && part.fileData().isEmpty()
                  && part.thoughtSignature().isEmpty()
                  && part.videoMetadata().isEmpty()
                  && part.toolCall().isEmpty()
                  && part.toolResponse().isEmpty();
            })
        .orElse(false);
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

  private static final class StreamingResponseAggregator {
    private final List<Part> accumulatedSequence = new ArrayList<>();
    private final StringBuilder currentTextBuffer = new StringBuilder();
    private boolean currentTextIsThought = false;
    private byte[] currentThoughtSignature = null;
    private GenerateContentResponse lastRawResponse = null;

    /**
     * Processes a stream of raw responses, emitting partial and aggregated {@link LlmResponse}s.
     */
    private Flowable<LlmResponse> process(Flowable<GenerateContentResponse> rawResponses) {
      return rawResponses
          .concatMap(this::processRawResponse)
          .concatWith(Flowable.defer(this::processFinalResponse));
    }

    /**
     * Processes a single raw streaming chunk, accumulating parts and emitting intermediate
     * responses.
     */
    private Flowable<LlmResponse> processRawResponse(GenerateContentResponse rawResponse) {
      lastRawResponse = rawResponse;
      logger.trace("Raw streaming response: {}", rawResponse);

      LlmResponse currentProcessedLlmResponse = LlmResponse.create(rawResponse);
      List<Part> parts =
          currentProcessedLlmResponse.content().flatMap(Content::parts).orElse(ImmutableList.of());

      if (accumulateParts(parts)) {
        return Flowable.just(currentProcessedLlmResponse.toBuilder().partial(true).build());
      }

      // If the chunk has no text or function calls (e.g. metadata-only or empty), we suppress it
      // during streaming so it doesn't emit an empty partial response.
      // Exception: If this is a standalone empty chunk in an otherwise completely empty stream
      // (and not a STOP chunk), we emit it directly as a non-partial empty response.
      if (!isStop(currentProcessedLlmResponse)
          && accumulatedSequence.isEmpty()
          && currentTextBuffer.isEmpty()) {
        return Flowable.just(currentProcessedLlmResponse.toBuilder().partial(null).build());
      }

      return Flowable.empty();
    }

    /**
     * Accumulates text and function calls from incoming parts.
     *
     * @return true if any text or function call was present, false otherwise.
     */
    private boolean accumulateParts(List<Part> parts) {
      boolean hasTextOrFc = false;
      for (Part part : parts) {
        part.thoughtSignature().ifPresent(sig -> currentThoughtSignature = sig);
        String text = part.text().orElse("");
        if (!text.isEmpty()) {
          hasTextOrFc = true;
          boolean isThought = part.thought().orElse(false);
          // Immediately flush the active text buffer to preserve the exact interleaved blocks of
          // text/thoughts.
          if (!currentTextBuffer.isEmpty() && isThought != currentTextIsThought) {
            flushTextBufferToSequence();
          }
          if (currentTextBuffer.isEmpty()) {
            currentTextIsThought = isThought;
          }
          currentTextBuffer.append(text);
        }
        if (part.functionCall().isPresent()) {
          hasTextOrFc = true;
          flushTextBufferToSequence();
          accumulatedSequence.add(part);
        }
      }
      return hasTextOrFc;
    }

    /** Flushes any accumulated text or thought content in the buffer as a new {@link Part}. */
    private void flushTextBufferToSequence() {
      if (!currentTextBuffer.isEmpty()) {
        Part.Builder partBuilder =
            Part.builder().text(currentTextBuffer.toString()).thought(currentTextIsThought);
        if (currentThoughtSignature != null) {
          partBuilder.thoughtSignature(currentThoughtSignature);
          currentThoughtSignature = null;
        }
        accumulatedSequence.add(partBuilder.build());
        currentTextBuffer.setLength(0);
        currentTextIsThought = false;
      }
    }

    /**
     * Emits a single final aggregated, non-partial response carrying the complete chronological
     * sequence of accumulated parts (thoughts, text, and function calls) when the stream completes
     * with a STOP reason.
     */
    private Flowable<LlmResponse> processFinalResponse() {
      if (lastRawResponse == null) {
        return Flowable.empty();
      }
      LlmResponse currentResponse = LlmResponse.create(lastRawResponse);
      if (!isStop(currentResponse)) {
        return Flowable.empty();
      }

      flushTextBufferToSequence();

      if (accumulatedSequence.isEmpty()) {
        return Flowable.just(currentResponse.toBuilder().partial(null).build());
      }

      LlmResponse.Builder finalResponseBuilder = currentResponse.toBuilder().partial(null);

      // If the final STOP chunk carries a thoughtSignature (e.g. from a preceding function call or
      // thought), attach it to the last accumulated part in the sequence.
      GeminiUtil.getPart0FromLlmResponse(currentResponse)
          .flatMap(Part::thoughtSignature)
          .ifPresent(
              signature -> {
                int targetIndex = accumulatedSequence.size() - 1;
                Part targetPart = accumulatedSequence.get(targetIndex);
                accumulatedSequence.set(
                    targetIndex, targetPart.toBuilder().thoughtSignature(signature).build());
              });

      return Flowable.just(
          finalResponseBuilder
              .content(Content.builder().role("model").parts(accumulatedSequence).build())
              .build());
    }

    /** Checks whether the response finish reason indicates the stream has finished with STOP. */
    private static boolean isStop(LlmResponse response) {
      return response
          .finishReason()
          .map(reason -> reason.knownEnum() == FinishReason.Known.STOP)
          .orElse(false);
    }
  }
}
