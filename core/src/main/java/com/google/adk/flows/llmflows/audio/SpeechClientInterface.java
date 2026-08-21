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

package com.google.adk.flows.llmflows.audio;

import com.google.cloud.speech.v1.RecognitionAudio;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.RecognizeResponse;

/**
 * Interface for a speech-to-text client. Allows for different implementations (e.g., Cloud, Mocks).
 */
public interface SpeechClientInterface extends AutoCloseable {

  /**
   * Performs synchronous speech recognition.
   *
   * @param config The recognition configuration.
   * @param audio The audio data to transcribe.
   * @return The recognition response.
   * @throws Exception if an error occurs during recognition.
   */
  RecognizeResponse recognize(RecognitionConfig config, RecognitionAudio audio) throws Exception;

  /**
   * Closes the client and releases any resources.
   *
   * @throws Exception if an error occurs during closing.
   */
  @Override
  void close() throws Exception;
}
