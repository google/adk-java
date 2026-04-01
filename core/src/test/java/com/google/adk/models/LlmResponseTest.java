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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LlmResponseTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = JsonBaseModel.getMapper();
  }

  private Content createSampleContent(String text) {
    return Content.builder().parts(ImmutableList.of(Part.fromText(text))).build();
  }

  private Content createSampleFunctionCallContent(String functionName) {
    return Content.builder()
        .parts(
            ImmutableList.of(
                Part.builder()
                    .functionCall(FunctionCall.builder().name(functionName).build())
                    .build()))
        .build();
  }

  @Test
  public void testSerializationAndDeserialization_allFieldsPresent()
      throws JsonProcessingException {
    Content sampleContent = createSampleContent("Hello, world!");
    GenerateContentResponseUsageMetadata usageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    LlmResponse originalResponse =
        LlmResponse.builder()
            .content(sampleContent)
            .partial(true)
            .turnComplete(false)
            .errorCode(new FinishReason("ERR_123"))
            .errorMessage("An error occurred.")
            .interrupted(true)
            .usageMetadata(usageMetadata)
            .build();

    String json = originalResponse.toJson();
    assertThat(json).isNotNull();

    JsonNode jsonNode = objectMapper.readTree(json);
    assertThat(jsonNode.has("content")).isTrue();
    assertThat(jsonNode.get("content").get("parts").get(0).get("text").asText())
        .isEqualTo("Hello, world!");
    assertThat(jsonNode.get("partial").asBoolean()).isTrue();
    assertThat(jsonNode.get("turnComplete").asBoolean()).isFalse();
    assertThat(jsonNode.get("errorCode").asText()).isEqualTo("ERR_123");
    assertThat(jsonNode.get("errorMessage").asText()).isEqualTo("An error occurred.");
    assertThat(jsonNode.get("interrupted").asBoolean()).isTrue();
    assertThat(jsonNode.has("usageMetadata")).isTrue();
    assertThat(jsonNode.get("usageMetadata").get("promptTokenCount").asInt()).isEqualTo(10);
    assertThat(jsonNode.get("usageMetadata").get("candidatesTokenCount").asInt()).isEqualTo(20);
    assertThat(jsonNode.get("usageMetadata").get("totalTokenCount").asInt()).isEqualTo(30);

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);

    assertThat(deserializedResponse).isEqualTo(originalResponse);
    assertThat(deserializedResponse.content()).hasValue(sampleContent);
    assertThat(deserializedResponse.partial()).hasValue(true);
    assertThat(deserializedResponse.turnComplete()).hasValue(false);
    assertThat(deserializedResponse.errorCode()).hasValue(new FinishReason("ERR_123"));
    assertThat(deserializedResponse.errorMessage()).hasValue("An error occurred.");
    assertThat(deserializedResponse.interrupted()).hasValue(true);
    assertThat(deserializedResponse.usageMetadata()).hasValue(usageMetadata);
  }

  @Test
  public void testSerializationAndDeserialization_optionalFieldsEmpty()
      throws JsonProcessingException {
    Content sampleContent = createSampleFunctionCallContent("tool_abc");
    LlmResponse originalResponse =
        LlmResponse.builder().content(sampleContent).turnComplete(false).build();

    String json = originalResponse.toJson();
    assertThat(json).isNotNull();

    JsonNode jsonNode = objectMapper.readTree(json);
    assertThat(jsonNode.has("content")).isTrue();
    assertThat(jsonNode.has("groundingMetadata")).isFalse();
    assertThat(jsonNode.has("partial")).isFalse();
    assertThat(jsonNode.has("turnComplete")).isTrue();
    assertThat(jsonNode.get("turnComplete").asBoolean()).isFalse();
    assertThat(jsonNode.has("errorCode")).isFalse();
    assertThat(jsonNode.has("errorMessage")).isFalse();
    assertThat(jsonNode.has("interrupted")).isFalse();
    assertThat(jsonNode.has("usageMetadata")).isFalse();

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);

    assertThat(deserializedResponse).isEqualTo(originalResponse);
    assertThat(deserializedResponse.content()).hasValue(sampleContent);
    assertThat(deserializedResponse.groundingMetadata()).isEmpty();
    assertThat(deserializedResponse.partial()).isEmpty();
    assertThat(deserializedResponse.turnComplete()).hasValue(false);
    assertThat(deserializedResponse.errorCode()).isEmpty();
    assertThat(deserializedResponse.errorMessage()).isEmpty();
    assertThat(deserializedResponse.interrupted()).isEmpty();
    assertThat(deserializedResponse.usageMetadata()).isEmpty();
  }

  @Test
  public void testSerializationAndDeserialization_withTranscriptions()
      throws JsonProcessingException {
    Transcription inputTranscription =
        Transcription.builder().text("user said hello").finished(true).build();
    Transcription outputTranscription =
        Transcription.builder().text("model replied hi").finished(false).build();
    LlmResponse originalResponse =
        LlmResponse.builder()
            .content(createSampleContent("hello"))
            .inputTranscription(inputTranscription)
            .outputTranscription(outputTranscription)
            .build();

    String json = originalResponse.toJson();
    JsonNode jsonNode = objectMapper.readTree(json);

    assertThat(jsonNode.has("inputTranscription")).isTrue();
    assertThat(jsonNode.get("inputTranscription").get("text").asText())
        .isEqualTo("user said hello");
    assertThat(jsonNode.get("inputTranscription").get("finished").asBoolean()).isTrue();
    assertThat(jsonNode.has("outputTranscription")).isTrue();
    assertThat(jsonNode.get("outputTranscription").get("text").asText())
        .isEqualTo("model replied hi");
    assertThat(jsonNode.get("outputTranscription").get("finished").asBoolean()).isFalse();

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);

    assertThat(deserializedResponse).isEqualTo(originalResponse);
    assertThat(deserializedResponse.inputTranscription()).hasValue(inputTranscription);
    assertThat(deserializedResponse.outputTranscription()).hasValue(outputTranscription);
  }

  @Test
  public void testTranscriptions_emptyByDefault() {
    LlmResponse response = LlmResponse.builder().content(createSampleContent("hello")).build();

    assertThat(response.inputTranscription()).isEmpty();
    assertThat(response.outputTranscription()).isEmpty();
  }

  @Test
  public void testDeserialization_optionalFieldsNullInJson() throws JsonProcessingException {

    String jsonWithNulls =
        "{"
            + "\"content\": {\"parts\": [{\"text\": \"Test content\"}]},"
            + "\"groundingMetadata\": null,"
            + "\"partial\": null,"
            + "\"turnComplete\": true,"
            + "\"errorCode\": null,"
            + "\"errorMessage\": null,"
            + "\"interrupted\": null,"
            + "\"usageMetadata\": null"
            + "}";

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(jsonWithNulls, LlmResponse.class);

    assertThat(deserializedResponse.content()).isPresent();
    assertThat(deserializedResponse.content().get().parts().get().get(0).text())
        .hasValue("Test content");
    assertThat(deserializedResponse.groundingMetadata()).isEmpty();
    assertThat(deserializedResponse.partial()).isEmpty();
    assertThat(deserializedResponse.turnComplete()).hasValue(true);
    assertThat(deserializedResponse.errorCode()).isEmpty();
    assertThat(deserializedResponse.errorMessage()).isEmpty();
    assertThat(deserializedResponse.interrupted()).isEmpty();
    assertThat(deserializedResponse.usageMetadata()).isEmpty();
  }

  @Test
  public void testDeserialization_someOptionalFieldsMissingSomePresent()
      throws JsonProcessingException {
    Content sampleContent = createSampleContent("Partial data");

    LlmResponse originalResponse =
        LlmResponse.builder()
            .content(sampleContent)
            .turnComplete(true)
            .errorCode(new FinishReason("FATAL_ERROR"))
            .build();

    String json = originalResponse.toJson();
    JsonNode jsonNode = objectMapper.readTree(json);

    assertThat(jsonNode.has("content")).isTrue();
    assertThat(jsonNode.has("partial")).isFalse();
    assertThat(jsonNode.has("turnComplete")).isTrue();
    assertThat(jsonNode.get("turnComplete").asBoolean()).isTrue();
    assertThat(jsonNode.has("errorCode")).isTrue();
    assertThat(jsonNode.get("errorCode").asText()).isEqualTo("FATAL_ERROR");
    assertThat(jsonNode.has("errorMessage")).isFalse();
    assertThat(jsonNode.has("interrupted")).isFalse();
    assertThat(jsonNode.has("usageMetadata")).isFalse();

    LlmResponse deserializedResponse = LlmResponse.fromJsonString(json, LlmResponse.class);
    assertThat(deserializedResponse).isEqualTo(originalResponse);

    assertThat(deserializedResponse.content()).isPresent();
    assertThat(deserializedResponse.content().get().parts().get().get(0).text())
        .hasValue("Partial data");
    assertThat(deserializedResponse.partial()).isEmpty();
    assertThat(deserializedResponse.turnComplete()).hasValue(true);
    assertThat(deserializedResponse.errorCode()).hasValue(new FinishReason("FATAL_ERROR"));
    assertThat(deserializedResponse.errorMessage()).isEmpty();
    assertThat(deserializedResponse.interrupted()).isEmpty();
    assertThat(deserializedResponse.usageMetadata()).isEmpty();
  }
}
