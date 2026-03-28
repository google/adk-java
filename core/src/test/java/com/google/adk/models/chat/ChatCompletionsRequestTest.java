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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ChatCompletionsRequestTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testSerializeChatCompletionRequest_standard() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

    ChatCompletionsRequest.Message message = new ChatCompletionsRequest.Message();
    message.role = "user";
    message.content = new ChatCompletionsRequest.MessageContent("Hello");
    request.messages = ImmutableList.of(message);

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"model\":\"gemini-3-flash-preview\"");
    assertThat(json).contains("\"role\":\"user\"");
    assertThat(json).contains("\"content\":\"Hello\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withExtraBody() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

    ChatCompletionsRequest.Message message = new ChatCompletionsRequest.Message();
    message.role = "user";
    message.content = new ChatCompletionsRequest.MessageContent("Explain to me how AI works");
    request.messages = ImmutableList.of(message);

    Map<String, Object> thinkingConfig = new HashMap<>();
    thinkingConfig.put("thinking_level", "low");
    thinkingConfig.put("include_thoughts", true);

    Map<String, Object> google = new HashMap<>();
    google.put("thinking_config", thinkingConfig);

    Map<String, Object> extraBody = new HashMap<>();
    extraBody.put("google", google);

    request.extraBody = extraBody;

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"extra_body\":{");
    assertThat(json).contains("\"thinking_level\":\"low\"");
    assertThat(json).contains("\"include_thoughts\":true");
  }

  @Test
  public void testSerializeChatCompletionRequest_withToolCallsAndExtraContent() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

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

    Map<String, Object> google = new HashMap<>();
    google.put("thought_signature", "<SIGNATURE_A>");

    Map<String, Object> extraContent = new HashMap<>();
    extraContent.put("google", google);

    toolCall.extraContent = extraContent;

    modelMessage.toolCalls = ImmutableList.of(toolCall);

    ChatCompletionsRequest.Message toolMessage = new ChatCompletionsRequest.Message();
    toolMessage.role = "tool";
    toolMessage.name = "check_flight";
    toolMessage.toolCallId = "function-call-1";
    toolMessage.content = new ChatCompletionsRequest.MessageContent("{\"status\":\"delayed\"}");

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
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

    // Developer message with name
    ChatCompletionsRequest.Message devMsg = new ChatCompletionsRequest.Message();
    devMsg.role = "developer";
    devMsg.content = new ChatCompletionsRequest.MessageContent("System instruction");
    devMsg.name = "system-bot";

    request.messages = ImmutableList.of(devMsg);

    // Response Format JSON Schema
    ChatCompletionsRequest.ResponseFormatJsonSchema format =
        new ChatCompletionsRequest.ResponseFormatJsonSchema();
    format.jsonSchema = new ChatCompletionsRequest.ResponseFormatJsonSchema.JsonSchema();
    format.jsonSchema.name = "MySchema";
    format.jsonSchema.strict = true;
    request.responseFormat = format;

    // Tool Choice Named
    ChatCompletionsRequest.NamedToolChoice choice = new ChatCompletionsRequest.NamedToolChoice();
    choice.function = new ChatCompletionsRequest.NamedToolChoice.FunctionName();
    choice.function.name = "my_function";
    request.toolChoice = choice;

    String json = objectMapper.writeValueAsString(request);

    // Assert Developer Message
    assertThat(json).contains("\"role\":\"developer\"");
    assertThat(json).contains("\"name\":\"system-bot\"");
    assertThat(json).contains("\"content\":\"System instruction\"");

    // Assert Response Format
    assertThat(json).contains("\"response_format\":{");
    assertThat(json).contains("\"type\":\"json_schema\"");
    assertThat(json).contains("\"name\":\"MySchema\"");
    assertThat(json).contains("\"strict\":true");

    // Assert Tool Choice
    assertThat(json).contains("\"tool_choice\":{");
    assertThat(json).contains("\"type\":\"function\"");
    assertThat(json).contains("\"name\":\"my_function\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withToolChoiceMode() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

    request.toolChoice = new ChatCompletionsRequest.ToolChoiceMode("none");

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"tool_choice\":\"none\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withStopAndVoice() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

    request.stop = new ChatCompletionsRequest.StopCondition("STOP");

    ChatCompletionsRequest.AudioParam audio = new ChatCompletionsRequest.AudioParam();
    audio.voice = new ChatCompletionsRequest.VoiceConfig("alloy");
    request.audio = audio;

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"stop\":\"STOP\"");
    assertThat(json).contains("\"voice\":\"alloy\"");
  }

  @Test
  public void testSerializeChatCompletionRequest_withStopList() throws Exception {
    ChatCompletionsRequest request = new ChatCompletionsRequest();
    request.model = "gemini-3-flash-preview";

    request.stop = new ChatCompletionsRequest.StopCondition(ImmutableList.of("STOP1", "STOP2"));

    String json = objectMapper.writeValueAsString(request);

    assertThat(json).contains("\"stop\":[\"STOP1\",\"STOP2\"]");
  }
}
