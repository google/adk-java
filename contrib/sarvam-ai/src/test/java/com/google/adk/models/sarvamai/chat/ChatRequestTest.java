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

package com.google.adk.models.sarvamai.chat;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * @author Sandeep Belgavi
 */
class ChatRequestTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void fromLlmRequest_mapsUserAndAssistantMessages() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(
                List.of(
                    Content.builder().role("user").parts(Part.fromText("Hello")).build(),
                    Content.builder().role("model").parts(Part.fromText("Hi there")).build(),
                    Content.builder().role("user").parts(Part.fromText("How?")).build()))
            .build();

    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").temperature(0.5).build();
    ChatRequest request = ChatRequest.fromLlmRequest("sarvam-m", llmRequest, config, false);

    assertThat(request.getModel()).isEqualTo("sarvam-m");
    assertThat(request.getMessages()).hasSize(3);
    assertThat(request.getMessages().get(0).getRole()).isEqualTo("user");
    assertThat(request.getMessages().get(1).getRole()).isEqualTo("assistant");
    assertThat(request.getMessages().get(2).getRole()).isEqualTo("user");
    assertThat(request.getTemperature()).isWithin(0.001).of(0.5);
    assertThat(request.getStream()).isNull();
  }

  @Test
  void fromLlmRequest_includesSystemInstructions() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.builder().role("user").parts(Part.fromText("Hello")).build()))
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(Part.fromText("You are a helpful assistant"))
                            .build())
                    .build())
            .build();

    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").build();
    ChatRequest request = ChatRequest.fromLlmRequest("sarvam-m", llmRequest, config, true);

    assertThat(request.getMessages().get(0).getRole()).isEqualTo("system");
    assertThat(request.getMessages().get(0).getContent()).isEqualTo("You are a helpful assistant");
    assertThat(request.getStream()).isTrue();
  }

  @Test
  void fromLlmRequest_appliesConfigParameters() throws Exception {
    SarvamAiConfig config =
        SarvamAiConfig.builder()
            .apiKey("key")
            .temperature(0.7)
            .topP(0.9)
            .maxTokens(100)
            .reasoningEffort("high")
            .wikiGrounding(true)
            .build();

    LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.builder().role("user").parts(Part.fromText("test")).build()))
            .build();

    ChatRequest request = ChatRequest.fromLlmRequest("sarvam-m", llmRequest, config, false);

    assertThat(request.getTemperature()).isWithin(0.001).of(0.7);
    assertThat(request.getTopP()).isWithin(0.001).of(0.9);
    assertThat(request.getMaxTokens()).isEqualTo(100);
    assertThat(request.getReasoningEffort()).isEqualTo("high");
    assertThat(request.getWikiGrounding()).isTrue();
  }

  @Test
  void serialization_excludesNullFields() throws Exception {
    SarvamAiConfig config = SarvamAiConfig.builder().apiKey("key").build();
    LlmRequest llmRequest =
        LlmRequest.builder()
            .contents(List.of(Content.builder().role("user").parts(Part.fromText("Hi")).build()))
            .build();

    ChatRequest request = ChatRequest.fromLlmRequest("sarvam-m", llmRequest, config, false);
    String json = objectMapper.writeValueAsString(request);

    assertThat(json).doesNotContain("temperature");
    assertThat(json).doesNotContain("stream");
    assertThat(json).doesNotContain("wiki_grounding");
    assertThat(json).contains("\"model\":\"sarvam-m\"");
    assertThat(json).contains("\"messages\"");
  }
}
