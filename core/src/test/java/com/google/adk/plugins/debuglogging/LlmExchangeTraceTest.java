/*
 * Copyright 2026 Google LLC
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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Covers both halves of the model exchange, and the system-instruction off-switch. */
@RunWith(JUnit4.class)
public class LlmExchangeTraceTest {

  private static final String MODEL = "gemini-2.0-flash";
  private static final String SECRET_INSTRUCTION = "You are a helpful assistant with a secret.";
  private static final String TOOL_DESCRIPTION = "Looks an order up by its id.";

  private final ObjectMapper mapper = DebugYamlWriter.configure(new ObjectMapper());

  private String serializeRequest(LlmRequest request, boolean includeSystemInstruction)
      throws Exception {
    return mapper.writeValueAsString(LlmRequestTrace.from(request, includeSystemInstruction));
  }

  private static Content userContent(String text) {
    return Content.builder()
        .role("user")
        .parts(ImmutableList.of(Part.builder().text(text).build()))
        .build();
  }

  private static LlmRequest requestWith(GenerateContentConfig config) {
    return LlmRequest.builder()
        .model(MODEL)
        .contents(ImmutableList.of(userContent("hello")))
        .config(config)
        .build();
  }

  @Test
  public void request_carriesModelAndContentCount() throws Exception {
    LlmRequest request =
        LlmRequest.builder()
            .model(MODEL)
            .contents(ImmutableList.of(userContent("a"), userContent("b")))
            .build();

    String json = serializeRequest(request, true);

    assertThat(json).contains("\"model\":\"gemini-2.0-flash\"");
    assertThat(json).contains("\"content_count\":2");
  }

  @Test
  public void request_includeSystemInstruction_recordsItAsStructuredContent() throws Exception {
    GenerateContentConfig config =
        GenerateContentConfig.builder().systemInstruction(userContent(SECRET_INSTRUCTION)).build();

    String json = serializeRequest(requestWith(config), true);

    assertThat(json).contains("\"system_instruction\":");
    assertThat(json).contains(SECRET_INSTRUCTION);
  }

  /** The off-switch exists so a trace can be shared without the prompt in it. */
  @Test
  public void request_excludeSystemInstruction_recordsOnlyThatOneWasPresent() throws Exception {
    GenerateContentConfig config =
        GenerateContentConfig.builder().systemInstruction(userContent(SECRET_INSTRUCTION)).build();

    String json = serializeRequest(requestWith(config), false);

    assertThat(json).contains("\"has_system_instruction\":true");
    assertThat(json).doesNotContain(SECRET_INSTRUCTION);
    assertThat(json).doesNotContain("\"system_instruction\":");
  }

  @Test
  public void request_generationSettings_areRecorded() throws Exception {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.25f).maxOutputTokens(512).build();

    String json = serializeRequest(requestWith(config), true);

    assertThat(json).contains("\"temperature\":0.25");
    assertThat(json).contains("\"max_output_tokens\":512");
  }

  @Test
  public void request_emptyConfig_omitsTheBlockEntirely() throws Exception {
    LlmRequest request =
        LlmRequest.builder().model(MODEL).contents(ImmutableList.of(userContent("hi"))).build();

    assertThat(serializeRequest(request, true)).doesNotContain("\"config\"");
  }

  @Test
  public void request_responseMimeType_isRecorded() throws Exception {
    GenerateContentConfig config =
        GenerateContentConfig.builder().responseMimeType("application/json").build();

    String json = serializeRequest(requestWith(config), true);

    assertThat(json).contains("\"response_mime_type\":\"application/json\"");
  }

  /**
   * A response schema can be arbitrarily large and says nothing about the turn being traced, so
   * only its presence is recorded, as upstream does.
   */
  @Test
  public void request_responseSchema_isReducedToABoolean() throws Exception {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseSchema(Schema.builder().type(Type.Known.OBJECT).build())
            .build();

    String json = serializeRequest(requestWith(config), true);

    assertThat(json).contains("\"has_response_schema\":true");
    assertThat(json).doesNotContain("\"response_schema\":");
  }

  /**
   * Tool declarations repeat on every turn and dwarf everything else in the document, so upstream
   * records names only. The record has no field for a declaration, so this is structural — but the
   * key name and the {@code NON_EMPTY} inclusion still need pinning.
   */
  @Test
  public void request_tools_recordTheirNamesOnly() throws Exception {
    LlmRequest request =
        LlmRequest.builder()
            .model(MODEL)
            .contents(ImmutableList.of(userContent("hi")))
            .tools(ImmutableMap.of("lookup_order", new NamedTool("lookup_order")))
            .build();

    String json = serializeRequest(request, true);

    assertThat(json).contains("\"tools\":[\"lookup_order\"]");
    assertThat(json).doesNotContain(TOOL_DESCRIPTION);
  }

  @Test
  public void request_withNoTools_omitsTheKey() throws Exception {
    LlmRequest request =
        LlmRequest.builder().model(MODEL).contents(ImmutableList.of(userContent("hi"))).build();

    assertThat(serializeRequest(request, true)).doesNotContain("\"tools\"");
  }

  @Test
  public void response_carriesContentAndFinishReason() throws Exception {
    LlmResponse response =
        LlmResponse.builder()
            .content(userContent("answer"))
            .finishReason(new FinishReason(FinishReason.Known.STOP))
            .build();

    String json = mapper.writeValueAsString(LlmResponseTrace.from(response));

    assertThat(json).contains("\"parts\":[{\"text\":\"answer\"}]");
    assertThat(json).contains("\"finish_reason\":\"STOP\"");
  }

  @Test
  public void response_error_isRecorded() throws Exception {
    LlmResponse response = LlmResponse.builder().errorMessage("quota exceeded").build();

    String json = mapper.writeValueAsString(LlmResponseTrace.from(response));

    assertThat(json).contains("\"error_message\":\"quota exceeded\"");
  }

  @Test
  public void response_emptyResponse_omitsEveryAbsentField() throws Exception {
    String json = mapper.writeValueAsString(LlmResponseTrace.from(LlmResponse.builder().build()));

    assertThat(json).isEqualTo("{}");
  }

  /**
   * A response's token counts carry one more field than an event's. {@link UsageTraceTest} pins the
   * difference at the record; this pins that the response side asks for the four-count factory.
   */
  @Test
  public void response_usageMetadata_carriesTheCachedContentCount() throws Exception {
    LlmResponse response =
        LlmResponse.builder()
            .usageMetadata(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(120)
                    .totalTokenCount(165)
                    .cachedContentTokenCount(80)
                    .build())
            .build();

    assertThat(mapper.writeValueAsString(LlmResponseTrace.from(response)))
        .contains(
            "\"usage_metadata\":{\"prompt_token_count\":120,\"total_token_count\":165,"
                + "\"cached_content_token_count\":80}");
  }

  /** Grounding payloads are large and add nothing readable, so only their presence is kept. */
  @Test
  public void response_groundingMetadata_isReducedToABoolean() throws Exception {
    LlmResponse response =
        LlmResponse.builder().groundingMetadata(GroundingMetadata.builder().build()).build();

    assertThat(mapper.writeValueAsString(LlmResponseTrace.from(response)))
        .isEqualTo("{\"has_grounding_metadata\":true}");
  }

  /** The streaming flags and the resolved model version, which a partial response is read for. */
  @Test
  public void response_streamingFlagsAndModelVersion_areRecorded() throws Exception {
    LlmResponse response =
        LlmResponse.builder()
            .partial(true)
            .turnComplete(false)
            .errorCode(new FinishReason(FinishReason.Known.SAFETY))
            .modelVersion("gemini-2.0-flash-001")
            .build();

    String json = mapper.writeValueAsString(LlmResponseTrace.from(response));

    assertThat(json).contains("\"partial\":true");
    assertThat(json).contains("\"turn_complete\":false");
    assertThat(json).contains("\"error_code\":\"SAFETY\"");
    assertThat(json).contains("\"model_version\":\"gemini-2.0-flash-001\"");
  }

  /**
   * The trace only ever reads {@link BaseTool#name()}, so a named stub is enough — and it keeps
   * this test free of a mocking framework the rest of the file does not use.
   */
  private static final class NamedTool extends BaseTool {
    NamedTool(String name) {
      super(name, TOOL_DESCRIPTION);
    }
  }
}
