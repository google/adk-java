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
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.chat.ChatCompletionsResponse.ChatCompletion;
import com.google.adk.models.chat.ChatCompletionsResponse.ChatCompletionChunk;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FinishReason.Known;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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
        completion.choices.get(0).message.toolCalls.get(0).extraContent;
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
    Map<String, Object> extraContent = got.choices.get(0).message.toolCalls.get(0).extraContent;
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

  @Test
  public void testToLlmResponse_simpleText() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1694268190,
          "model": "gpt-4",
          "system_fingerprint": "fp_123",
          "service_tier": "scale",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "Hello world"
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "completion_tokens": 10,
            "prompt_tokens": 5,
            "total_tokens": 15,
            "thoughts_token_count": 42
          }
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    assertThat(response.modelVersion()).hasValue("gpt-4");
    assertThat(response.finishReason().get().knownEnum()).isEqualTo(Known.STOP);

    // Usage Metadata
    assertThat(response.usageMetadata().get().promptTokenCount()).hasValue(5);
    assertThat(response.usageMetadata().get().candidatesTokenCount()).hasValue(10);
    assertThat(response.usageMetadata().get().totalTokenCount()).hasValue(15);
    assertThat(response.usageMetadata().get().thoughtsTokenCount()).hasValue(42);

    // Content
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts().get().get(0).text()).hasValue("Hello world");

    // Custom Metadata
    List<CustomMetadata> metadata = response.customMetadata().get();
    assertThat(metadata).hasSize(5);
    assertThat(metadata.get(0).key()).hasValue("id");
    assertThat(metadata.get(0).stringValue()).hasValue("chatcmpl-123");
    assertThat(metadata.get(1).key()).hasValue("created");
    assertThat(metadata.get(1).stringValue()).hasValue("1694268190");
    assertThat(metadata.get(2).key()).hasValue("object");
    assertThat(metadata.get(2).stringValue()).hasValue("chat.completion");
    assertThat(metadata.get(3).key()).hasValue("system_fingerprint");
    assertThat(metadata.get(3).stringValue()).hasValue("fp_123");
    assertThat(metadata.get(4).key()).hasValue("service_tier");
    assertThat(metadata.get(4).stringValue()).hasValue("scale");
  }

  @Test
  public void testToLlmResponse_userRole() throws Exception {
    String json =
        """
        {
          "choices": [{
            "index": 0,
            "message": {
              "role": "user",
              "content": "Hello world"
            },
            "finish_reason": "stop"
          }]
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    assertThat(response.content().get().role()).hasValue("user");
  }

  @Test
  public void testToLlmResponse_withToolCall_simple() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_123",
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "arguments": "{\\\"location\\\":\\\"Seattle\\\"}"
                }
              }]
            }
          }]
         }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    Part part = response.content().get().parts().get().get(0);
    FunctionCall fc = part.functionCall().get();
    assertThat(fc.id()).hasValue("call_123");
    assertThat(fc.name()).hasValue("get_weather");
    assertThat(fc.args().get().get("location")).isEqualTo("Seattle");

    assertThat(response.customMetadata().get()).isEmpty();
  }

  @Test
  public void testToLlmResponse_thoughtSignature() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "tool_calls": [{
                "id": "call_123",
                "type": "function",
                "function": {
                  "name": "get_weather",
                  "arguments": "{\\\"location\\\":\\\"Seattle\\\"}"
                },
                "extra_content": {
                  "google": {
                    "thought_signature": "c2ln"
                  }
                }
              }]
            }
          }]
         }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();
    assertThat(response.content().get().parts().get().get(0).thoughtSignature().get())
        .isEqualTo(Base64.getDecoder().decode("c2ln"));
  }

  @Test
  public void testToLlmResponse_withRefusal() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1677652288,
          "model": "gpt-3.5-turbo-0125",
          "choices": [{
            "index": 0,
            "message": {
              "role": "assistant",
              "content": "Partial text answer",
              "refusal": "System error or refusal"
            },
            "finish_reason": "stop"
          }]
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    assertThat(response.modelVersion()).hasValue("gpt-3.5-turbo-0125");
    assertThat(response.finishReason().get().knownEnum()).isEqualTo(Known.STOP);

    // Content
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts().get()).hasSize(2);
    assertThat(response.content().get().parts().get().get(0).text())
        .hasValue("Partial text answer");
    assertThat(response.content().get().parts().get().get(1).text())
        .hasValue("[[REFUSAL]]: System error or refusal");

    // Custom Metadata
    List<CustomMetadata> metadata = response.customMetadata().get();
    assertThat(metadata).hasSize(3);
    assertThat(metadata.get(0).key()).hasValue("id");
    assertThat(metadata.get(0).stringValue()).hasValue("chatcmpl-123");
    assertThat(metadata.get(1).key()).hasValue("created");
    assertThat(metadata.get(1).stringValue()).hasValue("1677652288");
    assertThat(metadata.get(2).key()).hasValue("object");
    assertThat(metadata.get(2).stringValue()).hasValue("chat.completion");
  }

  @Test
  public void testToLlmResponse_reasoningTokens() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "hello"
            },
            "finish_reason": "stop"
          }],
          "usage": {
            "prompt_tokens": 10,
            "completion_tokens": 5,
            "total_tokens": 15,
            "completion_tokens_details": {
              "reasoning_tokens": 4
            }
          }
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    assertThat(response.finishReason().get().knownEnum()).isEqualTo(Known.STOP);

    // Content
    assertThat(response.content().get().role()).hasValue("model");
    assertThat(response.content().get().parts().get().get(0).text()).hasValue("hello");

    // Usage Metadata
    assertThat(response.usageMetadata().get().promptTokenCount()).hasValue(10);
    assertThat(response.usageMetadata().get().candidatesTokenCount()).hasValue(5);
    assertThat(response.usageMetadata().get().totalTokenCount()).hasValue(15);
    assertThat(response.usageMetadata().get().thoughtsTokenCount()).hasValue(4);

    assertThat(response.customMetadata().get()).isEmpty();
  }

  @Test
  public void testToolCallToPart_withFunction() throws Exception {
    String json =
        """
        {
          "id": "call_123",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\\\"location\\\":\\\"Seattle\\\"}"
          }
        }
        """;
    ChatCompletionsCommon.ToolCall toolCall =
        objectMapper.readValue(json, ChatCompletionsCommon.ToolCall.class);

    Part part = toolCall.toPart();

    assertThat(part).isNotNull();
    assertThat(part.functionCall()).isPresent();
    FunctionCall fc = part.functionCall().get();
    assertThat(fc.id()).hasValue("call_123");
    assertThat(fc.name()).hasValue("get_weather");
  }

  @Test
  public void testToolCallToPart_withFunction_nullId() throws Exception {
    String json =
        """
        {
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\\\"location\\\":\\\"Seattle\\\"}"
          }
        }
        """;
    ChatCompletionsCommon.ToolCall toolCall =
        objectMapper.readValue(json, ChatCompletionsCommon.ToolCall.class);

    Part part = toolCall.toPart();

    assertThat(part).isNotNull();
    assertThat(part.functionCall()).isPresent();
    FunctionCall fc = part.functionCall().get();
    assertThat(fc.id()).isEmpty();
  }

  @Test
  public void testToolCallToPart_withThoughtSignature() throws Exception {
    String json =
        """
        {
          "id": "call_123",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\\\"location\\\":\\\"Seattle\\\"}"
          },
          "extra_content": {
            "google": {
              "thought_signature": "c2ln"
            }
          }
        }
        """;
    ChatCompletionsCommon.ToolCall toolCall =
        objectMapper.readValue(json, ChatCompletionsCommon.ToolCall.class);

    Part part = toolCall.toPart();

    assertThat(part).isNotNull();
    assertThat(part.thoughtSignature()).hasValue(Base64.getDecoder().decode("c2ln"));
  }

  @Test
  public void testToolCallToPart_nullFunction() throws Exception {
    String json =
        """
        {
          "id": "call_123",
          "type": "function"
        }
        """;
    ChatCompletionsCommon.ToolCall toolCall =
        objectMapper.readValue(json, ChatCompletionsCommon.ToolCall.class);

    Part part = toolCall.toPart();

    assertThat(part).isNull();
  }

  @Test
  public void testToLlmResponse_noChoices() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1677652288,
          "model": "gpt-4"
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    assertThat(response.modelVersion()).hasValue("gpt-4");
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isEmpty();
  }

  @Test
  public void testChunkCollection_accumulatesMultipleToolCalls() throws Exception {
    ChatCompletionsResponse.ChatCompletionChunkCollection collection =
        new ChatCompletionsResponse.ChatCompletionChunkCollection();

    String chunk1Json =
        """
        {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_id_1","type":"function","function":{"name":"roll_die","arguments":""}}]}}]}
        """;
    String chunk2Json =
        """
        {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\\"sides\\\":8}"}}]}}]}
        """;
    String chunk3Json =
        """
        {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_id_2","type":"function","function":{"name":"roll_die","arguments":""}}]}}]}
        """;
    String chunk4Json =
        """
        {"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\\\"sides\\\":8}"}}]}}]}
        """;
    String chunk5Json =
        """
        {"choices":[{"finish_reason":"tool_calls"}]}
        """;

    ImmutableList<LlmResponse> unused1 =
        collection.processChunk(
            objectMapper.readValue(chunk1Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> unused2 =
        collection.processChunk(
            objectMapper.readValue(chunk2Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> unused3 =
        collection.processChunk(
            objectMapper.readValue(chunk3Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> unused4 =
        collection.processChunk(
            objectMapper.readValue(chunk4Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> responses =
        collection.processChunk(
            objectMapper.readValue(chunk5Json, ChatCompletionsResponse.ChatCompletionChunk.class));

    LlmResponse expectedFinalResponse =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("")
                    .parts(
                        Arrays.asList(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .id("call_id_1")
                                        .name("roll_die")
                                        .args(ImmutableMap.of("sides", 8))
                                        .build())
                                .build(),
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .id("call_id_2")
                                        .name("roll_die")
                                        .args(ImmutableMap.of("sides", 8))
                                        .build())
                                .build()))
                    .build())
            .finishReason(new FinishReason(Known.STOP.toString()))
            .customMetadata(ImmutableList.of())
            .modelVersion("")
            .build();

    // Tool call deltas are accumulated across chunks 1-4 without emitting partial responses.
    // Chunk 5 (finish_reason=tool_calls) emits a single metadata-final response carrying the fully
    // accumulated tool calls and the FinishReason. Size 1.
    assertThat(responses).hasSize(1);
    LlmResponse finalResponse = responses.get(0);

    assertThat(finalResponse).isEqualTo(expectedFinalResponse);
  }

  @Test
  public void testChunkCollection_simpleText() throws Exception {
    ChatCompletionsResponse.ChatCompletionChunkCollection collection =
        new ChatCompletionsResponse.ChatCompletionChunkCollection();

    String chunk1Json =
        """
        {"choices":[{"delta":{"content":"Hello "}}]}
        """;
    String chunk2Json =
        """
        {"choices":[{"delta":{"content":"World!"}}]}
        """;
    String chunk3Json =
        """
        {"choices":[{"finish_reason":"stop"}]}
        """;

    ImmutableList<LlmResponse> unused1 =
        collection.processChunk(
            objectMapper.readValue(chunk1Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> unused2 =
        collection.processChunk(
            objectMapper.readValue(chunk2Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> responses =
        collection.processChunk(
            objectMapper.readValue(chunk3Json, ChatCompletionsResponse.ChatCompletionChunk.class));

    // For a text-only turn, the finish_reason chunk emits TWO non-partial responses:
    // (A) an aggregated-text response with the full text but NO finishReason.
    // (B) a metadata-final response with FinishReason=STOP and no text parts.
    // See ChatCompletionsResponse.processChunk for rationale. Size 2.
    LlmResponse expectedAggregatedTextResponse =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("")
                    .parts(ImmutableList.of(Part.fromText("Hello World!")))
                    .build())
            .customMetadata(ImmutableList.of())
            .modelVersion("")
            .build();
    LlmResponse expectedFinalResponse =
        LlmResponse.builder()
            .content(Content.builder().role("").parts(ImmutableList.of()).build())
            .finishReason(new FinishReason(Known.STOP.toString()))
            .customMetadata(ImmutableList.of())
            .modelVersion("")
            .build();

    assertThat(responses)
        .containsExactly(expectedAggregatedTextResponse, expectedFinalResponse)
        .inOrder();
  }

  @Test
  public void testChunkCollection_withRefusal() throws Exception {
    ChatCompletionsResponse.ChatCompletionChunkCollection collection =
        new ChatCompletionsResponse.ChatCompletionChunkCollection();

    String chunk1Json =
        """
        {"choices":[{"delta":{"refusal":"I cannot do that."}}]}
        """;
    String chunk2Json =
        """
        {"choices":[{"finish_reason":"stop"}]}
        """;

    ImmutableList<LlmResponse> unused1 =
        collection.processChunk(
            objectMapper.readValue(chunk1Json, ChatCompletionsResponse.ChatCompletionChunk.class));
    ImmutableList<LlmResponse> responses =
        collection.processChunk(
            objectMapper.readValue(chunk2Json, ChatCompletionsResponse.ChatCompletionChunk.class));

    // Similar to testChunkCollection_simpleText: chunk 1 streams the refusal, then chunk 2
    // (finish_reason) emits an aggregated-text response with the full refusal text, followed
    // by a metadata-final response with FinishReason and no text parts. Size 2.
    LlmResponse expectedAggregatedTextResponse =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("")
                    .parts(ImmutableList.of(Part.fromText("I cannot do that.")))
                    .build())
            .customMetadata(ImmutableList.of())
            .modelVersion("")
            .build();
    LlmResponse expectedFinalResponse =
        LlmResponse.builder()
            .content(Content.builder().role("").parts(ImmutableList.of()).build())
            .finishReason(new FinishReason(Known.STOP.toString()))
            .customMetadata(ImmutableList.of())
            .modelVersion("")
            .build();

    assertThat(responses)
        .containsExactly(expectedAggregatedTextResponse, expectedFinalResponse)
        .inOrder();
  }

  @Test
  public void testChunkCollection_noChoices() throws Exception {
    String json =
        """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1677652288,
          "model": "gpt-4"
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    assertThat(response.modelVersion()).hasValue("gpt-4");
    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isEmpty();
  }

  // ----- thought_signature decoding on the response side -----------------------------------
  //
  // The response code maps wire-level extra_content.google.thought_signature (base64) onto
  // Part.thoughtSignature() bytes across four conceptual paths. The tests below cover one
  // canonical positive per path plus a single malformed-input tolerance check and one
  // request-response byte-equality round-trip:
  //   1. Non-streaming text:   Message.extra_content is PARSED onto the DTO but is NOT
  //                            attached to the output text Part. Characterized below as
  //                            current behavior (likely a bug; see the test's TODO).
  //   2. Non-streaming tool:   ToolCall.extra_content --> tool-call Part.thoughtSignature
  //                            (already covered by testToLlmResponse_thoughtSignature).
  //   3. Streaming text:       Per-chunk delta.extra_content captured into
  //                            ChatCompletionChunkCollection.accumulatedTextThoughtSignature,
  //                            attached to the aggregated text Part on the finish chunk.
  //   4. Streaming tool:       Per-chunk delta.tool_calls[i].extra_content applied to the
  //                            accumulated tool-call Part; message-level signature backfills
  //                            tool-call Parts that lack their own.

  private static final byte[] streamingTextSignature = {0x0a, 0x0b, 0x0c};
  private static final byte[] streamingToolSignature = {0x11, 0x12, 0x13, 0x14};
  private static final String STREAMING_TEXT_SIGNATURE_B64 =
      Base64.getEncoder().encodeToString(streamingTextSignature);
  private static final String STREAMING_TOOL_SIGNATURE_B64 =
      Base64.getEncoder().encodeToString(streamingToolSignature);

  @Test
  public void testToLlmResponse_nonStreamingText_messageLevelSignatureStaysOnDtoButNotOnOutputPart()
      throws Exception {
    // Characterizes a known asymmetry between the streaming and non-streaming paths:
    //   - Streaming:     ChatCompletionChunkCollection.captureMessageThoughtSignature decodes
    //                    extra_content.google.thought_signature from any delta and attaches
    //                    it to the aggregated text Part (see buildAggregatedTextResponse).
    //   - Non-streaming: mapMessageToParts does NOT decode Message.extraContent at all; the
    //                    signature parses onto the Message DTO but never lands on any Part.
    //
    // Gemini's OpenAI-compatible endpoint emits a message-level thought_signature on
    // assistant text responses; if not round-tripped on the next turn, Gemini may retry or
    // loop. Today the non-streaming branch silently drops the signature, which is likely a
    // bug. This test pins the CURRENT behavior so it is visible to future readers and so any
    // future fix (propagating the signature to the text Part, mirroring streaming) flips
    // this test from passing to failing -- forcing an intentional, documented update.
    //
    // TODO(b/...): consider attaching message.extraContent.google.thought_signature to the
    // output text Part to match the streaming-path contract. If/when that fix lands, this
    // test should be updated to assert that textPart.thoughtSignature() has the decoded
    // bytes (compare with
    // testChunkCollection_streamingText_messageLevelSignatureAttachesToAggregatedTextPart).
    String json =
        String.format(
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": "Hello world",
                  "extra_content": {
                    "google": {
                      "thought_signature": "%s"
                    }
                  }
                },
                "finish_reason": "stop"
              }]
            }
            """,
            STREAMING_TEXT_SIGNATURE_B64);

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    // The DTO field IS populated (Jackson parses the JSON) ...
    @SuppressWarnings("unchecked")
    Map<String, Object> google =
        (Map<String, Object>) completion.choices.get(0).message.extraContent.get("google");
    assertThat(google).containsEntry("thought_signature", STREAMING_TEXT_SIGNATURE_B64);

    // ... but mapMessageToParts does NOT propagate it to the output text Part.
    LlmResponse response = completion.toLlmResponse();
    Part textPart = response.content().get().parts().get().get(0);
    assertThat(textPart.text()).hasValue("Hello world");
    assertThat(textPart.thoughtSignature()).isEmpty();
  }

  @Test
  public void testToLlmResponse_nonStreamingText_malformedExtraContent_doesNotCrash()
      throws Exception {
    // Defensive: a non-string thought_signature (e.g. the number 42) on a non-streaming
    // text Message must not throw during toLlmResponse(). The Message DTO parses the field
    // as Map<String, Object>, so a numeric value lands as Integer/Long and any future
    // decoder needs to tolerate it. Today's code does nothing with it; this test guards
    // both today's no-op behavior and any future decode site from a NullPointer/ClassCast.
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "hi",
              "extra_content": {
                "google": {
                  "thought_signature": 42
                }
              }
            },
            "finish_reason": "stop"
          }]
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    Part textPart = response.content().get().parts().get().get(0);
    assertThat(textPart.text()).hasValue("hi");
    assertThat(textPart.thoughtSignature()).isEmpty();
  }

  @Test
  public void testToLlmResponse_nonStreamingText_googleNotAMap_doesNotCrash() throws Exception {
    String json =
        """
        {
          "choices": [{
            "message": {
              "role": "assistant",
              "content": "hi",
              "extra_content": {
                "google": "not_a_map"
              }
            },
            "finish_reason": "stop"
          }]
        }
        """;

    ChatCompletionsResponse.ChatCompletion completion =
        objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletion.class);

    LlmResponse response = completion.toLlmResponse();

    Part textPart = response.content().get().parts().get().get(0);
    assertThat(textPart.text()).hasValue("hi");
    assertThat(textPart.thoughtSignature()).isEmpty();
  }

  // ----- streaming thought_signature paths -------------------------------------------------

  /**
   * Pushes the given JSON chunks (one per varargs entry) through a fresh {@link
   * ChatCompletionsResponse.ChatCompletionChunkCollection} and returns the concatenated list of all
   * {@link LlmResponse} values emitted. Centralizes the four-line decode-and-process boiler so
   * streaming tests stay focused on assertions.
   */
  private ImmutableList<LlmResponse> runStream(String... chunkJson) throws Exception {
    ChatCompletionsResponse.ChatCompletionChunkCollection collection =
        new ChatCompletionsResponse.ChatCompletionChunkCollection();
    ImmutableList.Builder<LlmResponse> all = ImmutableList.builder();
    for (String json : chunkJson) {
      all.addAll(
          collection.processChunk(
              objectMapper.readValue(json, ChatCompletionsResponse.ChatCompletionChunk.class)));
    }
    return all.build();
  }

  @Test
  public void testChunkCollection_streamingText_messageLevelSignatureAttachesToAggregatedTextPart()
      throws Exception {
    // The canonical Gemini streaming-text pattern: text chunks first, then a finish chunk that
    // carries the message-level thought_signature in delta.extra_content. The aggregated-text
    // response emitted on the finish chunk MUST carry the signature on its single Part.
    String chunk1 = "{\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}";
    String chunk2 = "{\"choices\":[{\"delta\":{\"content\":\"world!\"}}]}";
    String chunk3 =
        String.format(
            "{\"choices\":[{\"delta\":{\"extra_content\":{\"google\":{\"thought_signature\":\"%s\"}}},\"finish_reason\":\"stop\"}]}",
            STREAMING_TEXT_SIGNATURE_B64);

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2, chunk3);

    // chunk1 and chunk2 each emit a partial text response (no signature); chunk3 emits
    // (A) aggregated-text with signature, then (B) the metadata-final response.
    assertThat(all).hasSize(4);
    LlmResponse aggregated = all.get(2);
    Part aggregatedTextPart = aggregated.content().get().parts().get().get(0);
    assertThat(aggregatedTextPart.text()).hasValue("Hello world!");
    assertThat(aggregatedTextPart.thoughtSignature()).hasValue(streamingTextSignature);
  }

  @Test
  public void testChunkCollection_streamingText_malformedExtraContent_doesNotCrash()
      throws Exception {
    String chunk1 = "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
    String chunk2 =
        "{\"choices\":[{\"delta\":{\"extra_content\":{\"google\":42}},\"finish_reason\":\"stop\"}]}";

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    assertThat(all).hasSize(3);
    LlmResponse aggregated = all.get(1);
    Part aggregatedTextPart = aggregated.content().get().parts().get().get(0);
    assertThat(aggregatedTextPart.text()).hasValue("hi");
    assertThat(aggregatedTextPart.thoughtSignature()).isEmpty();
  }

  @Test
  public void testChunkCollection_streamingText_googleNotAMap_doesNotCrash() throws Exception {
    String chunk1 = "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
    String chunk2 =
        "{\"choices\":[{\"delta\":{\"extra_content\":{\"google\":\"not_a_map\"}},\"finish_reason\":\"stop\"}]}";

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    assertThat(all).hasSize(3);
    LlmResponse aggregated = all.get(1);
    Part aggregatedTextPart = aggregated.content().get().parts().get().get(0);
    assertThat(aggregatedTextPart.text()).hasValue("hi");
    assertThat(aggregatedTextPart.thoughtSignature()).isEmpty();
  }

  @Test
  public void testChunkCollection_streamingToolCall_perToolCallSignatureAttachesToFinalPart()
      throws Exception {
    // Per-tool-call streaming: a tool_call delta with extra_content.google.thought_signature
    // must land on the accumulated tool-call Part by the time finish_reason=tool_calls fires.
    String chunk1 =
        String.format(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"do_thing\",\"arguments\":\"{}\"},\"extra_content\":{\"google\":{\"thought_signature\":\"%s\"}}}]}}]}",
            STREAMING_TOOL_SIGNATURE_B64);
    String chunk2 = "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}";

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    // Tool-call chunks are accumulated silently: per the doc-comment on accumulateToolCalls
    // ("To prevent downstream flows from dispatching the same tool multiple times, partial
    // tool calls are NOT emitted."), chunk1 emits zero events. chunk2's finish chunk emits
    // a single metadata-final response carrying the fully-accumulated tool-call Part with the
    // per-tool-call signature applied by updateAccumulatedToolCall.
    assertThat(all).hasSize(1);

    LlmResponse finalResponse = all.get(0);
    assertThat(finalResponse.finishReason().get().knownEnum()).isEqualTo(Known.STOP);
    Part finalToolPart = finalResponse.content().get().parts().get().get(0);
    assertThat(finalToolPart.functionCall().get().name()).hasValue("do_thing");
    assertThat(finalToolPart.thoughtSignature()).hasValue(streamingToolSignature);
  }

  @Test
  public void testChunkCollection_streamingToolCall_backfillsMessageLevelSignatureWhenAbsent()
      throws Exception {
    // When a tool-call Part lacks its own per-call signature but the stream carries a
    // message-level signature (typical Gemini pattern: message-level signature on the final
    // chunk), getFinalToolCallParts backfills it onto the tool-call Part so the assistant
    // turn round-trips with a signature.
    String chunk1 =
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"do_thing\",\"arguments\":\"{}\"}}]}}]}";
    String chunk2 =
        String.format(
            "{\"choices\":[{\"delta\":{\"extra_content\":{\"google\":{\"thought_signature\":\"%s\"}}},\"finish_reason\":\"tool_calls\"}]}",
            STREAMING_TEXT_SIGNATURE_B64);

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    // chunk1: silently accumulated (no partial emitted for tool calls). chunk2: single
    // metadata-final response (no aggregated-text event because contentParts is empty).
    assertThat(all).hasSize(1);

    LlmResponse finalResponse = all.get(0);
    Part finalToolPart = finalResponse.content().get().parts().get().get(0);
    // Backfilled signature.
    assertThat(finalToolPart.thoughtSignature()).hasValue(streamingTextSignature);
  }

  @Test
  public void
      testChunkCollection_streamingToolCall_messageLevelSignatureDoesNotOverwriteExistingToolCallSignature()
          throws Exception {
    // If a tool-call already has its own per-tool-call signature, a message-level signature
    // does not overwrite it during getFinalToolCallParts.
    String chunk1 =
        String.format(
            "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"do_thing\",\"arguments\":\"{}\"},\"extra_content\":{\"google\":{\"thought_signature\":\"%s\"}}}]}}]}",
            STREAMING_TOOL_SIGNATURE_B64);
    String chunk2 =
        String.format(
            "{\"choices\":[{\"delta\":{\"extra_content\":{\"google\":{\"thought_signature\":\"%s\"}}},\"finish_reason\":\"tool_calls\"}]}",
            STREAMING_TEXT_SIGNATURE_B64);

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    assertThat(all).hasSize(1);
    LlmResponse finalResponse = all.get(0);
    Part finalToolPart = finalResponse.content().get().parts().get().get(0);
    // Preserved tool-level signature, not the message-level one.
    assertThat(finalToolPart.thoughtSignature()).hasValue(streamingToolSignature);
  }

  @Test
  public void testChunkCollection_streamingToolCall_parsesValidJsonArgs() throws Exception {
    String chunk1 =
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"do_thing\",\"arguments\":\"{\\\"key\\\": \\\"value\\\"}\"}}]}}]}";
    String chunk2 = "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}";

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    assertThat(all).hasSize(1);
    LlmResponse finalResponse = all.get(0);
    Part finalToolPart = finalResponse.content().get().parts().get().get(0);
    assertThat(finalToolPart.functionCall().get().name()).hasValue("do_thing");
    assertThat(finalToolPart.functionCall().get().args().get().get("key")).isEqualTo("value");
  }

  @Test
  public void testChunkCollection_streamingToolCall_handlesEmptyArgs() throws Exception {
    String chunk1 =
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"do_thing\",\"arguments\":\"\"}}]}}]}";
    String chunk2 = "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}";

    ImmutableList<LlmResponse> all = runStream(chunk1, chunk2);

    assertThat(all).hasSize(1);
    LlmResponse finalResponse = all.get(0);
    Part finalToolPart = finalResponse.content().get().parts().get().get(0);
    assertThat(finalToolPart.functionCall().get().name()).hasValue("do_thing");
    assertThat(finalToolPart.functionCall().get().args()).isEmpty();
  }

  @Test
  public void testChunkCollection_streamingToolCall_throwsOnInvalidJsonArgs() {
    String chunk1 =
        "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"do_thing\",\"arguments\":\"none\"}}]}}]}";
    String chunk2 = "{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}";

    org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> runStream(chunk1, chunk2));
  }

  // ----- Round-trip: Part(sig) --> request --> response --> Part(sig) bytewise equal -------

  @Test
  public void testRoundTrip_functionCallSignature_bytesPreservedThroughRequestAndResponse()
      throws Exception {
    // Bytewise round-trip from a Part with a signature through the request encoder, then
    // back through the response decoder. Guards against any encoding-decoding asymmetry
    // (e.g. URL-safe vs standard base64) that DTO-only tests cannot catch.
    byte[] originalSig = {0x00, 0x7f, (byte) 0x80, (byte) 0xff};

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
                                        FunctionCall.builder().id("call_rt").name("ping").build())
                                    .thoughtSignature(originalSig)
                                    .build()))
                        .build()))
            .build();
    ChatCompletionsRequest request = ChatCompletionsRequest.fromLlmRequest(llmRequest, false);

    // Sanity-check the outbound DTO carries the same encoded signature value...
    @SuppressWarnings("unchecked")
    Map<String, Object> outboundGoogle =
        (Map<String, Object>) request.messages.get(0).toolCalls.get(0).extraContent.get("google");
    String encodedSig = (String) outboundGoogle.get("thought_signature");

    // ...then synthesize a wire-shaped response carrying the same encoded sig and decode it
    // back through toLlmResponse.
    String responseJson =
        String.format(
            """
            {
              "choices": [{
                "message": {
                  "role": "assistant",
                  "tool_calls": [{
                    "id": "call_rt",
                    "type": "function",
                    "function": { "name": "ping", "arguments": "{}" },
                    "extra_content": {
                      "google": {
                        "thought_signature": "%s"
                      }
                    }
                  }]
                }
              }]
            }
            """,
            encodedSig);

    ChatCompletion roundTrippedCompletion =
        objectMapper.readValue(responseJson, ChatCompletion.class);
    LlmResponse roundTripped = roundTrippedCompletion.toLlmResponse();

    Part decodedPart = roundTripped.content().get().parts().get().get(0);
    assertThat(decodedPart.thoughtSignature()).hasValue(originalSig);
  }
}
