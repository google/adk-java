/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.models.chat;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolConfig;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ChatCompletionsRequestTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = JsonBaseModel.getMapper();
  }

  @Test
  public void testSerializeChatCompletionRequest_standard() throws Exception {
    ChatCompletionsRequest.Message message = new ChatCompletionsRequest.Message();
    message.role = "user";
    message.content = new ChatCompletionsRequest.MessageContent("Hello");

    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of(message);

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"model\":\"gemini-3-flash-preview\"");
    assertThat(json).contains("\"role\":\"user\"");
    assertThat(json).contains("\"content\":\"Hello\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withExtraBody() throws Exception {
    ChatCompletionsRequest.Message message = new ChatCompletionsRequest.Message();
    message.role = "user";
    message.content = new ChatCompletionsRequest.MessageContent("Explain to me how AI works");

    ImmutableMap<String, Object> extraBody =
        ImmutableMap.of(
            "google",
            ImmutableMap.of(
                "thinking_config",
                ImmutableMap.of("thinking_level", "low", "include_thoughts", true)));

    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of(message);
    request.extraBody = extraBody;

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"extra_body\":{");
    assertThat(json).contains("\"thinking_level\":\"low\"");
    assertThat(json).contains("\"include_thoughts\":true");
  }

  @Test
  public void testSerializeChatCompletionRequest_withToolCallsAndExtraContent() throws Exception {
    ChatCompletionsRequest.Message userMessage = new ChatCompletionsRequest.Message();
    userMessage.role = "user";
    userMessage.content = new ChatCompletionsRequest.MessageContent("Check flight status");

    ChatCompletionsRequest.Message modelMessage = new ChatCompletionsRequest.Message();
    modelMessage.role = "model";

    ChatCompletionsCommon.ToolCall toolCall = new ChatCompletionsCommon.ToolCall();
    toolCall.id = "function-call-1";
    toolCall.type = "function";

    ChatCompletionsCommon.Function function = new ChatCompletionsCommon.Function();
    function.name = "check_flight";
    function.arguments = "{\"flight\":\"AA100\"}";
    toolCall.function = function;

    ImmutableMap<String, Object> extraContent =
        ImmutableMap.of("google", ImmutableMap.of("thought_signature", "<SIGNATURE_A>"));

    toolCall.extraContent = extraContent;

    modelMessage.toolCalls = ImmutableList.of(toolCall);

    ChatCompletionsRequest.Message toolMessage = new ChatCompletionsRequest.Message();
    toolMessage.role = "tool";
    toolMessage.name = "check_flight";
    toolMessage.toolCallId = "function-call-1";
    toolMessage.content = new ChatCompletionsRequest.MessageContent("{\"status\":\"delayed\"}");

    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of(userMessage, modelMessage, toolMessage);

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"role\":\"user\"");
    assertThat(json).contains("\"role\":\"model\"");
    assertThat(json).contains("\"role\":\"tool\"");
    assertThat(json).contains("\"extra_content\":{");
    assertThat(json).contains("\"thought_signature\":\"<SIGNATURE_A>\"");
    assertThat(json).contains("\"tool_call_id\":\"function-call-1\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_comprehensive() throws Exception {
    ChatCompletionsRequest.Message devMsg = new ChatCompletionsRequest.Message();
    devMsg.role = "developer";
    devMsg.content = new ChatCompletionsRequest.MessageContent("System instruction");
    devMsg.name = "system-bot";

    ChatCompletionsRequest.ResponseFormatJsonSchema format =
        new ChatCompletionsRequest.ResponseFormatJsonSchema();
    format.jsonSchema = new ChatCompletionsRequest.ResponseFormatJsonSchema.JsonSchema();
    format.jsonSchema.name = "MySchema";
    format.jsonSchema.strict = true;

    ChatCompletionsRequest.NamedToolChoice choice = new ChatCompletionsRequest.NamedToolChoice();
    choice.function = new ChatCompletionsRequest.NamedToolChoice.FunctionName();
    choice.function.name = "my_function";

    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of(devMsg);
    request.responseFormat = format;
    request.toolChoice = choice;

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"role\":\"developer\"");
    assertThat(json).contains("\"name\":\"system-bot\"");
    assertThat(json).contains("\"content\":\"System instruction\"");

    assertThat(json).contains("\"response_format\":{");
    assertThat(json).contains("\"type\":\"json_schema\"");
    assertThat(json).contains("\"name\":\"MySchema\"");
    assertThat(json).contains("\"strict\":true");

    assertThat(json).contains("\"tool_choice\":{");
    assertThat(json).contains("\"type\":\"function\"");
    assertThat(json).contains("\"name\":\"my_function\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withToolChoiceMode() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of();
    request.toolChoice = new ChatCompletionsRequest.ToolChoiceMode("none");

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"tool_choice\":\"none\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withStopAndVoice() throws Exception {
    ChatCompletionsRequest.StopCondition stop = new ChatCompletionsRequest.StopCondition("STOP");

    ChatCompletionsRequest.AudioParam audio = new ChatCompletionsRequest.AudioParam();
    audio.voice = new ChatCompletionsRequest.VoiceConfig("alloy");

    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of();
    request.stop = stop;
    request.audio = audio;

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"stop\":\"STOP\"");
    assertThat(json).contains("\"voice\":\"alloy\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withStopList() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";
    request.messages = ImmutableList.of();
    request.stop = new ChatCompletionsRequest.StopCondition(ImmutableList.of("STOP1", "STOP2"));

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"stop\":[\"STOP1\",\"STOP2\"]");
  }

  @Test
  public void testFromLlmRequest_basic() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(ImmutableList.of(Part.fromText("Hello")))
                        .build()))
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.model).isEqualTo("gemini-1.5-pro");
    assertThat(request.stream).isFalse();
    assertThat(request.messages).hasSize(1);
    assertThat(request.messages.get(0).role).isEqualTo("user");
    assertThat(request.messages.get(0).content.getValue()).isEqualTo("Hello");
  }

  @Test
  public void testFromLlmRequest_withSystemInstruction() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gpt-4")
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(ImmutableList.of(Part.fromText("Be helpful")))
                            .build())
                    .temperature(0.7f)
                    .topP(0.9f)
                    .maxOutputTokens(100)
                    .stopSequences(ImmutableList.of("END"))
                    .candidateCount(2)
                    .presencePenalty(0.5f)
                    .frequencyPenalty(0.3f)
                    .seed(12345)
                    .tools(
                        ImmutableList.of(
                            Tool.builder()
                                .functionDeclarations(
                                    ImmutableList.of(
                                        FunctionDeclaration.builder()
                                            .name("get_weather")
                                            .description("Get current weather")
                                            .build()))
                                .build()))
                    .toolConfig(
                        ToolConfig.builder()
                            .functionCallingConfig(
                                FunctionCallingConfig.builder()
                                    .mode(
                                        com.google.genai.types.FunctionCallingConfigMode.Known.ANY)
                                    .build())
                            .build())
                    .build())
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(ImmutableList.of(Part.fromText("Hello")))
                        .build()))
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.messages).hasSize(2);
    assertThat(request.messages.get(0).role).isEqualTo("system");
    assertThat(request.messages.get(0).content.getValue()).isEqualTo("Be helpful");
    assertThat(request.temperature).isWithin(0.001).of(0.7);
    assertThat(request.topP).isWithin(0.001).of(0.9);
    assertThat(request.maxCompletionTokens).isEqualTo(100);
    assertThat((List<?>) request.stop.getValue()).containsExactly("END");
    assertThat(request.n).isEqualTo(2);
    assertThat(request.presencePenalty).isWithin(0.001).of(0.5);
    assertThat(request.frequencyPenalty).isWithin(0.001).of(0.3);
    assertThat(request.seed).isEqualTo(12345L);
    assertThat(request.tools).hasSize(1);
    assertThat(request.tools.get(0).function.name).isEqualTo("get_weather");
    assertThat(request.tools.get(0).function.description).isEqualTo("Get current weather");
    assertThat(((ChatCompletionsRequest.ToolChoiceMode) request.toolChoice).getMode())
        .isEqualTo("required");
  }

  @Test
  public void testFromLlmRequest_withInlineData() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(
                            ImmutableList.of(
                                Part.builder()
                                    .inlineData(
                                        Blob.builder()
                                            .mimeType("image/jpeg")
                                            .data("base64data".getBytes(UTF_8))
                                            .build())
                                    .build()))
                        .build()))
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.messages).hasSize(1);
    ChatCompletionsRequest.Message msg = request.messages.get(0);

    @SuppressWarnings(
        "unchecked") // Safe in unit tests and this is the expected type from msg.content
    List<ChatCompletionsRequest.ContentPart> parts =
        (List<ChatCompletionsRequest.ContentPart>) msg.content.getValue();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).type).isEqualTo("image_url");
    assertThat(parts.get(0).imageUrl.url).contains("base64,");
  }

  @Test
  public void testFromLlmRequest_withFileData() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("user")
                        .parts(
                            ImmutableList.of(
                                Part.builder()
                                    .fileData(
                                        FileData.builder()
                                            .fileUri("gs://bucket/file.jpg")
                                            .mimeType("image/jpeg")
                                            .build())
                                    .build()))
                        .build()))
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.messages).hasSize(1);
    ChatCompletionsRequest.Message msg = request.messages.get(0);

    @SuppressWarnings(
        "unchecked") // Safe in unit tests and this is the expected type from msg.content
    List<ChatCompletionsRequest.ContentPart> parts =
        (List<ChatCompletionsRequest.ContentPart>) msg.content.getValue();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).type).isEqualTo("image_url");
    assertThat(parts.get(0).imageUrl.url).isEqualTo("gs://bucket/file.jpg");
  }

  @Test
  public void testFromLlmRequest_withFunctionCall() throws Exception {
    ImmutableMap<String, Object> args = ImmutableMap.of("location", "Paris");

    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("model")
                        .parts(
                            ImmutableList.of(
                                Part.builder()
                                    .functionCall(
                                        FunctionCall.builder()
                                            .id("call_123")
                                            .name("get_weather")
                                            .args(args)
                                            .build())
                                    .build()))
                        .build()))
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.messages).hasSize(1);
    ChatCompletionsRequest.Message msg = request.messages.get(0);
    assertThat(msg.role).isEqualTo("assistant");
    assertThat(msg.toolCalls).hasSize(1);
    assertThat(msg.toolCalls.get(0).id).isEqualTo("call_123");
    assertThat(msg.toolCalls.get(0).type).isEqualTo("function");
    assertThat(msg.toolCalls.get(0).function.name).isEqualTo("get_weather");
    assertThat(msg.toolCalls.get(0).function.arguments).isEqualTo("{\"location\":\"Paris\"}");
  }

  @Test
  public void testFromLlmRequest_withStreamOptions() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder().model("gemini-1.5-pro").contents(ImmutableList.of()).build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, true);

    assertThat(request.stream).isTrue();
    assertThat(request.streamOptions).isNotNull();
    assertThat(request.streamOptions.includeUsage).isTrue();
  }

  private static class BadMap extends java.util.AbstractMap<String, Object> {
    @Override
    public Set<java.util.Map.Entry<String, Object>> entrySet() {
      throw new RuntimeException("Serialization failed!");
    }
  }

  @Test
  public void testFromLlmRequest_withFunctionResponse() throws Exception {
    ImmutableMap<String, Object> respData = ImmutableMap.of("result", "ok");

    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .contents(
                ImmutableList.of(
                    Content.builder()
                        .role("tool")
                        .parts(
                            ImmutableList.<Part>of(
                                Part.builder()
                                    .functionResponse(
                                        FunctionResponse.builder()
                                            .id("call_999")
                                            .response(respData)
                                            .build())
                                    .build(),
                                Part.builder()
                                    .functionResponse(FunctionResponse.builder().build())
                                    .build(),
                                Part.builder()
                                    .functionResponse(
                                        FunctionResponse.builder()
                                            .id("call_faulty")
                                            .response(new BadMap())
                                            .build())
                                    .build()))
                        .build()))
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.messages).hasSize(3);
    assertThat(request.messages.get(0).role).isEqualTo("tool");
    assertThat(request.messages.get(0).toolCallId).isEqualTo("call_999");
    assertThat(request.messages.get(0).content.getValue()).isEqualTo("{\"result\":\"ok\"}");

    assertThat(request.messages.get(1).role).isEqualTo("tool");
    assertThat(request.messages.get(1).toolCallId).isEmpty();
    assertThat(request.messages.get(1).content).isNull();

    assertThat(request.messages.get(2).role).isEqualTo("tool");
    assertThat(request.messages.get(2).toolCallId).isEqualTo("call_faulty");
    assertThat(request.messages.get(2).content).isNull();
  }

  @Test
  public void testFromLlmRequest_withConfigSchemaAndLogprobs() throws Exception {
    ImmutableMap<String, Object> schemaDef = ImmutableMap.of("type", "object");

    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .config(
                GenerateContentConfig.builder()
                    .responseJsonSchema(schemaDef)
                    .responseLogprobs(true)
                    .logprobs(5)
                    .build())
            .contents(ImmutableList.of())
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.responseFormat)
        .isInstanceOf(ChatCompletionsRequest.ResponseFormatJsonSchema.class);
    ChatCompletionsRequest.ResponseFormatJsonSchema format =
        (ChatCompletionsRequest.ResponseFormatJsonSchema) request.responseFormat;
    assertThat(format.jsonSchema.name).isEqualTo("response_schema");
    assertThat(format.jsonSchema.strict).isTrue();
    assertThat(format.jsonSchema.schema).isEqualTo(schemaDef);
    assertThat(request.logprobs).isTrue();
    assertThat(request.topLogprobs).isEqualTo(5);
  }

  @Test
  public void testFromLlmRequest_withConfigResponseMimeTypeJson() throws Exception {
    LlmRequest llmRequest =
        LlmRequest.builder()
            .model("gemini-1.5-pro")
            .config(GenerateContentConfig.builder().responseMimeType("application/json").build())
            .contents(ImmutableList.of())
            .build();

    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    assertThat(request.responseFormat)
        .isInstanceOf(ChatCompletionsRequest.ResponseFormatJsonObject.class);
  }
}
