package com.google.adk.models.azure;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket-based connection to the Azure OpenAI Realtime API.
 *
 * <p>Implements the GA WebSocket protocol:
 *
 * <ol>
 *   <li>Open a WebSocket to {@code
 *       wss://<resource>.openai.azure.com/openai/v1/realtime?model=<deployment>}
 *   <li>Authenticate via {@code api-key} header
 *   <li>Send/receive JSON events for text, audio, and function calls
 * </ol>
 *
 * @author Alfred Jimmy
 * @see <a
 *     href="https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/realtime-audio-websockets">
 *     Azure OpenAI Realtime API via WebSockets</a>
 */
public final class AzureRealtimeLlmConnection implements BaseLlmConnection {

  private static final Logger logger = LoggerFactory.getLogger(AzureRealtimeLlmConnection.class);

  private static final int CONNECT_TIMEOUT_SECONDS = 30;

  /**
   * Close-mic / phone-held noise reduction (not {@code far_field}, which favors room/distant
   * pickup).
   */
  private static final String INPUT_AUDIO_NOISE_REDUCTION = "near_field";

  private static final String SEMANTIC_VAD_EAGERNESS = "high";

  private static final boolean CREATE_RESPONSE_AFTER_TURN = true;

  private static final boolean INTERRUPT_RESPONSE = true;

  private final AzureConfig config;
  private final LlmRequest llmRequest;
  private final FlowableProcessor<LlmResponse> responseProcessor =
      PublishProcessor.<LlmResponse>create().toSerialized();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean sessionConfigured = new AtomicBoolean(false);
  private final CountDownLatch connectedLatch = new CountDownLatch(1);
  private final Object wsLock = new Object();

  private volatile RealtimeWebSocketClient wsClient;

  /**
   * When true, we already forwarded assistant text via {@code response.*.delta} events for this
   * response; the matching {@code *.done} carries the full string again and must not be printed
   * twice.
   */
  private final AtomicBoolean assistantOutputTextHadDelta = new AtomicBoolean(false);

  private final AtomicBoolean assistantAudioTranscriptHadDelta = new AtomicBoolean(false);

  /** True while Azure is generating a response (between response.created and response.done). */
  private final AtomicBoolean activeResponse = new AtomicBoolean(false);

  /**
   * Tracks in-flight function calls by item_id so that {@code
   * response.function_call_arguments.done} (which may omit name/call_id on some API versions) can
   * be resolved. Populated from {@code response.output_item.added} events.
   */
  private final ConcurrentHashMap<String, FunctionCallInfo> pendingFunctionCalls =
      new ConcurrentHashMap<>();

  private static final Set<String> WHISPER_HALLUCINATIONS =
      Set.of(
          "thank you.",
          "thanks for watching.",
          "bye.",
          "you",
          "the end.",
          "thanks for watching!",
          "subscribe",
          "продолжение следует...",
          "thank you for watching.",
          ".");

  private record FunctionCallInfo(String name, String callId) {}

  AzureRealtimeLlmConnection(AzureConfig config, LlmRequest llmRequest) {
    this.config = Objects.requireNonNull(config, "config cannot be null");
    this.llmRequest = Objects.requireNonNull(llmRequest, "llmRequest cannot be null");

    try {
      initializeConnection();
    } catch (Exception e) {
      logger.error("Failed to initialize Azure Realtime WebSocket connection", e);
      responseProcessor.onError(e);
      throw new IllegalStateException(
          "Failed to initialize Azure Realtime WebSocket connection", e);
    }
  }

  // ==================== Connection Initialization ====================

  private void initializeConnection() throws Exception {
    logger.info(
        "Initializing Azure Realtime WebSocket connection for model: {}", config.modelName());

    String apiKey = config.apiKey();
    String wsUrl = config.realtimeWebSocketUrl();

    logger.info("Connecting to WebSocket: {}", AzureConfig.maskWebSocketUrl(wsUrl));

    URI uri = URI.create(wsUrl);
    RealtimeWebSocketClient client = new RealtimeWebSocketClient(uri, apiKey);
    synchronized (wsLock) {
      wsClient = client;
    }

    try {
      client.connectBlocking(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!client.isOpen()) {
        throw new IllegalStateException("WebSocket connection failed to open within timeout");
      }

      if (!connectedLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("WebSocket connected but session.created not received");
      }

      sendSessionUpdate();
      logger.info("Azure Realtime WebSocket connection established.");
    } catch (Exception e) {
      closeOpenWebSocket(client);
      synchronized (wsLock) {
        if (wsClient == client) {
          wsClient = null;
        }
      }
      throw e;
    }
  }

  private void closeOpenWebSocket(WebSocketClient client) {
    if (client == null) {
      return;
    }
    try {
      if (client.isOpen()) {
        client.closeBlocking();
      }
    } catch (Exception e) {
      logger.warn("Error closing WebSocket during init cleanup", e);
    }
  }

  private void sendSessionUpdate() {
    String voice = config.voice();
    String instructions = AzureRequestConverter.extractInstructions(llmRequest);

    JSONObject event = new JSONObject();
    event.put("type", "session.update");

    JSONObject session = new JSONObject();
    if (!instructions.isEmpty()) {
      session.put("instructions", instructions);
    }
    session.put("voice", voice);
    session.put("modalities", new JSONArray().put("text").put("audio"));

    session.put("input_audio_format", "pcm16");
    session.put("output_audio_format", "pcm16");

    JSONObject noiseReduction = new JSONObject();
    noiseReduction.put("type", INPUT_AUDIO_NOISE_REDUCTION);
    session.put("input_audio_noise_reduction", noiseReduction);

    JSONObject turnDetection = new JSONObject();
    turnDetection.put("type", "semantic_vad");
    turnDetection.put("eagerness", SEMANTIC_VAD_EAGERNESS);
    turnDetection.put("create_response", CREATE_RESPONSE_AFTER_TURN);
    turnDetection.put("interrupt_response", INTERRUPT_RESPONSE);
    session.put("turn_detection", turnDetection);

    JSONObject transcription = new JSONObject();
    transcription.put("model", "whisper-1");
    session.put("input_audio_transcription", transcription);

    JSONArray toolsArray = AzureRequestConverter.buildTools(llmRequest);
    if (toolsArray.length() > 0) {
      session.put("tools", toolsArray);
      session.put("tool_choice", "auto");
    }

    event.put("session", session);
    sendMessage(event.toString());
    logger.info(
        "Sent session.update with voice={}, turn_detection={}, noise_reduction={}, tools={}",
        voice,
        turnDetection,
        INPUT_AUDIO_NOISE_REDUCTION,
        toolsArray.length());
  }

  // ==================== WebSocket Event Handling ====================

  private void handleMessage(String json) {
    if (closed.get()) return;

    try {
      JSONObject event = new JSONObject(json);
      String eventType = event.optString("type", "");

      logger.debug("Realtime WS event: {}", eventType);

      switch (eventType) {
        case "session.created":
          logger.info(
              "Realtime session created: {}",
              event.optJSONObject("session") != null
                  ? event.optJSONObject("session").optString("id", "unknown")
                  : "unknown");
          sessionConfigured.set(true);
          connectedLatch.countDown();
          break;

        case "session.updated":
          JSONObject updatedSession = event.optJSONObject("session");
          JSONObject appliedTurnDetection =
              updatedSession != null ? updatedSession.optJSONObject("turn_detection") : null;
          logger.info(
              "Realtime session updated; turn_detection={}",
              appliedTurnDetection != null ? appliedTurnDetection.toString() : "none");
          break;

        case "response.created":
          pendingFunctionCalls.clear();
          assistantOutputTextHadDelta.set(false);
          assistantAudioTranscriptHadDelta.set(false);
          activeResponse.set(true);
          break;

        case "response.text.delta":
        case "response.output_text.delta":
          handleTextDelta(event);
          break;

        case "response.text.done":
        case "response.output_text.done":
          handleTextDone(event);
          break;

        case "response.audio_transcript.delta":
        case "response.output_audio_transcript.delta":
          handleTranscriptDelta(event);
          break;

        case "response.audio_transcript.done":
        case "response.output_audio_transcript.done":
          handleTranscriptDone(event);
          break;

        case "response.audio.delta":
        case "response.output_audio.delta":
          handleAudioDelta(event);
          break;

        case "response.output_item.added":
          handleOutputItemAdded(event);
          break;

        case "response.function_call_arguments.delta":
          break;

        case "response.function_call_arguments.done":
          handleFunctionCallDone(event);
          break;

        case "response.done":
          handleResponseDone(event);
          break;

        case "input_audio_buffer.speech_started":
          // WebSocket clients should stop playback on speech_started during an active response
          // (OpenAI Realtime guide). Gemini emits interrupted() immediately; Azure relies on
          // server VAD + interrupt_response, then response.done status=cancelled — but that
          // response.done can lag or be missed, so emit interrupted here as the primary signal.
          if (activeResponse.get()) {
            logger.info(
                "Realtime: speech_started during active response — emitting interrupted (barge-in).");
            responseProcessor.onNext(LlmResponse.builder().interrupted(true).build());
            clearInputAudioBufferIfOpen();
          } else {
            logger.debug("Realtime: speech_started (no active response).");
          }
          break;

        case "input_audio_buffer.speech_stopped":
          logger.debug("User speech stopped.");
          break;

        case "input_audio_buffer.committed":
        case "conversation.item.created":
        case "response.output_item.done":
        case "response.content_part.added":
        case "response.content_part.done":
          logger.debug("Lifecycle event: {}", eventType);
          break;

        case "conversation.item.input_audio_transcription.completed":
          handleInputTranscription(event);
          break;

        case "error":
          handleErrorEvent(event);
          break;

        default:
          logger.debug("Unhandled Realtime event type: {}", eventType);
          break;
      }
    } catch (JSONException e) {
      logger.warn("Failed to parse WebSocket message: {}", json, e);
    }
  }

  private void handleTextDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      assistantOutputTextHadDelta.set(true);
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleTextDone(JSONObject event) {
    String text = event.optString("text", "");
    if (assistantOutputTextHadDelta.compareAndSet(true, false)) {
      emitAssistantTurnTerminatorOnly();
      return;
    }
    if (!text.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(text)).build())
              .partial(false)
              .build());
    }
  }

  private void handleTranscriptDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      assistantAudioTranscriptHadDelta.set(true);
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleTranscriptDone(JSONObject event) {
    String transcript = event.optString("transcript", "");
    if (assistantAudioTranscriptHadDelta.compareAndSet(true, false)) {
      emitAssistantTurnTerminatorOnly();
      return;
    }
    if (!transcript.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(transcript)).build())
              .partial(false)
              .build());
    }
  }

  /** Ends the assistant line in the UI without repeating text already streamed via deltas. */
  private void emitAssistantTurnTerminatorOnly() {
    responseProcessor.onNext(
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(Part.fromText("")).build())
            .partial(false)
            .build());
  }

  private void handleAudioDelta(JSONObject event) {
    String base64Audio = event.optString("delta", "");
    if (!base64Audio.isEmpty()) {
      try {
        byte[] audioBytes = Base64.getDecoder().decode(base64Audio);
        logger.debug("Received {} bytes of audio from model", audioBytes.length);
        Blob audioBlob = Blob.builder().mimeType("audio/pcm").data(audioBytes).build();

        responseProcessor.onNext(
            LlmResponse.builder()
                .content(
                    Content.builder()
                        .role("model")
                        .parts(ImmutableList.of(Part.builder().inlineData(audioBlob).build()))
                        .build())
                .partial(true)
                .build());
      } catch (IllegalArgumentException e) {
        logger.warn("Failed to decode audio delta", e);
      }
    }
  }

  /**
   * Captures function_call items from {@code response.output_item.added} so that name and call_id
   * are available when {@code response.function_call_arguments.done} arrives (some API versions
   * omit them from the latter event).
   */
  private void handleOutputItemAdded(JSONObject event) {
    JSONObject item = event.optJSONObject("item");
    if (item == null) return;
    String type = item.optString("type", "");
    if (!"function_call".equals(type)) return;

    String itemId = item.optString("id", "");
    String name = item.optString("name", "");
    String callId = item.optString("call_id", "");
    if (!itemId.isEmpty() && !name.isEmpty()) {
      pendingFunctionCalls.put(itemId, new FunctionCallInfo(name, callId));
      logger.info(
          "Tracked pending function_call: item_id={}, name={}, call_id={}", itemId, name, callId);
    }
  }

  private void handleFunctionCallDone(JSONObject event) {
    String name = event.optString("name", "");
    String callId = event.optString("call_id", "");
    String itemId = event.optString("item_id", "");
    String argsStr = event.optString("arguments", "{}");

    if (name.isEmpty() && !itemId.isEmpty()) {
      FunctionCallInfo tracked = pendingFunctionCalls.remove(itemId);
      if (tracked != null) {
        name = tracked.name();
        if (callId.isEmpty()) callId = tracked.callId();
      }
    } else if (!itemId.isEmpty()) {
      pendingFunctionCalls.remove(itemId);
    }

    if (name.isEmpty()) {
      logger.warn(
          "Dropping function_call_arguments.done with no resolvable name (item_id={})", itemId);
      return;
    }

    Map<String, Object> args;
    try {
      args = new JSONObject(argsStr).toMap();
    } catch (JSONException e) {
      logger.warn("Failed to parse function call arguments: {}", argsStr);
      args = Map.of();
    }

    FunctionCall.Builder fcBuilder = FunctionCall.builder().name(name).args(args);
    if (!callId.isEmpty()) {
      fcBuilder.id(callId);
    }
    FunctionCall fc = fcBuilder.build();
    logger.info(
        "Emitting FunctionCall: name={}, call_id={}, args_keys={}", name, callId, args.keySet());
    responseProcessor.onNext(
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().functionCall(fc).build()))
                    .build())
            .partial(false)
            .turnComplete(true)
            .build());
  }

  private void handleResponseDone(JSONObject event) {
    activeResponse.set(false);
    pendingFunctionCalls.clear();
    JSONObject resp = event.optJSONObject("response");
    String status =
        resp != null ? resp.optString("status", "").trim().toLowerCase(java.util.Locale.ROOT) : "";
    JSONObject statusDetails = resp != null ? resp.optJSONObject("status_details") : null;
    String statusReason =
        statusDetails != null
            ? statusDetails.optString("reason", "").trim().toLowerCase(java.util.Locale.ROOT)
            : "";
    boolean interrupted =
        "cancelled".equals(status)
            || "canceled".equals(status)
            || "interrupted".equals(status)
            || ("incomplete".equals(status) && "turn_detected".equals(statusReason));
    if (interrupted) {
      logger.info(
          "Realtime response ended with status={} reason={} — emitting interrupted playback signal.",
          status,
          statusReason.isEmpty() ? "n/a" : statusReason);
      responseProcessor.onNext(LlmResponse.builder().interrupted(true).build());
      clearInputAudioBufferIfOpen();
    } else if ("completed".equals(status) || status.isEmpty()) {
      // Align turnComplete with response.done (after audio finishes), not transcript.done.
      logger.info(
          "Realtime response completed (status={}) — emitting turnComplete.",
          status.isEmpty() ? "unknown" : status);
      responseProcessor.onNext(LlmResponse.builder().turnComplete(true).build());
    } else {
      logger.info("Realtime response ended with status={}.", status);
    }

    if (resp != null) {
      JSONObject usage = resp.optJSONObject("usage");
      if (usage != null) {
        logger.info(
            "Realtime token usage — input: {}, output: {}",
            usage.optInt("input_tokens", 0),
            usage.optInt("output_tokens", 0));

        GenerateContentResponseUsageMetadata.Builder usageBuilder =
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(usage.optInt("input_tokens", 0))
                .candidatesTokenCount(usage.optInt("output_tokens", 0))
                .totalTokenCount(usage.optInt("total_tokens", 0));

        JSONObject inputDetails = usage.optJSONObject("input_token_details");
        if (inputDetails != null && inputDetails.has("audio_tokens")) {
          usageBuilder.promptTokensDetails(
              ImmutableList.of(
                  ModalityTokenCount.builder()
                      .modality(com.google.genai.types.MediaModality.Known.AUDIO)
                      .tokenCount(inputDetails.optInt("audio_tokens", 0))
                      .build()));
        }

        JSONObject outputDetails = usage.optJSONObject("output_token_details");
        if (outputDetails != null && outputDetails.has("audio_tokens")) {
          usageBuilder.candidatesTokensDetails(
              ImmutableList.of(
                  ModalityTokenCount.builder()
                      .modality(com.google.genai.types.MediaModality.Known.AUDIO)
                      .tokenCount(outputDetails.optInt("audio_tokens", 0))
                      .build()));
        }
        responseProcessor.onNext(LlmResponse.builder().usageMetadata(usageBuilder.build()).build());
      }
    }
  }

  private void handleInputTranscription(JSONObject event) {
    String transcript = event.optString("transcript", "").trim();
    if (transcript.isEmpty()) return;

    if (transcript.length() <= 2
        || WHISPER_HALLUCINATIONS.contains(transcript.toLowerCase(java.util.Locale.ROOT))) {
      logger.debug("Filtered likely Whisper hallucination: '{}'", transcript);
      return;
    }

    // Mirror Gemini Live: transcription is independent of the model turn and must NOT
    // arrive as user-role content (LiveAudioSession treats user-role during playback
    // as a turn boundary and fires voice_complete prematurely).
    responseProcessor.onNext(
        LlmResponse.builder()
            .inputTranscription(Transcription.builder().text(transcript).finished(true).build())
            .build());
  }

  private void handleErrorEvent(JSONObject event) {
    JSONObject error = event.optJSONObject("error");
    String message = error != null ? error.optString("message", "Unknown error") : "Unknown error";
    logger.error("Realtime API error: {}", message);
    responseProcessor.onNext(LlmResponse.builder().errorMessage(message).build());
  }

  // ==================== BaseLlmConnection Methods ====================

  @Override
  public Completable sendHistory(List<Content> history) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          for (Content content : history) {
            sendContentOverWebSocket(content);
          }
        });
  }

  @Override
  public Completable sendContent(Content content) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          Objects.requireNonNull(content, "content cannot be null");

          boolean isFunctionResponse =
              content.parts().isPresent()
                  && !content.parts().get().isEmpty()
                  && content.parts().get().get(0).functionResponse().isPresent();

          if (isFunctionResponse) {
            sendFunctionResponseOverWebSocket(content);
          } else {
            sendContentOverWebSocket(content);
            sendResponseCreate();
          }
        });
  }

  @Override
  public Completable sendRealtime(Blob blob) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          Objects.requireNonNull(blob, "blob cannot be null");

          byte[] audioData = blob.data().orElse(new byte[0]);
          if (audioData.length == 0) {
            return;
          }

          String base64Audio = Base64.getEncoder().encodeToString(audioData);
          JSONObject event = new JSONObject();
          event.put("type", "input_audio_buffer.append");
          event.put("audio", base64Audio);
          sendMessage(event.toString());
        });
  }

  @Override
  public Completable clearRealtimeAudioBuffer() {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          clearInputAudioBufferIfOpen();
        });
  }

  private void clearInputAudioBufferIfOpen() {
    if (closed.get()) {
      return;
    }
    JSONObject event = new JSONObject();
    event.put("type", "input_audio_buffer.clear");
    logger.debug("Sending input_audio_buffer.clear");
    sendMessage(event.toString());
  }

  @Override
  public Flowable<LlmResponse> receive() {
    return responseProcessor;
  }

  @Override
  public void close() {
    closeInternal(null);
  }

  @Override
  public void close(Throwable throwable) {
    Objects.requireNonNull(throwable, "throwable cannot be null");
    closeInternal(throwable);
  }

  // ==================== Internal Helpers ====================

  private void sendContentOverWebSocket(Content content) {
    String role = content.role().orElse("user");
    String text =
        content.parts().isPresent()
            ? content.parts().get().stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"))
            : "";

    JSONObject event = new JSONObject();
    event.put("type", "conversation.item.create");

    JSONObject item = new JSONObject();
    item.put("type", "message");
    item.put("role", role.equals("model") ? "assistant" : role);

    JSONArray contentArr = new JSONArray();
    JSONObject contentItem = new JSONObject();
    contentItem.put("type", "input_text");
    contentItem.put("text", text);
    contentArr.put(contentItem);
    item.put("content", contentArr);

    event.put("item", item);
    sendMessage(event.toString());
  }

  private void sendFunctionResponseOverWebSocket(Content content) {
    content
        .parts()
        .ifPresent(
            parts ->
                parts.forEach(
                    part ->
                        part.functionResponse()
                            .ifPresent(
                                fr -> {
                                  JSONObject event = new JSONObject();
                                  event.put("type", "conversation.item.create");

                                  JSONObject item = new JSONObject();
                                  item.put("type", "function_call_output");
                                  String callId =
                                      fr.id().orElse("call_" + fr.name().orElse("unknown"));
                                  item.put("call_id", callId);
                                  item.put(
                                      "output",
                                      new JSONObject(fr.response().orElse(Map.of())).toString());

                                  event.put("item", item);
                                  sendMessage(event.toString());
                                })));

    sendResponseCreate();
  }

  private void sendResponseCreate() {
    JSONObject event = new JSONObject();
    event.put("type", "response.create");
    sendMessage(event.toString());
  }

  private void sendMessage(String json) {
    synchronized (wsLock) {
      if (wsClient == null || !wsClient.isOpen()) {
        logger.warn("WebSocket is not open, cannot send message.");
        return;
      }
      try {
        wsClient.send(json);
        logger.debug("Sent over WebSocket: {} bytes", json.getBytes(StandardCharsets.UTF_8).length);
      } catch (Exception e) {
        logger.error("Failed to send over WebSocket", e);
      }
    }
  }

  private void closeInternal(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.info("Closing AzureRealtimeLlmConnection.");
      pendingFunctionCalls.clear();

      if (throwable == null) {
        responseProcessor.onComplete();
      } else {
        responseProcessor.onError(throwable);
      }

      synchronized (wsLock) {
        try {
          if (wsClient != null && wsClient.isOpen()) {
            wsClient.closeBlocking();
          }
        } catch (Exception e) {
          logger.warn("Error closing WebSocket", e);
        } finally {
          wsClient = null;
        }
      }
    }
  }

  // ==================== WebSocket Client ====================

  private class RealtimeWebSocketClient extends WebSocketClient {

    RealtimeWebSocketClient(URI uri, String apiKey) {
      super(uri);
      addHeader("api-key", apiKey);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
      logger.info("WebSocket connection opened (status: {})", handshake.getHttpStatus());
    }

    @Override
    public void onMessage(String message) {
      handleMessage(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      logger.info("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
      if (!closed.get()) {
        closeInternal(
            new IllegalStateException("WebSocket closed unexpectedly: " + code + " " + reason));
      }
    }

    @Override
    public void onError(Exception ex) {
      logger.error("WebSocket error", ex);
      if (!closed.get()) {
        closeInternal(ex);
      }
    }
  }
}
