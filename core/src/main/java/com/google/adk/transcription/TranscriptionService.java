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

package com.google.adk.transcription;

import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

/**
 * Core interface for transcription services. Implementations provide audio-to-text transcription
 * capabilities.
 *
 * <p>This interface follows the Strategy Pattern, allowing different transcription providers
 * (Whisper, Gemini, Azure, etc.) to be used interchangeably.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public interface TranscriptionService {

  /**
   * Transcribes audio data synchronously.
   *
   * @param audioData Raw audio bytes
   * @param config Transcription configuration
   * @return Transcription result
   * @throws TranscriptionException if transcription fails
   */
  TranscriptionResult transcribe(byte[] audioData, TranscriptionConfig config)
      throws TranscriptionException;

  /**
   * Transcribes audio data asynchronously using RxJava Single.
   *
   * @param audioData Raw audio bytes
   * @param config Transcription configuration
   * @return Single containing transcription result
   */
  Single<TranscriptionResult> transcribeAsync(byte[] audioData, TranscriptionConfig config);

  /**
   * Streams transcription results for real-time audio. Processes audio chunks and returns
   * transcription events as they become available.
   *
   * @param audioStream Flowable of audio chunks
   * @param config Transcription configuration
   * @return Flowable of transcription events
   */
  Flowable<TranscriptionEvent> transcribeStream(
      Flowable<byte[]> audioStream, TranscriptionConfig config);

  /**
   * Checks if the service is available and healthy.
   *
   * @return true if service is available
   */
  boolean isAvailable();

  /**
   * Gets the service type identifier.
   *
   * @return Service type
   */
  ServiceType getServiceType();

  /**
   * Gets service health status with details.
   *
   * @return Health status information
   */
  ServiceHealth getHealth();
}
