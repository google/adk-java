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

package com.google.adk.models.sarvamai.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.models.sarvamai.SarvamAiException;
import com.google.adk.transcription.ServiceHealth;
import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionEvent;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import com.google.adk.transcription.TranscriptionService;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Base64;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sarvam AI Speech-to-Text service implementing the ADK {@link TranscriptionService} interface.
 *
 * <p>Supports three modes of operation:
 *
 * <ul>
 *   <li><b>REST synchronous</b> ({@link #transcribe}): Single-shot transcription via {@code POST
 *       /speech-to-text} using model {@code saaras:v3}.
 *   <li><b>REST async</b> ({@link #transcribeAsync}): Same as above, executed on an IO scheduler.
 *   <li><b>WebSocket streaming</b> ({@link #transcribeStream}): Real-time streaming via WebSocket
 *       with VAD support, delivering partial and final transcription events.
 * </ul>
 *
 * @author Sandeep Belgavi
 */
public final class SarvamSttService implements TranscriptionService {

  private static final Logger logger = LoggerFactory.getLogger(SarvamSttService.class);

  private final SarvamAiConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public SarvamSttService(SarvamAiConfig config, OkHttpClient httpClient) {
    this.config = Objects.requireNonNull(config);
    this.httpClient = Objects.requireNonNull(httpClient);
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public TranscriptionResult transcribe(byte[] audioData, TranscriptionConfig requestConfig)
      throws TranscriptionException {
    try {
      String sttModel = config.sttModel().orElse("saaras:v3");
      String mode = config.sttMode().orElse("transcribe");
      String languageCode = config.sttLanguageCode().orElse(requestConfig.getLanguage());

      RequestBody fileBody = RequestBody.create(audioData, MediaType.parse("audio/wav"));

      MultipartBody.Builder bodyBuilder =
          new MultipartBody.Builder()
              .setType(MultipartBody.FORM)
              .addFormDataPart("file", "audio.wav", fileBody)
              .addFormDataPart("model", sttModel)
              .addFormDataPart("mode", mode);

      if (languageCode != null && !"auto".equals(languageCode)) {
        bodyBuilder.addFormDataPart("language_code", languageCode);
      }

      Request request =
          new Request.Builder()
              .url(config.sttEndpoint())
              .addHeader("api-subscription-key", config.apiKey())
              .post(bodyBuilder.build())
              .build();

      logger.debug(
          "Sending STT request to {} with model={}, mode={}", config.sttEndpoint(), sttModel, mode);

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          String errorBody = response.body() != null ? response.body().string() : "";
          throw new TranscriptionException(
              "STT request failed with status " + response.code() + ": " + errorBody);
        }

        String responseBody = response.body().string();
        JsonNode root = objectMapper.readTree(responseBody);
        String transcript = root.path("transcript").asText("");
        String detectedLang = root.path("language_code").asText(null);

        TranscriptionResult.Builder resultBuilder =
            TranscriptionResult.builder().text(transcript).timestamp(System.currentTimeMillis());

        if (detectedLang != null) {
          resultBuilder.language(detectedLang);
        }

        return resultBuilder.build();
      }
    } catch (TranscriptionException e) {
      throw e;
    } catch (Exception e) {
      throw new TranscriptionException("STT transcription failed", e);
    }
  }

  @Override
  public Single<TranscriptionResult> transcribeAsync(
      byte[] audioData, TranscriptionConfig requestConfig) {
    return Single.fromCallable(() -> transcribe(audioData, requestConfig))
        .subscribeOn(Schedulers.io());
  }

  /**
   * Streams audio data to Sarvam's WebSocket STT endpoint for real-time transcription.
   *
   * <p>Audio chunks are base64-encoded and sent as JSON frames. The server responds with transcript
   * events including partial results and VAD signals (speech_start, speech_end).
   */
  @Override
  public Flowable<TranscriptionEvent> transcribeStream(
      Flowable<byte[]> audioStream, TranscriptionConfig requestConfig) {

    return Flowable.create(
        emitter -> {
          String sttModel = config.sttModel().orElse("saaras:v3");
          String mode = config.sttMode().orElse("transcribe");
          String languageCode = config.sttLanguageCode().orElse(requestConfig.getLanguage());

          StringBuilder wsUrl = new StringBuilder(config.sttWsEndpoint());
          wsUrl.append("?model=").append(sttModel);
          wsUrl.append("&mode=").append(mode);
          if (languageCode != null && !"auto".equals(languageCode)) {
            wsUrl.append("&language_code=").append(languageCode);
          }
          wsUrl.append("&high_vad_sensitivity=true");
          wsUrl.append("&vad_signals=true");

          Request wsRequest =
              new Request.Builder()
                  .url(wsUrl.toString())
                  .addHeader("api-subscription-key", config.apiKey())
                  .build();

          logger.debug("Opening STT WebSocket to {}", wsUrl);

          WebSocket webSocket =
              httpClient.newWebSocket(
                  wsRequest,
                  new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response response) {
                      logger.debug("STT WebSocket connected");
                      audioStream.subscribe(
                          chunk -> {
                            String base64Audio = Base64.getEncoder().encodeToString(chunk);
                            String frame =
                                String.format(
                                    "{\"audio\":\"%s\",\"encoding\":\"audio/wav\",\"sample_rate\":16000}",
                                    base64Audio);
                            ws.send(frame);
                          },
                          error -> {
                            logger.error("Audio stream error", error);
                            ws.close(1000, "Audio stream error");
                          },
                          () -> {
                            logger.debug("Audio stream completed, closing WebSocket");
                            ws.close(1000, "Stream complete");
                          });
                    }

                    @Override
                    public void onMessage(WebSocket ws, String text) {
                      try {
                        JsonNode node = objectMapper.readTree(text);
                        String type = node.path("type").asText("");

                        switch (type) {
                          case "transcript":
                          case "translation":
                            String transcript = node.path("text").asText("");
                            emitter.onNext(
                                TranscriptionEvent.builder()
                                    .text(transcript)
                                    .finished(true)
                                    .timestamp(System.currentTimeMillis())
                                    .build());
                            break;
                          case "speech_start":
                            logger.trace("VAD: speech started");
                            break;
                          case "speech_end":
                            logger.trace("VAD: speech ended");
                            break;
                          default:
                            logger.trace("Received STT WS message type: {}", type);
                        }
                      } catch (Exception e) {
                        logger.warn("Failed to parse STT WS message: {}", text, e);
                      }
                    }

                    @Override
                    public void onClosing(WebSocket ws, int code, String reason) {
                      logger.debug("STT WebSocket closing: {} {}", code, reason);
                      ws.close(code, reason);
                    }

                    @Override
                    public void onClosed(WebSocket ws, int code, String reason) {
                      logger.debug("STT WebSocket closed: {} {}", code, reason);
                      emitter.onComplete();
                    }

                    @Override
                    public void onFailure(WebSocket ws, Throwable t, Response response) {
                      logger.error("STT WebSocket failure", t);
                      if (!emitter.isCancelled()) {
                        emitter.onError(
                            new SarvamAiException("STT WebSocket connection failed", t));
                      }
                    }
                  });

          emitter.setCancellable(() -> webSocket.close(1000, "Cancelled"));
        },
        BackpressureStrategy.BUFFER);
  }

  @Override
  public boolean isAvailable() {
    return config.apiKey() != null && !config.apiKey().isEmpty();
  }

  @Override
  public ServiceType getServiceType() {
    return ServiceType.SARVAM;
  }

  @Override
  public ServiceHealth getHealth() {
    return ServiceHealth.builder().available(isAvailable()).serviceType(ServiceType.SARVAM).build();
  }
}
