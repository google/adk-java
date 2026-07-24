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

package com.google.adk.models.external;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Transcription;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ExternalLlmResponseSubclassTest {

  @Test
  public void subclassOutsidePackage_canInstantiate() {
    LlmResponse response = new ExternalLlmResponse();

    assertThat(response.content()).isEmpty();
  }

  private static final class ExternalLlmResponse extends LlmResponse {

    @Override
    public Optional<Content> content() {
      return Optional.empty();
    }

    @Override
    public Optional<GroundingMetadata> groundingMetadata() {
      return Optional.empty();
    }

    @Override
    public Optional<List<CustomMetadata>> customMetadata() {
      return Optional.empty();
    }

    @Override
    public Optional<Boolean> partial() {
      return Optional.empty();
    }

    @Override
    public Optional<Boolean> turnComplete() {
      return Optional.empty();
    }

    @Override
    public Optional<FinishReason> errorCode() {
      return Optional.empty();
    }

    @Override
    public Optional<FinishReason> finishReason() {
      return Optional.empty();
    }

    @Override
    public Optional<Double> avgLogprobs() {
      return Optional.empty();
    }

    @Override
    public Optional<String> errorMessage() {
      return Optional.empty();
    }

    @Override
    public Optional<Boolean> interrupted() {
      return Optional.empty();
    }

    @Override
    public Optional<GenerateContentResponseUsageMetadata> usageMetadata() {
      return Optional.empty();
    }

    @Override
    public Optional<String> modelVersion() {
      return Optional.empty();
    }

    @Override
    public Optional<Transcription> inputTranscription() {
      return Optional.empty();
    }

    @Override
    public Optional<Transcription> outputTranscription() {
      return Optional.empty();
    }

    @Override
    public Builder toBuilder() {
      return LlmResponse.builder();
    }
  }
}
