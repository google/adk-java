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

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.backends.Backend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlock;
import com.anthropic.models.messages.StopReason;
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
import com.google.common.net.InetAddresses;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
    return Message.builder()
        .id("msg_1")
        .container(Optional.empty())
        .content(ImmutableList.copyOf(blocks))
        .model("claude-3-opus")
        .stopReason(StopReason.END_TURN)
        .stopSequence(Optional.empty())
        .usage(
            Usage.builder()
                .inputTokens(10)
                .outputTokens(5)
                .cacheCreation(Optional.empty())
                .cacheCreationInputTokens(Optional.empty())
                .cacheReadInputTokens(Optional.empty())
                .inferenceGeo(Optional.empty())
                .serverToolUse(Optional.empty())
                .serviceTier(Optional.empty())
                .build())
        .build();
  }

  /** Sends {@code contents} in one request and returns the messages Claude received. */
  private List<MessageParam> sentMessages(Content... contents) {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(message(textBlock("ok")));
    claude.generateContent(request(contents), false).blockingFirst();
    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService).create(params.capture());
    return params.getValue().messages();
  }

  /** A signature that is not UTF-8, like Gemini's, so it cannot be one Claude issued. */
  private static byte[] nonUtf8Signature() {
    return new byte[] {(byte) 0xC3, 0x28};
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

  private static FunctionCall weatherCall(String id, String city) {
    return FunctionCall.builder()
        .id(id)
        .name("getWeather")
        .args(ImmutableMap.of("city", city))
        .build();
  }

  private static FunctionResponse weatherResult(String id, String weather) {
    return FunctionResponse.builder()
        .id(id)
        .name("getWeather")
        .response(ImmutableMap.of("result", weather))
        .build();
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
  public void generateContent_maxTokensStopReason_mapsToMaxTokensFinishReason() {
    Message truncated =
        message(textBlock("cut off")).toBuilder().stopReason(StopReason.MAX_TOKENS).build();
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(truncated);

    LlmResponse response = claude.generateContent(request(userText("hi")), false).blockingFirst();

    assertThat(response.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.MAX_TOKENS);
  }

  @Test
  public void generateContent_contextWindowExceededStopReason_mapsToMaxTokensFinishReason() {
    Message truncated =
        message(textBlock("cut off")).toBuilder()
            .stopReason(StopReason.of("model_context_window_exceeded"))
            .build();
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(truncated);

    LlmResponse response = claude.generateContent(request(userText("hi")), false).blockingFirst();

    assertThat(response.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.MAX_TOKENS);
  }

  @Test
  public void generateContent_endTurnStopReason_mapsToStopFinishReason() {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(message(textBlock("done")));

    LlmResponse response = claude.generateContent(request(userText("hi")), false).blockingFirst();

    assertThat(response.finishReason().map(FinishReason::knownEnum))
        .hasValue(FinishReason.Known.STOP);
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
  public void generateContent_thinkingBlockWithEmptySignature_isNotSigned() {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(message(thinkingBlock("Thinking.", ""), textBlock("4")));

    LlmResponse response = claude.generateContent(request(userText("2+2?")), false).blockingFirst();

    Part thought = response.content().get().parts().get().get(0);
    assertThat(thought.thought()).hasValue(true);
    assertThat(thought.text()).hasValue("Thinking.");
    assertThat(thought.thoughtSignature()).isEmpty();
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
    Content toolResult =
        Content.builder()
            .role("user")
            .parts(Part.builder().functionResponse(weatherResult("toolu_1", "sunny")).build())
            .build();
    Content modelTurn =
        claude
            .generateContent(request(userText("Weather in Seoul?")), false)
            .blockingFirst()
            .content()
            .get();

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
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("").thought(true).build(),
                Part.builder().text("").thought(true).thoughtSignature(new byte[0]).build(),
                Part.fromText("Hello."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    // Neither thought has a signature to send back, and Anthropic rejects empty text blocks.
    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_thoughtWithBinarySignature_isSentAsText() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .text("Looking up the weather.")
                    .thought(true)
                    .thoughtSignature(nonUtf8Signature())
                    .build(),
                Part.fromText("It is sunny."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Thanks"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Looking up the weather.");
    assertThat(blocks.get(1).asText().text()).isEqualTo("It is sunny.");
  }

  @Test
  public void generateContent_thoughtWithoutTextOrUsableSignature_isNotSent() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().thought(true).build(),
                Part.builder().thought(true).thoughtSignature(nonUtf8Signature()).build(),
                Part.fromText("Hello."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_thoughtFlaggedCallsAndResponses_areSentAsToolUseAndToolResult() {
    byte[] signature = "signature".getBytes(UTF_8);
    // A call or response flagged as a thought is still sent, whatever text or signature it has.
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .thought(true)
                    .thoughtSignature(signature)
                    .functionCall(weatherCall("toolu_1", "Seoul"))
                    .build(),
                Part.builder()
                    .text("")
                    .thought(true)
                    .functionCall(weatherCall("toolu_2", "Busan"))
                    .build(),
                Part.builder()
                    .text("Checking Jeju.")
                    .thought(true)
                    .thoughtSignature(signature)
                    .functionCall(weatherCall("toolu_3", "Jeju"))
                    .build())
            .build();
    Content toolResults =
        Content.builder()
            .role("user")
            .parts(
                Part.builder()
                    .thought(true)
                    .thoughtSignature(signature)
                    .functionResponse(weatherResult("toolu_1", "sunny"))
                    .build(),
                Part.builder()
                    .text("")
                    .thought(true)
                    .functionResponse(weatherResult("toolu_2", "rainy"))
                    .build(),
                Part.builder()
                    .text("Got Jeju.")
                    .thought(true)
                    .thoughtSignature(signature)
                    .functionResponse(weatherResult("toolu_3", "windy"))
                    .build())
            .build();

    List<MessageParam> messages = sentMessages(userText("Weather?"), modelTurn, toolResults);

    List<ContentBlockParam> calls = messages.get(1).content().asBlockParams();
    assertThat(calls).hasSize(3);
    assertThat(calls.get(0).asToolUse().id()).isEqualTo("toolu_1");
    assertThat(calls.get(0).asToolUse().name()).isEqualTo("getWeather");
    assertThat(calls.get(0).asToolUse()._input())
        .isEqualTo(JsonValue.from(ImmutableMap.of("city", "Seoul")));
    assertThat(calls.get(1).asToolUse().id()).isEqualTo("toolu_2");
    assertThat(calls.get(1).asToolUse().name()).isEqualTo("getWeather");
    assertThat(calls.get(1).asToolUse()._input())
        .isEqualTo(JsonValue.from(ImmutableMap.of("city", "Busan")));
    assertThat(calls.get(2).asToolUse().id()).isEqualTo("toolu_3");
    assertThat(calls.get(2).asToolUse().name()).isEqualTo("getWeather");
    assertThat(calls.get(2).asToolUse()._input())
        .isEqualTo(JsonValue.from(ImmutableMap.of("city", "Jeju")));
    List<ContentBlockParam> results = messages.get(2).content().asBlockParams();
    assertThat(results).hasSize(3);
    assertThat(results.get(0).asToolResult().toolUseId()).isEqualTo("toolu_1");
    assertThat(results.get(0).asToolResult().content().get().asString()).isEqualTo("sunny");
    assertThat(results.get(1).asToolResult().toolUseId()).isEqualTo("toolu_2");
    assertThat(results.get(1).asToolResult().content().get().asString()).isEqualTo("rainy");
    assertThat(results.get(2).asToolResult().toolUseId()).isEqualTo("toolu_3");
    assertThat(results.get(2).asToolResult().content().get().asString()).isEqualTo("windy");
  }

  @Test
  public void generateContent_contentFreeSignature_isNotSent() {
    // Another model (e.g. Gemini) leaves signatures on parts that carry nothing else.
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("").thoughtSignature(nonUtf8Signature()).build(),
                Part.builder().thoughtSignature("signature".getBytes(UTF_8)).build(),
                // Metadata is not content, so this part is dropped too.
                Part.builder()
                    .thoughtSignature("signature-2".getBytes(UTF_8))
                    .partMetadata(ImmutableMap.of("source", "test"))
                    .build(),
                Part.fromText("Hello."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_signatureBesideContent_isKept() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(weatherCall("toolu_1", "Seoul"))
                    .thoughtSignature(nonUtf8Signature())
                    .build(),
                Part.builder()
                    .text("Checking the weather.")
                    .thought(true)
                    .thoughtSignature("signature-1".getBytes(UTF_8))
                    .build(),
                Part.builder().thought(true).thoughtSignature("redacted-1".getBytes(UTF_8)).build())
            .build();
    Content toolResult =
        Content.builder()
            .role("user")
            .parts(Part.builder().functionResponse(weatherResult("toolu_1", "sunny")).build())
            .build();

    List<MessageParam> messages = sentMessages(userText("Weather?"), modelTurn, toolResult);

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(3);
    assertThat(blocks.get(0).asToolUse().id()).isEqualTo("toolu_1");
    assertThat(blocks.get(1).asThinking().signature()).isEqualTo("signature-1");
    assertThat(blocks.get(2).asRedactedThinking().data()).isEqualTo("redacted-1");
  }

  @Test
  public void generateContent_turnOfOnlyDroppedThoughts_isNotSent() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().thought(true).build(),
                Part.builder().thought(true).thoughtSignature(nonUtf8Signature()).build())
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).content().asBlockParams().get(0).asText().text()).isEqualTo("Hi");
    assertThat(messages.get(1).content().asBlockParams().get(0).asText().text()).isEqualTo("Bye");
  }

  @Test
  public void generateContent_turnOfOnlyContentFreeSignature_isNotSent() {
    Part contentFreeSignature = Part.builder().thoughtSignature(nonUtf8Signature()).build();
    Content modelTurn = Content.builder().role("model").parts(contentFreeSignature).build();
    // Only a user turn of empty text keeps a placeholder; this one is left out.
    Content userTurn = Content.builder().role("user").parts(contentFreeSignature).build();

    List<MessageParam> messages =
        sentMessages(userText("Hi"), modelTurn, userText("Bye"), userTurn);

    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).content().asBlockParams().get(0).asText().text()).isEqualTo("Hi");
    assertThat(messages.get(1).content().asBlockParams().get(0).asText().text()).isEqualTo("Bye");
  }

  @Test
  public void generateContent_emptyTextPart_isNotSent() {
    // A session store that keeps only text (e.g. Firestore) restores a Claude thought this way.
    Content modelTurn =
        Content.builder().role("model").parts(Part.fromText(""), Part.fromText("Hello.")).build();
    Content emptyModelTurn = Content.builder().role("model").parts(Part.fromText("")).build();

    List<MessageParam> messages =
        sentMessages(
            userText("Hi"), modelTurn, userText("Weather?"), emptyModelTurn, userText("Bye"));

    assertThat(messages).hasSize(4);
    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
    assertThat(messages.get(2).content().asBlockParams().get(0).asText().text())
        .isEqualTo("Weather?");
    assertThat(messages.get(3).content().asBlockParams().get(0).asText().text()).isEqualTo("Bye");
  }

  @Test
  public void generateContent_userTurnOfOnlyEmptyText_isSentAsPlaceholder() {
    Content userTurn =
        Content.builder().role("user").parts(Part.fromText(""), Part.fromText("Run it.")).build();
    Content modelTurn = Content.builder().role("model").parts(Part.fromText("Running.")).build();

    // E.g. an empty user message; dropping it would end the request on an assistant turn.
    List<MessageParam> messages = sentMessages(userTurn, modelTurn, userText(""));

    assertThat(messages).hasSize(3);
    // Beside other content, an empty part is just dropped.
    List<ContentBlockParam> firstBlocks = messages.get(0).content().asBlockParams();
    assertThat(firstBlocks).hasSize(1);
    assertThat(firstBlocks.get(0).asText().text()).isEqualTo("Run it.");
    assertThat(messages.get(2).role()).isEqualTo(MessageParam.Role.USER);
    List<ContentBlockParam> blocks = messages.get(2).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo(".");
  }

  @Test
  public void generateContent_extendsTheSdkUserAgentWithAdkTrackingHeaders() throws Exception {
    Headers headers = headersSentThrough(AnthropicOkHttpClient.builder());

    String userAgent = getOnlyElement(headers.get("user-agent"));
    assertThat(userAgent).startsWith("AnthropicClientImpl/Java ");
    assertThat(userAgent).endsWith(" " + TrackingHeaders.VALUE);
    assertThat(headers.get("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE);
  }

  @Test
  public void generateContent_extendsTheCallerUserAgentWithAdkTrackingHeaders() throws Exception {
    Headers headers =
        headersSentThrough(AnthropicOkHttpClient.builder().putHeader("User-Agent", "my-app/1.0"));

    assertThat(headers.get("user-agent")).containsExactly("my-app/1.0 " + TrackingHeaders.VALUE);
    assertThat(headers.get("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE);
  }

  @Test
  public void constructor_nullClient_doesNotThrow() {
    assertThat(new Claude("claude-test", (AnthropicClient) null).model()).isEqualTo("claude-test");
  }

  private static final String MESSAGE_JSON =
      "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
          + "\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"stop_reason\":\"end_turn\","
          + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";

  /**
   * Sends one request through Claude using a client built from {@code clientBuilder} for a local
   * server, and returns the headers the server received.
   */
  private static Headers headersSentThrough(AnthropicOkHttpClient.Builder clientBuilder)
      throws Exception {
    AtomicReference<Headers> requestHeaders = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestHeaders.set(exchange.getRequestHeaders());
          byte[] body = MESSAGE_JSON.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      String baseUrl =
          "http://"
              + InetAddresses.toUriString(server.getAddress().getAddress())
              + ":"
              + server.getAddress().getPort();
      Backend backend =
          new Backend() {
            @Override
            public String baseUrl() {
              return baseUrl;
            }

            @Override
            public void close() {}
          };
      Claude model = new Claude("claude-test", clientBuilder.backend(backend).build());

      LlmResponse response =
          model.generateContent(request(userText("Ping")), /* stream= */ false).blockingFirst();

      assertThat(response.content().get().parts().get().get(0).text()).hasValue("pong");
      return requestHeaders.get();
    } finally {
      server.stop(0);
    }
  }
}
