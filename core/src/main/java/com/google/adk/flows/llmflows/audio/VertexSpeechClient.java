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
import com.google.cloud.speech.v1.SpeechClient;
import java.io.IOException;

/** Implementation of SpeechClientInterface using Vertex AI SpeechClient. */
public class VertexSpeechClient implements SpeechClientInterface {

  private final SpeechClient speechClient;

  /**
   * Constructs a VertexSpeechClient, initializing the underlying Google Cloud SpeechClient.
   *
   * @throws IOException if SpeechClient creation fails.
   */
  public VertexSpeechClient() throws IOException {
    this.speechClient = SpeechClient.create();
  }

  /**
   * Performs synchronous speech recognition on the given audio input.
   *
   * @param config Recognition configuration (e.g., language, encoding).
   * @param audio Audio data to recognize.
   * @return The recognition result.
   */
  @Override
  public RecognizeResponse recognize(RecognitionConfig config, RecognitionAudio audio) {
    // The original SpeechClient.recognize doesn't declare checked exceptions other than what might
    // be runtime. The interface declares Exception to be more general for other implementations.
    return speechClient.recognize(config, audio);
  }

  @Override
  public void close() throws Exception {
    if (speechClient != null) {
      speechClient.close();
    }
  }
}
