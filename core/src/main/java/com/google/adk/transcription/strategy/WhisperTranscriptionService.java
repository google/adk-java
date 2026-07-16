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

package com.google.adk.transcription.strategy;

import com.google.adk.transcription.ServiceHealth;
import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionEvent;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import com.google.adk.transcription.TranscriptionService;
import com.google.adk.transcription.client.WhisperApiClient;
import com.google.adk.transcription.processor.AudioChunkAggregator;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whisper transcription service implementation.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class WhisperTranscriptionService implements TranscriptionService {
  private static final Logger logger = LoggerFactory.getLogger(WhisperTranscriptionService.class);

  private final WhisperApiClient apiClient;
  private final TranscriptionConfig config;

  public WhisperTranscriptionService(WhisperApiClient apiClient, TranscriptionConfig config) {
    this.apiClient = apiClient;
    this.config = config;
  }

  @Override
  public TranscriptionResult transcribe(byte[] audioData, TranscriptionConfig requestConfig)
      throws TranscriptionException {
    try {
      TranscriptionResult result =
          apiClient.transcribe(audioData, requestConfig).toTranscriptionResult();
      logger.debug("Transcribed {} bytes to text: {}", audioData.length, result.getText());
      return result;
    } catch (Exception e) {
      logger.error("Error transcribing audio", e);
      throw new TranscriptionException("Transcription failed", e);
    }
  }

  @Override
  public Single<TranscriptionResult> transcribeAsync(
      byte[] audioData, TranscriptionConfig requestConfig) {
    return Single.fromCallable(() -> transcribe(audioData, requestConfig))
        .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io());
  }

  @Override
  public Flowable<TranscriptionEvent> transcribeStream(
      Flowable<byte[]> audioStream, TranscriptionConfig requestConfig) {
    AudioChunkAggregator aggregator =
        new AudioChunkAggregator(
            requestConfig.getAudioFormat(), Duration.ofMillis(requestConfig.getChunkSizeMs()));

    return audioStream
        .buffer(requestConfig.getChunkSizeMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .map(
            chunks -> {
              // Aggregate chunks
              byte[] aggregated = aggregator.aggregate(chunks);
              try {
                return transcribe(aggregated, requestConfig);
              } catch (TranscriptionException e) {
                logger.error("Stream transcription error", e);
                throw new RuntimeException(e);
              }
            })
        .map(this::mapToTranscriptionEvent);
  }

  @Override
  public boolean isAvailable() {
    return apiClient.healthCheck();
  }

  @Override
  public ServiceType getServiceType() {
    return ServiceType.WHISPER;
  }

  @Override
  public ServiceHealth getHealth() {
    long startTime = System.currentTimeMillis();
    boolean available = isAvailable();
    long responseTime = System.currentTimeMillis() - startTime;

    return ServiceHealth.builder()
        .available(available)
        .serviceType(ServiceType.WHISPER)
        .responseTimeMs(responseTime)
        .build();
  }

  private TranscriptionEvent mapToTranscriptionEvent(TranscriptionResult result) {
    return TranscriptionEvent.builder()
        .text(result.getText())
        .finished(true)
        .timestamp(result.getTimestamp())
        .language(result.getLanguage().orElse(null))
        .build();
  }
}
