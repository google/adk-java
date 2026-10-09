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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RedactedThinkingBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ClaudeTest {

  private Claude claude;
  private MessageService messageService;
  private Method partToAnthropicMessageBlockMethod;
  private Method functionDeclarationToAnthropicToolMethod;

  @Before
  public void setUp() throws Exception {
    AnthropicClient mockClient = Mockito.mock(AnthropicClient.class);
    messageService = Mockito.mock(MessageService.class);
    when(mockClient.messages()).thenReturn(messageService);
    claude = new Claude("claude-3-opus", mockClient);

    // Access private method for testing the extraction logic
    partToAnthropicMessageBlockMethod =
        Claude.class.getDeclaredMethod("partToAnthropicMessageBlock", Part.class);
    partToAnthropicMessageBlockMethod.setAccessible(true);

    functionDeclarationToAnthropicToolMethod =
        Claude.class.getDeclaredMethod(
            "functionDeclarationToAnthropicTool", FunctionDeclaration.class);
    functionDeclarationToAnthropicToolMethod.setAccessible(true);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> inputSchemaProperties(Tool tool) {
    JsonValue properties = (JsonValue) tool.inputSchema()._properties();
    return properties.convert(new TypeReference<Map<String, Object>>() {});
  }

  private static Message message(ContentBlock... blocks) {
    Message message = mock(Message.class);
    when(message.content()).thenReturn(ImmutableList.copyOf(blocks));
    return message;
  }

  private static ContentBlock thinkingBlock(String thinking, String signature) {
    return ContentBlock.ofThinking(
        ThinkingBlock.builder().thinking(thinking).signature(signature).build());
  }

  private static ContentBlock redactedThinkingBlock(String data) {
    return ContentBlock.ofRedactedThinking(RedactedThinkingBlock.builder().data(data).build());
  }

  private static ContentBlock textBlock(String text) {
    return ContentBlock.ofText(
        TextBlock.builder().text(text).citations(ImmutableList.of()).build());
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  private static LlmRequest request(Content... contents) {
    return LlmRequest.builder().contents(ImmutableList.copyOf(contents)).build();
  }

  private static String signatureOf(Part part) {
    return new String(part.thoughtSignature().get(), UTF_8);
  }

  @Test
  public void testPartToAnthropicMessageBlock_mcpTool_legacyTextOutputKey() throws Exception {
    Map<String, Object> responseData =
        ImmutableMap.of("text_output", ImmutableMap.of("text", "Legacy result text"));
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString())
        .isEqualTo("{\"text_output\":{\"text\":\"Legacy result text\"}}");
  }

  @Test
  public void testPartToAnthropicMessageBlock_jsonFallback() throws Exception {
    Map<String, Object> responseData = ImmutableMap.of("custom_key", "custom_value");
    FunctionResponse funcParam =
        FunctionResponse.builder().name("test_tool").response(responseData).id("call_123").build();
    Part part = Part.builder().functionResponse(funcParam).build();

    ContentBlockParam result =
        (ContentBlockParam) partToAnthropicMessageBlockMethod.invoke(claude, part);

    ToolResultBlockParam toolResult = result.asToolResult();
    assertThat(toolResult.content().get().asString()).contains("\"custom_key\":\"custom_value\"");
  }

  @Test
  public void testClaudeUsageMapping_ShouldFailWhenMappingIsMissing() throws Exception {
    long inputTokens = 10L;
    long outputTokens = 20L;
    Usage mockUsage = mock(Usage.class);
    when(mockUsage.inputTokens()).thenReturn(inputTokens);
    when(mockUsage.outputTokens()).thenReturn(outputTokens);

    Message mockMessage = mock(Message.class);
    when(mockMessage.usage()).thenReturn(mockUsage);
    when(mockMessage.content()).thenReturn(Collections.emptyList());

    Method convertMethod =
        Claude.class.getDeclaredMethod("convertAnthropicResponseToLlmResponse", Message.class);
    convertMethod.setAccessible(true);
    LlmResponse result = (LlmResponse) convertMethod.invoke(claude, mockMessage);
    assertTrue(result.usageMetadata().isPresent());
    assertEquals(inputTokens, (long) result.usageMetadata().get().promptTokenCount().orElse(0));
    assertEquals(
        outputTokens, (long) result.usageMetadata().get().candidatesTokenCount().orElse(0));
  }

  @Test
  public void functionDeclarationToAnthropicTool_usesParameters() throws Exception {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("retrievesItemByItemNumber")
            .description("Retrieves an item")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        ImmutableMap.of("itemNumber", Schema.builder().type("STRING").build()))
                    .required(ImmutableList.of("itemNumber"))
                    .build())
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).containsKey("itemNumber");
    // The genai type "STRING" is lowercased to the JSON Schema "string" for Claude.
    assertThat(((Map<String, Object>) properties.get("itemNumber")).get("type"))
        .isEqualTo("string");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("itemNumber"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_fallsBackToParametersJsonSchema()
      throws Exception {
    // MCP tools populate parametersJsonSchema instead of the structured parameters() field.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "dataset",
                ImmutableMap.of("type", "string", "description", "The dataset id"),
                "project",
                ImmutableMap.of("type", "string")),
            "required",
            ImmutableList.of("dataset"));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("get_dataset_info")
            .description("Gets dataset info")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    // Before the fix these properties were empty, so Claude could not invoke the MCP tool.
    assertThat(properties).containsKey("dataset");
    assertThat(properties).containsKey("project");
    assertThat(((Map<String, Object>) properties.get("dataset")).get("type")).isEqualTo("string");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("dataset"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_noParameters_hasEmptyProperties()
      throws Exception {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder().name("no_args_tool").description("Takes no args").build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).isEmpty();
    assertThat(tool.inputSchema().required()).isEmpty();
  }

  @Test
  public void functionDeclarationToAnthropicTool_unionTypeArray_doesNotThrow() throws Exception {
    // JSON Schema permits a union type array (e.g. ["string", "null"]), which MCP tools can emit.
    // It must not be cast to String, which previously threw ClassCastException.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "nickname", ImmutableMap.of("type", ImmutableList.of("string", "null"))));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("set_nickname")
            .description("Sets a nickname")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).containsKey("nickname");
    // The union type array is preserved rather than crashing on the String cast.
    assertThat(((Map<String, Object>) properties.get("nickname")).get("type"))
        .isEqualTo(ImmutableList.of("string", "null"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_preservesRefsAndDefs() throws Exception {
    // MCP tools may use $ref/$defs. These top-level keywords must survive so Claude does not
    // receive dangling references.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of("pet", ImmutableMap.of("$ref", "#/$defs/Pet")),
            "$defs",
            ImmutableMap.of(
                "Pet",
                ImmutableMap.of(
                    "type",
                    "object",
                    "properties",
                    ImmutableMap.of("name", ImmutableMap.of("type", "string")))));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("register_pet")
            .description("Registers a pet")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(claude, functionDeclaration);

    // The $ref is kept on the property...
    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(((Map<String, Object>) properties.get("pet")).get("$ref")).isEqualTo("#/$defs/Pet");
    // ...and the $defs block survives as a top-level keyword.
    JsonValue defsValue = (JsonValue) tool.inputSchema()._additionalProperties().get("$defs");
    assertThat(defsValue).isNotNull();
    Map<String, Object> defs = defsValue.convert(new TypeReference<Map<String, Object>>() {});
    assertThat(defs).containsKey("Pet");
  }

  @Test
  public void generateContent_thinkingBlocks_becomeThoughtParts() {
    // With display "omitted" (the Claude 5 default) a thinking block has empty text.
    Message reply =
        message(
            thinkingBlock("", "signature"), redactedThinkingBlock("redacted-data"), textBlock("4"));
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);

    LlmResponse response = claude.generateContent(request(userText("2+2?")), false).blockingFirst();

    List<Part> parts = response.content().get().parts().get();
    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(parts.get(0).text()).hasValue("");
    assertThat(signatureOf(parts.get(0))).isEqualTo("signature");
    assertThat(parts.get(1).thought()).hasValue(true);
    assertThat(parts.get(1).text()).isEmpty();
    assertThat(signatureOf(parts.get(1))).isEqualTo("redacted-data");
    assertThat(parts.get(2).thought()).isEmpty();
    assertThat(parts.get(2).text()).hasValue("4");
  }

  @Test
  public void generateContent_toolTurn_sendsThinkingBlocksBackUnchanged() {
    Message toolUseReply =
        message(
            thinkingBlock("", "signature-1"),
            redactedThinkingBlock("redacted-data"),
            textBlock("Let me check."),
            thinkingBlock("Checking the weather.", "signature-2"),
            ContentBlock.ofToolUse(
                ToolUseBlock.builder()
                    .id("toolu_1")
                    .name("getWeather")
                    .input(JsonValue.from(ImmutableMap.of("city", "Seoul")))
                    .caller(DirectCaller.builder().build())
                    .build()));
    Message finalReply = message(textBlock("It is sunny."));
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(toolUseReply, finalReply);
    Content modelTurn =
        claude
            .generateContent(request(userText("Weather in Seoul?")), false)
            .blockingFirst()
            .content()
            .get();
    Content toolResult =
        Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .id("toolu_1")
                            .name("getWeather")
                            .response(ImmutableMap.of("result", "sunny"))
                            .build())
                    .build())
            .build();

    claude
        .generateContent(request(userText("Weather in Seoul?"), modelTurn, toolResult), false)
        .blockingFirst();

    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService, times(2)).create(params.capture());
    List<ContentBlockParam> blocks =
        params.getAllValues().get(1).messages().get(1).content().asBlockParams();
    assertThat(blocks).hasSize(5);
    assertThat(blocks.get(0).asThinking().thinking()).isEmpty();
    assertThat(blocks.get(0).asThinking().signature()).isEqualTo("signature-1");
    assertThat(blocks.get(1).asRedactedThinking().data()).isEqualTo("redacted-data");
    assertThat(blocks.get(2).asText().text()).isEqualTo("Let me check.");
    assertThat(blocks.get(3).asThinking().thinking()).isEqualTo("Checking the weather.");
    assertThat(blocks.get(3).asThinking().signature()).isEqualTo("signature-2");
    assertThat(blocks.get(4).asToolUse().id()).isEqualTo("toolu_1");
  }

  @Test
  public void generateContent_emptyThoughtWithoutSignature_isNotSent() {
    Message reply = message(textBlock("ok"));
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("").thought(true).build(),
                Part.builder().text("").thought(true).thoughtSignature(new byte[0]).build(),
                Part.fromText("Hello."))
            .build();

    claude
        .generateContent(request(userText("Hi"), modelTurn, userText("Bye")), false)
        .blockingFirst();

    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService).create(params.capture());
    // Neither thought has a signature to send back, and Anthropic rejects empty text blocks.
    List<ContentBlockParam> blocks = params.getValue().messages().get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_thoughtWithBinarySignature_isSentAsText() {
    Message reply = message(textBlock("ok"));
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);
    // Another model (e.g. Gemini) stores a binary signature, which Anthropic could not decode.
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .text("Looking up the weather.")
                    .thought(true)
                    .thoughtSignature(new byte[] {(byte) 0xC3, 0x28})
                    .build(),
                Part.fromText("It is sunny."))
            .build();

    claude
        .generateContent(request(userText("Hi"), modelTurn, userText("Thanks")), false)
        .blockingFirst();

    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService).create(params.capture());
    List<ContentBlockParam> blocks = params.getValue().messages().get(1).content().asBlockParams();
    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Looking up the weather.");
    assertThat(blocks.get(1).asText().text()).isEqualTo("It is sunny.");
  }
}
