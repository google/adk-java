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

import com.google.adk.internal.http.HttpClientFactory;
import com.google.adk.utils.ModelNameUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.ResponseStream;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponsePromptFeedback;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
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

  /**
   * Gemini models that failed with tools at least once on Vertex AI when a request also carried a
   * response schema. Each id also matches its pinned versions, such as {@code
   * gemini-3.8-flash-001}.
   */
  private static final ImmutableSet<String>
      MODELS_REQUIRING_SCHEMA_SET_MODEL_RESPONSE_TOOL_FALLBACK =
          ImmutableSet.of(
              "gemini-3-flash-preview",
              "gemini-3.1-flash-lite",
              "gemini-3.1-pro-preview",
              "gemini-3.5-flash",
              "gemini-3.5-flash-lite",
              "gemini-3.6-flash",
              "gemini-3.7-flash",
              "gemini-3.8-flash",
              "gemini-4-hydrogen");

  private static final Pattern PINNED_VERSION_SUFFIX = Pattern.compile("-\\d+$");

  private static OkHttpClient prepareHttpClient(@Nullable ExecutorService executorService) {
    OkHttpClient client =
        executorService == null
            ? HttpClientFactory.getOrCreateSharedHttpClient("GeminiApiClient")
            : HttpClientFactory.createHttpClient(executorService);
    return client
        .newBuilder()
        .connectTimeout(Duration.ZERO)
        .readTimeout(Duration.ZERO)
        .writeTimeout(Duration.ZERO)
        .build();
  }

  // Every client ADK builds starts here, so each sends ADK's tracking tag.
  private static Client.Builder adkClientBuilder(@Nullable ExecutorService executorService) {
    return Client.builder()
        .httpOptions(HttpOptions.builder().headers(TrackingHeaders.GENAI_SDK_HEADERS).build())
        .clientOptions(
            ClientOptions.builder().customHttpClient(prepareHttpClient(executorService)).build());
  }

  private static Client buildApiKeyClient(
      String apiKey, @Nullable ExecutorService executorService) {
    return adkClientBuilder(executorService).apiKey(apiKey).build();
  }

  private static Client buildVertexClient(
      VertexCredentials vertexCredentials, @Nullable ExecutorService executorService) {
    Client.Builder apiClientBuilder = adkClientBuilder(executorService);
    vertexCredentials.project().ifPresent(apiClientBuilder::project);
    vertexCredentials.location().ifPresent(apiClientBuilder::location);
    vertexCredentials.credentials().ifPresent(apiClientBuilder::credentials);
    return apiClientBuilder.build();
  }

  private static Client buildDefaultClient(@Nullable ExecutorService executorService) {
    return adkClientBuilder(executorService).build();
  }

  private final Client apiClient;
  // True when apiClient already sends ADK's tracking tag.
  private final boolean clientSendsTrackingHeaders;

  /**
   * Constructs a new Gemini instance. Each generate request sent through {@code apiClient} carries
   * ADK's tracking tag, which the genai SDK appends to the client's tracking headers. If the
   * client's own headers already carry an ADK tag, requests send two because the SDK does not
   * expose a client's headers.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiClient The genai {@link com.google.genai.Client} instance for making API calls.
   */
  public Gemini(String modelName, Client apiClient) {
    this(modelName, apiClient, /* clientSendsTrackingHeaders= */ false);
  }

  Gemini(String modelName, Client apiClient, boolean clientSendsTrackingHeaders) {
    super(modelName);
    this.apiClient = Objects.requireNonNull(apiClient, "apiClient cannot be null");
    this.clientSendsTrackingHeaders = clientSendsTrackingHeaders;
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param apiKey The Google Gemini API key.
   */
  public Gemini(String modelName, String apiKey) {
    this(
        modelName,
        buildApiKeyClient(Objects.requireNonNull(apiKey, "apiKey cannot be null"), null),
        /* clientSendsTrackingHeaders= */ true);
  }

  /**
   * Constructs a new Gemini instance with a Google Gemini API key.
   *
   * @param modelName The name of the Gemini model to use (e.g., "gemini-2.0-flash").
   * @param vertexCredentials The Vertex AI credentials to access the Gemini model.
   */
  public Gemini(String modelName, VertexCredentials vertexCredentials) {
    this(
        modelName,
        buildVertexClient(
            Objects.requireNonNull(vertexCredentials, "vertexCredentials cannot be null"), null),
        /* clientSendsTrackingHeaders= */ true);
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
    private ExecutorService httpExecutorService;

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
     * Sets the executor for the shared HTTP client's dispatcher. Pass {@link
     * HttpClientFactory#daemonExecutor} so a standalone or CLI JVM can exit once work is done, or a
     * container-managed executor in a managed environment. Applies only when the client is built
     * from an API key, Vertex credentials, or the default; it is ignored when an explicit {@link
     * #apiClient} is supplied.
     *
     * @param httpExecutorService The executor for HTTP dispatcher threads.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder httpExecutorService(ExecutorService httpExecutorService) {
      this.httpExecutorService = httpExecutorService;
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
      }
      Client client;
      if (apiKey != null) {
        client = buildApiKeyClient(apiKey, httpExecutorService);
      } else if (vertexCredentials != null) {
        client = buildVertexClient(vertexCredentials, httpExecutorService);
      } else {
        client = buildDefaultClient(httpExecutorService);
      }
      return new Gemini(modelName, client, /* clientSendsTrackingHeaders= */ true);
    }
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    LlmRequest preparedRequest =
        GeminiUtil.prepareGenenerateContentRequest(
            llmRequest, !apiClient.vertexAI(), /* stripThoughts= */ false);
    String effectiveModelName = preparedRequest.model().orElse(model());
    if (preparedRequest.cacheConfig().isEmpty()) {
      return sendRequest(effectiveModelName, preparedRequest, stream, /* cacheMetadata= */ null);
    }
    return new GeminiContextCacheManager(apiClient, effectiveModelName)
        .handleContextCaching(preparedRequest)
        .flatMapPublisher(
            cached -> sendRequest(effectiveModelName, cached.request(), stream, cached.metadata()));
  }

  /** Sends the request, and puts {@code cacheMetadata}, if any, on the final response. */
  private Flowable<LlmResponse> sendRequest(
      String effectiveModelName,
      LlmRequest llmRequest,
      boolean stream,
      @Nullable CacheMetadata cacheMetadata) {
    GenerateContentConfig config = llmRequest.config().orElse(null);

    // Model input must not be logged, so only the request's shape is.
    logger.trace(
        "Request to {}: {} contents, config present: {}, {} function tools",
        effectiveModelName,
        llmRequest.contents().size(),
        config != null,
        llmRequest.tools().size());

    if (!clientSendsTrackingHeaders) {
      config = withTrackingHeaders(config);
    }
    if (stream) {
      logger.debug("Sending streaming generateContent request to model {}", effectiveModelName);
      CompletableFuture<ResponseStream<GenerateContentResponse>> streamFuture =
          apiClient.async.models.generateContentStream(
              effectiveModelName, llmRequest.contents(), config);

      return aggregateStream(readStream(streamFuture), cacheMetadata);
    } else {
      logger.debug("Sending generateContent request to model {}", effectiveModelName);
      return Flowable.fromFuture(
              apiClient
                  .async
                  .models
                  .generateContent(effectiveModelName, llmRequest.contents(), config)
                  .thenApplyAsync(LlmResponse::create))
          .map(response -> withCacheMetadata(response, cacheMetadata));
    }
  }

  /**
   * Returns {@code config} with ADK's tracking tag merged into its request headers, which the genai
   * SDK appends to the client's own.
   */
  private static GenerateContentConfig withTrackingHeaders(@Nullable GenerateContentConfig config) {
    GenerateContentConfig base = config != null ? config : GenerateContentConfig.builder().build();
    HttpOptions httpOptions = base.httpOptions().orElseGet(() -> HttpOptions.builder().build());
    return base.toBuilder()
        .httpOptions(
            httpOptions.toBuilder()
                .headers(
                    TrackingHeaders.mergeIntoGenAiSdkHeaders(
                        httpOptions.headers().orElse(ImmutableMap.of())))
                .build())
        .build();
  }

  /**
   * Reads the stream and closes it on {@link Schedulers#io()} unless it was read to the end, so a
   * cancel or a read error doesn't leave the HTTP response open. The close waits for the stream to
   * open and can block behind a {@code readLine()} in progress. An iterator call that returns after
   * the cancel closes the stream again, covering a continuation request opened during that call.
   */
  @VisibleForTesting
  static <T, S extends Iterable<T> & AutoCloseable> Flowable<T> readStream(
      CompletableFuture<S> streamFuture) {
    return Flowable.defer(
        () -> {
          AtomicBoolean cancelled = new AtomicBoolean();
          AtomicBoolean completed = new AtomicBoolean();
          return Flowable.using(
              () -> streamFuture,
              future ->
                  Flowable.fromFuture(future)
                      .flatMapIterable(stream -> closingAfterCancel(stream, cancelled))
                      .doOnComplete(() -> completed.set(true)),
              future -> {
                if (!completed.get()) {
                  cancelled.set(true);
                  var unused = future.thenAccept(Gemini::closeAsync);
                }
              },
              /* eager= */ false);
        });
  }

  private static <T, S extends Iterable<T> & AutoCloseable> Iterable<T> closingAfterCancel(
      S stream, AtomicBoolean cancelled) {
    Iterator<T> delegate = stream.iterator();
    return () ->
        new Iterator<T>() {
          @Override
          public boolean hasNext() {
            try {
              return delegate.hasNext();
            } finally {
              closeIfCancelled();
            }
          }

          @Override
          public T next() {
            try {
              return delegate.next();
            } finally {
              closeIfCancelled();
            }
          }

          private void closeIfCancelled() {
            if (cancelled.get()) {
              closeAsync(stream);
            }
          }
        };
  }

  private static void closeAsync(AutoCloseable stream) {
    Schedulers.io().scheduleDirect(() -> closeQuietly(stream));
  }

  private static void closeQuietly(AutoCloseable stream) {
    try {
      stream.close();
    } catch (Exception e) {
      logger.debug("Failed to close the response stream", e);
    }
  }

  private static LlmResponse withCacheMetadata(
      LlmResponse response, @Nullable CacheMetadata cacheMetadata) {
    return cacheMetadata == null
        ? response
        : response.toBuilder().cacheMetadata(cacheMetadata).build();
  }

  @VisibleForTesting
  static Flowable<LlmResponse> processRawResponses(Flowable<GenerateContentResponse> rawResponses) {
    return aggregateStream(rawResponses, /* cacheMetadata= */ null);
  }

  /**
   * Turns a stream of raw responses into partial and aggregated {@link LlmResponse}s, and puts
   * {@code cacheMetadata}, if any, on the aggregated response.
   */
  private static Flowable<LlmResponse> aggregateStream(
      Flowable<GenerateContentResponse> rawResponses, @Nullable CacheMetadata cacheMetadata) {
    return Flowable.defer(() -> new StreamState().process(rawResponses, cacheMetadata));
  }

  /**
   * Reports {@link LlmCapabilities#outputSchemaAndTools()} as false on the Gemini API, as ADK
   * Python does. On Vertex AI, it is false for {@code gemini-2.*} and for the Gemini models that
   * failed with tools in JSON mode, such as {@code gemini-3.8-flash}, and true for any other model.
   */
  @Override
  public LlmCapabilities capabilities() {
    return LlmCapabilities.builder()
        .outputSchemaAndTools(apiClient.vertexAI() && takesOutputSchemaWithToolsOnVertexAi(model()))
        .build();
  }

  private static boolean takesOutputSchemaWithToolsOnVertexAi(@Nullable String modelName) {
    String name = Strings.nullToEmpty(modelName);
    String modelId = name.substring(name.lastIndexOf('/') + 1);
    return !ModelNameUtils.isGemini2Model(modelId)
        && !MODELS_REQUIRING_SCHEMA_SET_MODEL_RESPONSE_TOOL_FALLBACK.contains(
            PINNED_VERSION_SUFFIX.matcher(modelId).replaceFirst(""));
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
    logger.trace(
        "Connection config: {} function tools, system instruction present: {}",
        llmRequest.tools().size(),
        liveConnectConfig.systemInstruction().isPresent());

    return new GeminiLlmConnection(connectLiveTransport(effectiveModelName, liveConnectConfig));
  }

  /**
   * Opens the live transport the connection drives. Overridable so a test can supply an in-process
   * {@link GeminiLiveTransport} double and exercise the real {@link GeminiLlmConnection} without a
   * network.
   */
  protected CompletableFuture<GeminiLiveTransport> connectLiveTransport(
      String modelName, LiveConnectConfig config) {
    return apiClient.async.live.connect(modelName, config).thenApply(GenAiLiveTransport::new);
  }

  /** Feeds one stream's raw responses to a {@link StreamingResponseAggregator}. */
  private static final class StreamState {
    private final StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    // Kept from the error chunk, so a later STOP or chunk without candidates cannot drop it.
    private @Nullable String finishMessage = null;
    // The prompt was blocked: kept so a later chunk without candidates cannot replace the reason.
    private @Nullable LlmResponse promptBlock = null;

    private Flowable<LlmResponse> process(
        Flowable<GenerateContentResponse> rawResponses, @Nullable CacheMetadata cacheMetadata) {
      return rawResponses
          .concatMap(this::processRawResponse)
          .concatWith(
              Flowable.defer(() ->
                  Flowable.fromOptional(aggregator.aggregate())
                      .map(this::withFinishMessage)
                      .map(this::withPromptBlock)
                      .map(response -> withCacheMetadata(response, cacheMetadata))));
    }

    private Flowable<LlmResponse> processRawResponse(GenerateContentResponse rawResponse) {
      rawResponse
          .candidates()
          .flatMap(candidates -> candidates.stream().findFirst())
          .filter(candidate ->
              candidate
                  .finishReason()
                  .filter(reason -> !StreamingResponseAggregator.isStop(reason))
                  .isPresent())
          .ifPresent(candidate -> finishMessage = candidate.finishMessage().orElse(null));

      LlmResponse response = LlmResponse.create(rawResponse);
      if (rawResponse
              .promptFeedback()
              .flatMap(GenerateContentResponsePromptFeedback::blockReason)
              .isPresent()
          && rawResponse.candidates().map(List::isEmpty).orElse(true)) {
        promptBlock = response;
      }
      // Model output and tool arguments must not be logged, so only the chunk's shape is.
      logger.trace(
          "Streaming chunk: {} parts, finish reason {}",
          response.content().flatMap(Content::parts).map(List::size).orElse(0),
          response.finishReason().map(Object::toString).orElse("none"));
      // A chunk without content emits nothing; aggregate() keeps its usage and finish reason.
      return Flowable.fromOptional(aggregator.processResponse(response));
    }

    /** Puts the finish message on an aggregated response whose finish reason is an error. */
    private LlmResponse withFinishMessage(LlmResponse response) {
      if (finishMessage == null || response.errorCode().isEmpty()) {
        return response;
      }
      return response.toBuilder().errorMessage(finishMessage).build();
    }

    /**
     * Puts the prompt's block reason on an aggregated response with no finish reason, or with a
     * STOP that brought no answer, which would otherwise end the turn without an event.
     */
    private LlmResponse withPromptBlock(LlmResponse response) {
      boolean answered =
          response
              .finishReason()
              .map(reason -> !StreamingResponseAggregator.isStop(reason) || hasAnswer(response))
              .orElse(false);
      if (promptBlock == null || answered) {
        return response;
      }
      // A blocked prompt has no finish reason, as LlmResponse.create reports it outside a stream.
      return response.toBuilder()
          .finishReason(null)
          .errorCode(promptBlock.errorCode().orElse(null))
          .errorMessage(promptBlock.errorMessage().orElse(null))
          .build();
    }

    /** Returns whether the response has a part other than the empty text that ends a stream. */
    private static boolean hasAnswer(LlmResponse response) {
      return response.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
          .anyMatch(part -> !StreamingResponseAggregator.isStreamTerminator(part));
    }
  }
}
