package com.google.adk.models.azure;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
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
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket connection to Azure OpenAI GPT Realtime Translate.
 *
 * <p>Uses the translation session protocol ({@code /openai/v1/realtime/translations}): continuous
 * source audio in, translated audio and transcript deltas out. No {@code response.create} or agent
 * turn lifecycle.
 *
 * @see <a href="https://developers.openai.com/api/docs/guides/realtime-translation">Realtime
 *     translation</a>
 * @see <a
 *     href="https://learn.microsoft.com/en-us/azure/foundry/openai/concepts/gpt-realtime-translate">
 *     GPT Realtime Translate overview</a>
 */
public final class AzureRealtimeTranslateLlmConnection implements BaseLlmConnection {

  private static final Logger logger =
      LoggerFactory.getLogger(AzureRealtimeTranslateLlmConnection.class);

  private static final int CONNECT_TIMEOUT_SECONDS = 30;

  private final AzureConfig config;
  private final FlowableProcessor<LlmResponse> responseProcessor =
      PublishProcessor.<LlmResponse>create().toSerialized();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean sessionClosing = new AtomicBoolean(false);
  private final CountDownLatch connectedLatch = new CountDownLatch(1);
  private final Object wsLock = new Object();

  private final AtomicBoolean outputTranscriptHadDelta = new AtomicBoolean(false);

  private volatile TranslateWebSocketClient wsClient;

  AzureRealtimeTranslateLlmConnection(AzureConfig config, LlmRequest llmRequest) {
    this.config = Objects.requireNonNull(config, "config cannot be null");
    Objects.requireNonNull(llmRequest, "llmRequest cannot be null");

    try {
      initializeConnection();
    } catch (Exception e) {
      logger.error("Failed to initialize Azure Realtime Translate WebSocket connection", e);
      responseProcessor.onError(e);
      throw new IllegalStateException(
          "Failed to initialize Azure Realtime Translate WebSocket connection", e);
    }
  }

  /** Returns true when the translation WebSocket is open and session.created was received. */
  public boolean isConnected() {
    TranslateWebSocketClient client = wsClient;
    return client != null && client.isOpen() && connectedLatch.getCount() == 0;
  }

  private void initializeConnection() throws Exception {
    String apiKey = config.apiKey();
    String wsUrl = config.translationsWebSocketUrl();

    logger.info(
        "Connecting to Azure Realtime Translate WebSocket: {}",
        AzureConfig.maskWebSocketUrl(wsUrl));

    URI uri = URI.create(wsUrl);
    TranslateWebSocketClient client = new TranslateWebSocketClient(uri, apiKey);
    synchronized (wsLock) {
      wsClient = client;
    }

    try {
      client.connectBlocking(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!client.isOpen()) {
        throw new IllegalStateException("Translation WebSocket failed to open within timeout");
      }

      if (!connectedLatch.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
            "Translation WebSocket connected but session.created not received");
      }

      sendSessionUpdate();
      logger.info(
          "Azure Realtime Translate connection established (target language={}).",
          config.translateTargetLanguage());
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
      logger.warn("Error closing translation WebSocket during init cleanup", e);
    }
  }

  private void sendSessionUpdate() {
    JSONObject event = new JSONObject();
    event.put("type", "session.update");

    JSONObject session = new JSONObject();
    JSONObject audio = new JSONObject();
    JSONObject output = new JSONObject();
    output.put("language", config.translateTargetLanguage());
    audio.put("output", output);
    session.put("audio", audio);

    event.put("session", session);
    sendMessage(event.toString());
    logger.info(
        "Sent translation session.update with language={}", config.translateTargetLanguage());
  }

  private void handleMessage(String json) {
    if (closed.get()) {
      return;
    }

    try {
      JSONObject event = new JSONObject(json);
      String eventType = event.optString("type", "");

      logger.debug("Translate WS event: {}", eventType);

      switch (eventType) {
        case "session.created":
          logger.info(
              "Translation session created: {}",
              event.optJSONObject("session") != null
                  ? event.optJSONObject("session").optString("id", "unknown")
                  : "unknown");
          connectedLatch.countDown();
          break;

        case "session.updated":
          logger.info("Translation session updated.");
          break;

        case "session.output_audio.delta":
          handleOutputAudioDelta(event);
          break;

        case "session.output_transcript.delta":
          handleOutputTranscriptDelta(event);
          break;

        case "session.input_transcript.delta":
          handleInputTranscriptDelta(event);
          break;

        case "session.closed":
          logger.info("Translation session closed by server.");
          activeCloseComplete();
          break;

        case "error":
          handleErrorEvent(event);
          break;

        default:
          logger.trace("Unhandled translation event type: {}", eventType);
          break;
      }
    } catch (JSONException e) {
      logger.warn("Failed to parse translation WebSocket message: {}", json, e);
    }
  }

  private void handleOutputAudioDelta(JSONObject event) {
    String base64Audio = event.optString("delta", "");
    if (base64Audio.isEmpty()) {
      return;
    }
    try {
      byte[] audioBytes = Base64.getDecoder().decode(base64Audio);
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
      logger.warn("Failed to decode translation audio delta", e);
    }
  }

  private void handleOutputTranscriptDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      outputTranscriptHadDelta.set(true);
      responseProcessor.onNext(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText(delta)).build())
              .partial(true)
              .build());
    }
  }

  private void handleInputTranscriptDelta(JSONObject event) {
    String delta = event.optString("delta", "");
    if (!delta.isEmpty()) {
      responseProcessor.onNext(
          LlmResponse.builder()
              .inputTranscription(Transcription.builder().text(delta).finished(false).build())
              .build());
    }
  }

  private void handleErrorEvent(JSONObject event) {
    JSONObject error = event.optJSONObject("error");
    String message = error != null ? error.optString("message", "Unknown error") : "Unknown error";
    logger.error("Realtime Translate API error: {}", message);
    responseProcessor.onNext(LlmResponse.builder().errorMessage(message).build());
  }

  private void activeCloseComplete() {
    if (!closed.get()) {
      responseProcessor.onNext(LlmResponse.builder().turnComplete(true).build());
    }
  }

  @Override
  public Completable sendHistory(List<Content> history) {
    return Completable.complete();
  }

  @Override
  public Completable sendContent(Content content) {
    return Completable.complete();
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
          event.put("type", "session.input_audio_buffer.append");
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
          JSONObject event = new JSONObject();
          event.put("type", "session.input_audio_buffer.clear");
          logger.debug("Sending session.input_audio_buffer.clear");
          sendMessage(event.toString());
        });
  }

  /** Gracefully closes the translation session and flushes pending output. */
  public Completable closeTranslationSession() {
    return Completable.fromAction(
        () -> {
          if (closed.get() || sessionClosing.getAndSet(true)) {
            return;
          }
          JSONObject event = new JSONObject();
          event.put("type", "session.close");
          sendMessage(event.toString());
          logger.info("Sent session.close for translation.");
        });
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

  private void sendMessage(String json) {
    synchronized (wsLock) {
      if (wsClient == null || !wsClient.isOpen()) {
        logger.warn("Translation WebSocket is not open, cannot send message.");
        return;
      }
      try {
        wsClient.send(json);
        logger.trace(
            "Sent over translation WebSocket: {} bytes",
            json.getBytes(StandardCharsets.UTF_8).length);
      } catch (Exception e) {
        logger.error("Failed to send over translation WebSocket", e);
      }
    }
  }

  private void closeInternal(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.info("Closing AzureRealtimeTranslateLlmConnection.");

      if (throwable == null) {
        responseProcessor.onComplete();
      } else {
        responseProcessor.onError(throwable);
      }

      synchronized (wsLock) {
        try {
          if (wsClient != null && wsClient.isOpen()) {
            if (!sessionClosing.get()) {
              try {
                JSONObject event = new JSONObject();
                event.put("type", "session.close");
                wsClient.send(event.toString());
              } catch (Exception e) {
                logger.debug("session.close on shutdown failed: {}", e.getMessage());
              }
            }
            wsClient.closeBlocking();
          }
        } catch (Exception e) {
          logger.warn("Error closing translation WebSocket", e);
        } finally {
          wsClient = null;
        }
      }
    }
  }

  private class TranslateWebSocketClient extends WebSocketClient {

    TranslateWebSocketClient(URI uri, String apiKey) {
      super(uri);
      addHeader("api-key", apiKey);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
      logger.info("Translation WebSocket opened (status: {})", handshake.getHttpStatus());
    }

    @Override
    public void onMessage(String message) {
      handleMessage(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      logger.info(
          "Translation WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
      if (!closed.get()) {
        closeInternal(
            new IllegalStateException(
                "Translation WebSocket closed unexpectedly: " + code + " " + reason));
      }
    }

    @Override
    public void onError(Exception ex) {
      logger.error("Translation WebSocket error", ex);
      if (!closed.get()) {
        closeInternal(ex);
      }
    }
  }
}
