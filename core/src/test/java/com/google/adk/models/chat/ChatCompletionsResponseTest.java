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
import com.google.adk.models.chat.ChatCompletionsResponse.ChatCompletion;
import com.google.adk.models.chat.ChatCompletionsResponse.ChatCompletionChunk;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ChatCompletionsResponseTest {

  private ObjectMapper objectMapper;

  @Before
  public void setUp() {
    objectMapper = new ObjectMapper();
  }

  @Test
  public void testDeserializeChatCompletion_standardResponse() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1677652288,
          "model": "gpt-4o-mini",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "Hello!"
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": 9,
            "completion_tokens": 12,
            "total_tokens": 21
          }
        }
        """;

    ChatCompletion completion = objectMapper.readValue(json, ChatCompletion.class);

    assertThat(completion.id).isEqualTo("chatcmpl-123");
    assertThat(completion.object).isEqualTo("chat.completion");
    assertThat(completion.created).isEqualTo(1677652288L);
    assertThat(completion.model).isEqualTo("gpt-4o-mini");
    assertThat(completion.choices).hasSize(1);
    assertThat(completion.choices.get(0).index).isEqualTo(0);
    assertThat(completion.choices.get(0).message.role).isEqualTo("assistant");
    assertThat(completion.choices.get(0).message.content).isEqualTo("Hello!");
    assertThat(completion.choices.get(0).finishReason).isEqualTo("stop");
    assertThat(completion.usage.promptTokens).isEqualTo(9);
    assertThat(completion.usage.completionTokens).isEqualTo(12);
    assertThat(completion.usage.totalTokens).isEqualTo(21);
  }

  @Test
  public void testDeserializeChatCompletion_withFunctionCallFallback() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "choices": [{
            "message": {
              "role": "assistant",
              "function_call": {
                "name": "get_current_weather",
                "arguments": "{\\"location\\": \\"Boston\\"}"
              }
            }
          }]
        }
        """;

    ChatCompletion completion = objectMapper.readValue(json, ChatCompletion.class);

    assertThat(completion.choices.get(0).message.functionCall).isNotNull();
    assertThat(completion.choices.get(0).message.functionCall.name)
        .isEqualTo("get_current_weather");
    assertThat(completion.choices.get(0).message.functionCall.arguments)
        .isEqualTo("{\"location\": \"Boston\"}");
  }

  @Test
  public void testDeserializeChatCompletion_withThoughtSignatureAndGeminiTokens() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_abc",
                "type": "function",
                "extra_content": {
                  "google": {
                    "thought_signature": "c2lnbmF0dXJl"
                  }
                }
              }]
            }
          }],
          "usage": {
            "thoughts_token_count": 50
          }
        }
        """;

    ChatCompletion completion = objectMapper.readValue(json, ChatCompletion.class);

    assertThat(completion.choices.get(0).message.toolCalls).hasSize(1);
    assertThat(completion.choices.get(0).message.toolCalls.get(0).extraContent).isNotNull();
    Map<String, Object> extraContentMap =
        (Map<String, Object>) completion.choices.get(0).message.toolCalls.get(0).extraContent;
    @SuppressWarnings("unchecked") // This code won't run in production and it's is a JSON object.
    Map<String, Object> googleMap = (Map<String, Object>) extraContentMap.get("google");
    assertThat(googleMap.get("thought_signature")).isEqualTo("c2lnbmF0dXJl");
    assertThat(completion.usage.thoughtsTokenCount).isEqualTo(50);
  }

  @Test
  public void testDeserializeChatCompletion_withArbitraryExtraContent() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_abc",
                "type": "function",
                "extra_content": {
                  "custom_key": "custom_value",
                  "nested": {
                    "key": 123
                  }
                }
              }]
            }
          }]
        }
        """;

    ChatCompletion got = objectMapper.readValue(json, ChatCompletion.class);

    assertThat(got.choices.get(0).message.toolCalls).hasSize(1);
    Map<String, Object> extraContent =
        (Map<String, Object>) got.choices.get(0).message.toolCalls.get(0).extraContent;
    assertThat(extraContent.get("custom_key")).isEqualTo("custom_value");
    @SuppressWarnings("unchecked") // This code won't run in production and it's is a JSON object.
    Map<String, Object> nested = (Map<String, Object>) extraContent.get("nested");
    assertThat(nested.get("key")).isEqualTo(123);
  }

  @Test
  public void testDeserializeChatCompletion_withAudio() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "Hello",
              "annotations": [{
                "type": "url_citation",
                "url_citation": {
                  "end_index": 5,
                  "start_index": 0,
                  "title": "Example Title",
                  "url": "https://example.com"
                }
              }],
              "audio": {
                "id": "audio_123",
                "data": "base64data",
                "expires_at": 1234567890,
                "transcript": "Hello"
              }
            }
          }]
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    assertThat(completion.choices.get(0).message.annotations).hasSize(1);
    ChatCompletionsResponse.Annotation annotation =
        completion.choices.get(0).message.annotations.get(0);
    assertThat(annotation.type).isEqualTo("url_citation");
    assertThat(annotation.urlCitation.title).isEqualTo("Example Title");
    assertThat(annotation.urlCitation.url).isEqualTo("https://example.com");

    assertThat(completion.choices.get(0).message.audio).isNotNull();
    assertThat(completion.choices.get(0).message.audio.id).isEqualTo("audio_123");
    assertThat(completion.choices.get(0).message.audio.data).isEqualTo("base64data");
  }

  @Test
  public void testDeserializeChatCompletion_withCustomToolCall() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_custom",
                "type": "custom",
                "custom": {
                  "input": "{\\\"arg\\\":\\\"val\\\"}",
                  "name": "custom_tool"
                }
              }]
            }
          }]
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    assertThat(completion.choices.get(0).message.toolCalls).hasSize(1);
    ChatCompletionsCommon.ToolCall toolCall = completion.choices.get(0).message.toolCalls.get(0);
    assertThat(toolCall.type).isEqualTo("custom");
    assertThat(toolCall.custom.name).isEqualTo("custom_tool");
    assertThat(toolCall.custom.input).isEqualTo("{\"arg\":\"val\"}");
  }

  @Test
  public void testDeserializeChatCompletionChunk_streamingResponse() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion.chunk",
          "created": 1694268190,
          "choices": [{
            "index": 0,
            "delta": {
              "content": "Hello"
            }
          }]
        }
        """;

    ChatCompletionChunk chunk = objectMapper.readValue(json, ChatCompletionChunk.class);

    assertThat(chunk.id).isEqualTo("chatcmpl-123");
    assertThat(chunk.object).isEqualTo("chat.completion.chunk");
    assertThat(chunk.choices).hasSize(1);
    assertThat(chunk.choices.get(0).delta.content).isEqualTo("Hello");
  }

  @Test
  public void testDeserializeChatCompletionChunk_withToolCallDelta() throws Exception {
    String json =
        """
        {
          "choices": [{
            "delta": {
              "tool_calls": [{
                "index": 1,
                "id": "call_abc",
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "arguments": "{\\\"location\\\":\\\"Boston\\\"}"
                },
                "extra_content": {
                  "google": {
                    "thought_signature": "sig"
                  }
                }
              }]
            }
          }],
          "usage": {
            "completion_tokens": 10,
            "prompt_tokens": 5,
            "total_tokens": 15
          }
        }
        """;

    ChatCompletionChunk chunk = objectMapper.readValue(json, ChatCompletionChunk.class);

    assertThat(chunk.choices.get(0).delta.toolCalls).hasSize(1);
    ChatCompletionsCommon.ToolCall toolCall = chunk.choices.get(0).delta.toolCalls.get(0);
    assertThat(toolCall.index).isEqualTo(1);
    assertThat(toolCall.id).isEqualTo("call_abc");
    assertThat(toolCall.type).isEqualTo("function");
    assertThat(toolCall.function.name).isEqualTo("get_weather");
    assertThat(toolCall.function.arguments).isEqualTo("{\"location\":\"Boston\"}");
    @SuppressWarnings("unchecked") // This code won't run in production and it's is a JSON object.
    Map<String, Object> google = (Map<String, Object>) toolCall.extraContent.get("google");
    assertThat(google).containsEntry("thought_signature", "sig");

    assertThat(chunk.usage).isNotNull();
    assertThat(chunk.usage.completionTokens).isEqualTo(10);
    assertThat(chunk.usage.promptTokens).isEqualTo(5);
    assertThat(chunk.usage.totalTokens).isEqualTo(15);
  }
}
