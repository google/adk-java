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

package com.google.adk.models.sarvamai.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.models.sarvamai.SarvamAiException;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Base64;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sarvam AI Text-to-Speech service with both REST and WebSocket streaming support.
 *
 * <p>REST mode ({@link #synthesize}): Sends text and returns the complete audio as a byte array
 * (decoded from base64). Uses the Bulbul v3 model with 30+ speaker voices.
 *
 * <p>WebSocket streaming mode ({@link #synthesizeStream}): Opens a persistent WebSocket connection
 * for progressive audio chunk delivery with low latency. Audio chunks are emitted as they are
 * synthesized, enabling real-time playback.
 *
 * @author Sandeep Belgavi
 */
public final class SarvamTtsService {

  private static final Logger logger = LoggerFactory.getLogger(SarvamTtsService.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

  private final SarvamAiConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public SarvamTtsService(SarvamAiConfig config, OkHttpClient httpClient) {
    this.config = Objects.requireNonNull(config);
    this.httpClient = Objects.requireNonNull(httpClient);
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Synthesizes speech from text synchronously via the REST endpoint.
   *
   * @param text the text to convert to speech (max 2500 chars for bulbul:v3)
   * @param targetLanguageCode BCP-47 language code (e.g., "en-IN", "hi-IN")
   * @return decoded audio bytes (WAV format by default)
   */
  public byte[] synthesize(String text, String targetLanguageCode) {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(targetLanguageCode, "targetLanguageCode must not be null");

    String model = config.ttsModel().orElse("bulbul:v3");
    String speaker = config.ttsSpeaker().orElse("shubh");
    Double pace = config.ttsPace().isPresent() ? config.ttsPace().getAsDouble() : null;
    Integer sampleRate =
        config.ttsSampleRate().isPresent() ? config.ttsSampleRate().getAsInt() : null;

    TtsRequest ttsRequest =
        new TtsRequest(text, targetLanguageCode, model, speaker, pace, sampleRate);

    try {
      String body = objectMapper.writeValueAsString(ttsRequest);

      Request request =
          new Request.Builder()
              .url(config.ttsEndpoint())
              .addHeader("api-subscription-key", config.apiKey())
              .addHeader("Content-Type", "application/json")
              .post(RequestBody.create(body, JSON_MEDIA_TYPE))
              .build();

      logger.debug(
          "Sending TTS request to {} with model={}, speaker={}",
          config.ttsEndpoint(),
          model,
          speaker);

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          String errorBody = response.body() != null ? response.body().string() : "";
          throw new SarvamAiException(
              "TTS request failed: " + response.code() + " " + errorBody,
              response.code(),
              null,
              null);
        }

        TtsResponse ttsResponse =
            objectMapper.readValue(response.body().string(), TtsResponse.class);
        if (ttsResponse.getAudios() == null || ttsResponse.getAudios().isEmpty()) {
          throw new SarvamAiException("TTS response contained no audio data");
        }

        String combinedBase64 = String.join("", ttsResponse.getAudios());
        return Base64.getDecoder().decode(combinedBase64);
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("TTS synthesis failed", e);
    }
  }

  /** Async version of {@link #synthesize}. */
  public Single<byte[]> synthesizeAsync(String text, String targetLanguageCode) {
    return Single.fromCallable(() -> synthesize(text, targetLanguageCode))
        .subscribeOn(Schedulers.io());
  }

  /**
   * Streams TTS audio via WebSocket for low-latency, progressive playback.
   *
   * <p>Opens a WebSocket to Sarvam's streaming TTS endpoint, sends config + text, and emits decoded
   * audio chunks as they arrive. Each chunk is a raw audio byte array ready for playback.
   *
   * @param text the text to synthesize
   * @param targetLanguageCode BCP-47 language code
   * @return a Flowable of audio byte[] chunks
   */
  public Flowable<byte[]> synthesizeStream(String text, String targetLanguageCode) {
    Objects.requireNonNull(text, "text must not be null");
    Objects.requireNonNull(targetLanguageCode, "targetLanguageCode must not be null");

    return Flowable.create(
        emitter -> {
          String model = config.ttsModel().orElse("bulbul:v3");
          String speaker = config.ttsSpeaker().orElse("shubh");

          String wsUrl = config.ttsWsEndpoint() + "?model=" + model;

          Request wsRequest =
              new Request.Builder()
                  .url(wsUrl)
                  .addHeader("api-subscription-key", config.apiKey())
                  .build();

          logger.debug("Opening TTS WebSocket to {}", wsUrl);

          WebSocket webSocket =
              httpClient.newWebSocket(
                  wsRequest,
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response response) {
                      logger.debug("TTS WebSocket connected");

                      String configMsg =
                          String.format(
                              "{\"type\":\"config\",\"data\":{\"speaker\":\"%s\","
                                  + "\"target_language_code\":\"%s\"}}",
                              speaker, targetLanguageCode);
                      ws.send(configMsg);

                      String textMsg =
                          String.format(
                              "{\"type\":\"text\",\"data\":{\"text\":\"%s\"}}",
                              text.replace("\"", "\\\""));
                      ws.send(textMsg);

                      ws.send("{\"type\":\"flush\"}");
                    }

                    @Override
                    public void onMessage(WebSocket ws, String messageText) {
                      try {
                        JsonNode node = objectMapper.readTree(messageText);
                        String type = node.path("type").asText("");

                        if ("audio".equals(type)) {
                          String audioBase64 = node.path("data").path("audio").asText("");
                          if (!audioBase64.isEmpty()) {
                            byte[] audioChunk = Base64.getDecoder().decode(audioBase64);
                            emitter.onNext(audioChunk);
                          }
                        } else if ("event".equals(type)) {
                          String eventType = node.path("data").path("event_type").asText("");
                          if ("final".equals(eventType)) {
                            ws.close(1000, "Synthesis complete");
                          }
                        }
                      } catch (Exception e) {
                        logger.warn("Failed to parse TTS WS message", e);
                      }
                    }

                    @Override
                    public void onClosing(WebSocket ws, int code, String reason) {
                      ws.close(code, reason);
                    }

                    @Override
                    public void onClosed(WebSocket ws, int code, String reason) {
                      logger.debug("TTS WebSocket closed: {} {}", code, reason);
                      emitter.onComplete();
                    }

                    @Override
                    public void onFailure(WebSocket ws, Throwable t, Response response) {
                      logger.error("TTS WebSocket failure", t);
                      if (!emitter.isCancelled()) {
                        emitter.onError(
                            new SarvamAiException("TTS WebSocket connection failed", t));
                      }
                    }
                  });

          emitter.setCancellable(() -> webSocket.close(1000, "Cancelled"));
        },
        BackpressureStrategy.BUFFER);
  }

  public boolean isAvailable() {
    return config.apiKey() != null && !config.apiKey().isEmpty();
  }
}
