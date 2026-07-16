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

package com.google.adk.transcription.client;

import com.google.adk.JsonBaseModel;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionException;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP client for Whisper transcription API. Handles communication with hosted Whisper service.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class WhisperApiClient {
  private static final Logger logger = LoggerFactory.getLogger(WhisperApiClient.class);
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

  private final OkHttpClient httpClient;
  private final String baseUrl;

  public WhisperApiClient(String baseUrl, int maxRetries) {
    this.baseUrl = baseUrl;
    this.httpClient =
        new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
  }

  /**
   * Transcribes audio data using Whisper API.
   *
   * @param audioData Raw audio bytes
   * @param config Transcription configuration
   * @return WhisperResponse containing transcription result
   * @throws TranscriptionException if transcription fails
   */
  public WhisperResponse transcribe(byte[] audioData, TranscriptionConfig config)
      throws TranscriptionException {
    return executeWithRetry(
        () -> {
          String endpoint = baseUrl + "/audio/transcribe";

          // Build request
          WhisperRequest request =
              WhisperRequest.builder()
                  .audio(Base64.getEncoder().encodeToString(audioData))
                  .language(config.getLanguage())
                  .format(config.getAudioFormat().getMimeType())
                  .build();

          String jsonBody = request.toJson();

          Request httpRequest =
              new Request.Builder()
                  .url(endpoint)
                  .post(RequestBody.create(jsonBody, JSON))
                  .addHeader("Content-Type", "application/json")
                  .addHeader("Accept", "application/json")
                  .build();

          try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (response.isSuccessful() && response.body() != null) {
              String responseBody = response.body().string();
              return JsonBaseModel.fromJsonString(responseBody, WhisperResponse.class);
            } else {
              String errorBody = response.body() != null ? response.body().string() : "No body";
              throw new TranscriptionException(
                  String.format("HTTP %d: %s", response.code(), errorBody));
            }
          } catch (IOException e) {
            throw new TranscriptionException("Failed to execute transcription request", e);
          }
        },
        config.getMaxRetries());
  }

  /**
   * Checks if the Whisper service is healthy.
   *
   * @return true if service is available
   */
  public boolean healthCheck() {
    try {
      String healthEndpoint = baseUrl + "/health";
      Request request = new Request.Builder().url(healthEndpoint).get().build();

      try (Response response =
          httpClient
              .newBuilder()
              .connectTimeout(5, TimeUnit.SECONDS)
              .readTimeout(5, TimeUnit.SECONDS)
              .build()
              .newCall(request)
              .execute()) {
        return response.isSuccessful();
      }
    } catch (Exception e) {
      logger.warn("Health check failed", e);
      return false;
    }
  }

  private <T> T executeWithRetry(RetryableOperation<T> operation, int maxRetries)
      throws TranscriptionException {
    TranscriptionException lastException = null;

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return operation.execute();
      } catch (TranscriptionException e) {
        lastException = e;
        if (attempt < maxRetries) {
          logger.warn("Transcription attempt {} failed, retrying...", attempt + 1);
          try {
            Thread.sleep(1000L * (attempt + 1)); // Exponential backoff
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException("Interrupted", ie);
          }
        }
      }
    }

    throw lastException;
  }

  @FunctionalInterface
  private interface RetryableOperation<T> {
    T execute() throws TranscriptionException;
  }
}
