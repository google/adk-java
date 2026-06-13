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
import com.google.adk.internal.http.HttpClientFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.Candidate;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
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
  private static final OkHttpClient SHARED_HTTP_CLIENT =
      HttpClientFactory.createSharedHttpClient("GeminiApiClient");

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
            .clientOptions(ClientOptions.builder().customHttpClient(SHARED_HTTP_CLIENT).build())
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
        Client.builder()
            .httpOptions(HttpOptions.builder().headers(TRACKING_HEADERS).build())
            .clientOptions(ClientOptions.builder().customHttpClient(SHARED_HTTP_CLIENT).build());
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
                .clientOptions(ClientOptions.builder().customHttpClient(SHARED_HTTP_CLIENT).build())
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
    return Flowable.defer(() -> new StreamingResponseAggregator().process(rawResponses));
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
    // Always reassigned in accumulateParts() before it is read; the initializer is never observed.
    private boolean currentTextIsThought = false;
    private byte[] currentThoughtSignature = null;
    private GenerateContentResponse lastRawResponse = null;

    // Streaming function-call accumulation state. When the model streams a function call across
    // multiple chunks (via partialArgs/willContinue), its arguments are accumulated here and a
    // single complete function-call part is flushed to accumulatedSequence once it completes.
    private String currentFcName = null;
    private Map<String, Object> currentFcArgs = new LinkedHashMap<>();
    private String currentFcId = null;

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

      // Assign an ID to every function-call part up front, mirroring ADK Python's
      // StreamingResponseAggregator: the same ID is reused in the partial and final responses so
      // consumers can correlate them.
      List<Part> partsWithIds = ensureFunctionCallIds(parts);

      if (accumulateParts(partsWithIds)) {
        // partsWithIds is non-empty here, so the chunk's content (and its role) is present. Rebuild
        // the partial content from the parts-with-IDs so its FC ID matches the final event.
        Content.Builder rebuilt = Content.builder().parts(partsWithIds);
        currentProcessedLlmResponse.content().flatMap(Content::role).ifPresent(rebuilt::role);
        return Flowable.just(
            currentProcessedLlmResponse.toBuilder().content(rebuilt.build()).partial(true).build());
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
     * Returns a list of parts where every function-call part has a non-empty ID. If a part's
     * function call already has an ID, the original part is preserved; otherwise a new part with a
     * client-generated ID is substituted. Non-FC parts are passed through unchanged.
     */
    private static List<Part> ensureFunctionCallIds(List<Part> parts) {
      List<Part> result = new ArrayList<>(parts.size());
      for (Part part : parts) {
        if (part.functionCall().isPresent()) {
          FunctionCall fc = part.functionCall().get();
          if (fc.id().map(String::isEmpty).orElse(true)) {
            FunctionCall withId = fc.toBuilder().id(generateClientFunctionCallId()).build();
            result.add(part.toBuilder().functionCall(withId).build());
            continue;
          }
        }
        result.add(part);
      }
      return result;
    }

    /**
     * Generates a unique client-side function-call ID. Format matches {@code
     * com.google.adk.flows.llmflows.Functions#generateClientFunctionCallId()} so downstream code
     * that already sees IDs with the {@code "adk-"} prefix continues to work.
     */
    private static String generateClientFunctionCallId() {
      return "adk-" + UUID.randomUUID();
    }

    /**
     * Accumulates text and function calls from incoming parts. Function-call parts passed to this
     * method are expected to already have IDs (see {@link #ensureFunctionCallIds}).
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
          processFunctionCallPart(part);
        }
      }
      return hasTextOrFc;
    }

    /**
     * Processes a function-call part, mirroring ADK Python's {@code _process_function_call_part}. A
     * function call whose arguments are streamed across chunks (it carries {@code partialArgs} or
     * {@code willContinue=true}) is accumulated and flushed as a single complete part once it
     * finishes; a complete (non-streaming) function call is appended directly.
     */
    private void processFunctionCallPart(Part part) {
      FunctionCall fc = part.functionCall().get();
      boolean streaming =
          fc.partialArgs().map(args -> !args.isEmpty()).orElse(false)
              || fc.willContinue().orElse(false);
      if (streaming) {
        // Capture the thought signature from the first chunk that carries one.
        if (part.thoughtSignature().isPresent() && currentThoughtSignature == null) {
          currentThoughtSignature = part.thoughtSignature().get();
        }
        processStreamingFunctionCall(fc);
      } else if (fc.name().filter(name -> !name.isEmpty()).isPresent()) {
        // Complete function call. Skip empty calls, which are only streaming end markers. The part
        // already has an ID assigned by ensureFunctionCallIds.
        flushTextBufferToSequence();
        accumulatedSequence.add(part);
      }
    }

    /**
     * Accumulates one chunk of a streamed function call, mirroring ADK Python's {@code
     * _process_streaming_function_call}: merges the function name/ID and each {@code partialArg}
     * (by JSONPath) into {@link #currentFcArgs}, then flushes the completed call once {@code
     * willContinue} is no longer set.
     */
    private void processStreamingFunctionCall(FunctionCall fc) {
      fc.name().filter(name -> !name.isEmpty()).ifPresent(name -> currentFcName = name);
      // Use the first ID seen (the model's, if provided, otherwise a generated one) for the whole
      // call so the partial and final events correlate.
      if (currentFcId == null) {
        currentFcId =
            fc.id().filter(id -> !id.isEmpty()).orElseGet(() -> generateClientFunctionCallId());
      }
      for (PartialArg partialArg : fc.partialArgs().orElse(ImmutableList.of())) {
        String jsonPath = partialArg.jsonPath().orElse("");
        if (jsonPath.isEmpty()) {
          continue;
        }
        applyPartialArg(partialArg, jsonPath);
      }
      if (!fc.willContinue().orElse(false)) {
        flushTextBufferToSequence();
        flushFunctionCallToSequence();
      }
    }

    /**
     * Applies a single {@link PartialArg} to {@link #currentFcArgs} at {@code jsonPath}, mirroring
     * ADK Python's {@code _get_value_from_partial_arg}: string chunks are appended to any existing
     * string at the path, while number/bool/null values overwrite.
     */
    private void applyPartialArg(PartialArg partialArg, String jsonPath) {
      if (partialArg.stringValue().isPresent()) {
        Object existing = getValueByJsonPath(jsonPath);
        String chunk = partialArg.stringValue().get();
        setValueByJsonPath(jsonPath, existing instanceof String s ? s + chunk : chunk);
      } else if (partialArg.numberValue().isPresent()) {
        setValueByJsonPath(jsonPath, partialArg.numberValue().get());
      } else if (partialArg.boolValue().isPresent()) {
        setValueByJsonPath(jsonPath, partialArg.boolValue().get());
      } else if (partialArg.nullValue().isPresent()) {
        setValueByJsonPath(jsonPath, null);
      }
    }

    /**
     * Returns the value currently stored at {@code jsonPath} in {@link #currentFcArgs}, or null.
     */
    private @Nullable Object getValueByJsonPath(String jsonPath) {
      Object current = currentFcArgs;
      for (String key : splitJsonPath(jsonPath)) {
        if (current instanceof Map<?, ?> map && map.containsKey(key)) {
          current = map.get(key);
        } else {
          return null;
        }
      }
      return current;
    }

    /**
     * Sets {@code value} at {@code jsonPath} in {@link #currentFcArgs}, creating maps as needed.
     */
    @SuppressWarnings("unchecked")
    private void setValueByJsonPath(String jsonPath, Object value) {
      String[] keys = splitJsonPath(jsonPath);
      Map<String, Object> current = currentFcArgs;
      for (int i = 0; i < keys.length - 1; i++) {
        Object next = current.get(keys[i]);
        if (!(next instanceof Map)) {
          next = new LinkedHashMap<>();
          current.put(keys[i], next);
        }
        current = (Map<String, Object>) next;
      }
      current.put(keys[keys.length - 1], value);
    }

    /** Splits a JSONPath such as {@code "$.location.city"} into its component keys. */
    private static String[] splitJsonPath(String jsonPath) {
      String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
      return path.split("\\.");
    }

    /**
     * Flushes the accumulated streamed function call (if any) to {@link #accumulatedSequence} as a
     * single complete part, mirroring ADK Python's {@code _flush_function_call_to_sequence}.
     */
    private void flushFunctionCallToSequence() {
      if (currentFcName == null) {
        return;
      }
      FunctionCall.Builder fcBuilder =
          FunctionCall.builder().name(currentFcName).args(new LinkedHashMap<>(currentFcArgs));
      if (currentFcId != null) {
        fcBuilder.id(currentFcId);
      }
      Part.Builder partBuilder = Part.builder().functionCall(fcBuilder.build());
      if (currentThoughtSignature != null) {
        partBuilder.thoughtSignature(currentThoughtSignature);
      }
      accumulatedSequence.add(partBuilder.build());
      currentFcName = null;
      currentFcArgs = new LinkedHashMap<>();
      currentFcId = null;
      currentThoughtSignature = null;
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
     * Emits the final aggregated, non-partial response with all accumulated parts (thoughts, text,
     * function calls). Mirrors ADK Python's {@code StreamingResponseAggregator.close()}: emitted
     * even without a finish reason so accumulated content is never dropped; a non-STOP finish
     * reason is surfaced as an error.
     */
    private Flowable<LlmResponse> processFinalResponse() {
      if (lastRawResponse == null) {
        return Flowable.empty();
      }
      LlmResponse currentResponse = LlmResponse.create(lastRawResponse);

      flushTextBufferToSequence();
      // Flush any in-progress streamed function call whose stream ended before completing.
      flushFunctionCallToSequence();

      // Nothing accumulated and no finish reason: any empty/metadata chunk already streamed, skip.
      boolean hasFinishReason = currentResponse.finishReason().isPresent();
      if (accumulatedSequence.isEmpty() && !hasFinishReason) {
        return Flowable.empty();
      }

      LlmResponse.Builder finalResponseBuilder = currentResponse.toBuilder().partial(null);
      if (hasFinishReason && !isStop(currentResponse)) {
        finalResponseBuilder.errorCode(currentResponse.finishReason().get());
        lastRawResponse
            .candidates()
            .filter(candidates -> !candidates.isEmpty())
            .map(candidates -> candidates.get(0))
            .flatMap(Candidate::finishMessage)
            .ifPresent(finalResponseBuilder::errorMessage);
      }

      if (accumulatedSequence.isEmpty()) {
        return Flowable.just(finalResponseBuilder.build());
      }

      // If the final chunk carries a thoughtSignature (e.g. from a preceding function call or
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
