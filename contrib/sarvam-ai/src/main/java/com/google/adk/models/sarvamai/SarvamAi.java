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

package com.google.adk.models.sarvamai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.sarvamai.chat.ChatRequest;
import com.google.adk.models.sarvamai.chat.ChatResponse;
import com.google.adk.models.sarvamai.chat.ChatToolCall;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sarvam AI LLM integration for the Agent Development Kit.
 *
 * <p>Provides chat completion (blocking and streaming) via the Sarvam {@code sarvam-m} model using
 * the OpenAI-compatible {@code /v1/chat/completions} endpoint. Authentication uses the {@code
 * api-subscription-key} header per Sarvam API specification.
 *
 * <p>Follows the same architectural patterns as {@link com.google.adk.models.Gemini}, including
 * Builder construction, immutable configuration, and RxJava-based streaming.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * SarvamAi sarvam = SarvamAi.builder()
 *     .modelName("sarvam-m")
 *     .config(SarvamAiConfig.builder()
 *         .apiKey("your-key")
 *         .temperature(0.7)
 *         .build())
 *     .build();
 * }</pre>
 *
 * @author Sandeep Belgavi
 */
public class SarvamAi extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(SarvamAi.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

  private final SarvamAiConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  SarvamAi(String modelName, SarvamAiConfig config, OkHttpClient httpClient) {
    super(modelName);
    this.config = Objects.requireNonNull(config, "config must not be null");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.objectMapper = new ObjectMapper();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Returns the active configuration. */
  public SarvamAiConfig config() {
    return config;
  }

  /** Returns the shared OkHttpClient for subservices (STT, TTS, Vision). */
  OkHttpClient httpClient() {
    return httpClient;
  }

  /** Returns the shared ObjectMapper. */
  ObjectMapper objectMapper() {
    return objectMapper;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return streamContent(llmRequest);
    }

    return Flowable.fromCallable(
        () -> {
          ChatRequest chatRequest = ChatRequest.fromLlmRequest(model(), llmRequest, config, false);
          String body = objectMapper.writeValueAsString(chatRequest);
          logger.debug("Sending chat completion request to {}", config.chatEndpoint());
          logger.trace("Request body: {}", body);

          Request request = buildHttpRequest(config.chatEndpoint(), body);

          try (Response response = httpClient.newCall(request).execute()) {
            handleErrorResponse(response);
            String responseBody = response.body().string();
            logger.trace("Response body: {}", responseBody);
            ChatResponse chatResponse = objectMapper.readValue(responseBody, ChatResponse.class);
            return toLlmResponse(chatResponse);
          }
        });
  }

  private Flowable<LlmResponse> streamContent(LlmRequest llmRequest) {
    return Flowable.create(
        emitter -> {
          try {
            ChatRequest chatRequest = ChatRequest.fromLlmRequest(model(), llmRequest, config, true);
            String body = objectMapper.writeValueAsString(chatRequest);
            logger.debug("Sending streaming chat request to {}", config.chatEndpoint());

            Request request = buildHttpRequest(config.chatEndpoint(), body);

            try (Response response = httpClient.newCall(request).execute()) {
              handleErrorResponse(response);

              if (response.body() == null) {
                emitter.onError(new SarvamAiException("Response body is null"));
                return;
              }

              try (BufferedReader reader = new BufferedReader(response.body().charStream())) {
                String line;
                while ((line = reader.readLine()) != null) {
                  if (emitter.isCancelled()) {
                    break;
                  }
                  if (!line.startsWith("data: ")) {
                    continue;
                  }
                  String data = line.substring(6).trim();
                  if ("[DONE]".equals(data)) {
                    break;
                  }
                  try {
                    JsonNode chunk = objectMapper.readTree(data);
                    JsonNode choices = chunk.path("choices");
                    if (choices.isArray() && !choices.isEmpty()) {
                      JsonNode delta = choices.get(0).path("delta");
                      if (delta.has("content")) {
                        String textChunk = delta.get("content").asText();
                        Content content =
                            Content.builder().role("model").parts(Part.fromText(textChunk)).build();
                        emitter.onNext(
                            LlmResponse.builder().content(content).partial(true).build());
                      }
                    }
                  } catch (Exception parseError) {
                    logger.trace("Skipping unparseable SSE line: {}", data);
                  }
                }
              }
              emitter.onComplete();
            }
          } catch (Exception e) {
            if (!emitter.isCancelled()) {
              emitter.onError(e);
            }
          }
        },
        BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    logger.debug("Establishing Sarvam AI live connection");
    return new SarvamAiLlmConnection(this, llmRequest);
  }

  Request buildHttpRequest(String url, String jsonBody) {
    return new Request.Builder()
        .url(url)
        .addHeader("api-subscription-key", config.apiKey())
        .addHeader("Content-Type", "application/json")
        .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE))
        .build();
  }

  void handleErrorResponse(Response response) throws IOException {
    if (response.isSuccessful()) {
      return;
    }
    String errorBody = response.body() != null ? response.body().string() : "";
    String errorCode = null;
    String requestId = null;
    String message = "Sarvam API error " + response.code();

    try {
      JsonNode errorJson = objectMapper.readTree(errorBody);
      JsonNode error = errorJson.path("error");
      if (!error.isMissingNode()) {
        message = error.path("message").asText(message);
        errorCode = error.path("code").asText(null);
        requestId = error.path("request_id").asText(null);
      }
    } catch (Exception ignored) {
      // Use raw error body as message fallback
      if (!errorBody.isEmpty()) {
        message = message + ": " + errorBody;
      }
    }

    throw new SarvamAiException(message, response.code(), errorCode, requestId);
  }

  private LlmResponse toLlmResponse(ChatResponse chatResponse) {
    if (chatResponse.getChoices() == null || chatResponse.getChoices().isEmpty()) {
      throw new SarvamAiException("Empty choices in response");
    }
    var choice = chatResponse.getChoices().get(0);
    var effectiveMsg = choice.effectiveMessage();
    if (effectiveMsg == null) {
      throw new SarvamAiException("No message in response choice");
    }

    // Handle tool_calls in response (model requesting function execution)
    if (effectiveMsg.getToolCalls() != null && !effectiveMsg.getToolCalls().isEmpty()) {
      List<Part> parts = new ArrayList<>();
      for (ChatToolCall tc : effectiveMsg.getToolCalls()) {
        if (tc.getFunction() == null || tc.getFunction().getName() == null) {
          continue;
        }
        String argsStr = tc.getFunction().getArguments();
        if (argsStr == null) {
          argsStr = "{}";
        }
        Map<String, Object> args;
        try {
          args = objectMapper.readValue(argsStr, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
          args = Map.of();
        }
        FunctionCall fc =
            FunctionCall.builder()
                .id(tc.getId())
                .name(tc.getFunction().getName())
                .args(args)
                .build();
        parts.add(Part.builder().functionCall(fc).build());
      }
      if (parts.isEmpty()) {
        throw new SarvamAiException("Tool calls in response but no valid function call found");
      }
      Content content = Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build();
      return LlmResponse.builder().content(content).build();
    }

    // Handle text content
    String textContent = effectiveMsg.getContent();
    if (textContent == null) {
      textContent = "";
    }
    Content content = Content.builder().role("model").parts(Part.fromText(textContent)).build();
    return LlmResponse.builder().content(content).build();
  }

  /** Builder for {@link SarvamAi}. Mirrors the Gemini builder pattern. */
  public static final class Builder {
    private String modelName;
    private SarvamAiConfig config;
    private OkHttpClient httpClient;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder config(SarvamAiConfig config) {
      this.config = config;
      return this;
    }

    /**
     * Provides a custom OkHttpClient. If not set, a default client is created with retry
     * interceptor and timeouts from the config.
     */
    @CanIgnoreReturnValue
    public Builder httpClient(OkHttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public SarvamAi build() {
      Objects.requireNonNull(modelName, "modelName must be set");
      Objects.requireNonNull(config, "config must be set");

      OkHttpClient client = this.httpClient;
      if (client == null) {
        client =
            new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .addInterceptor(new SarvamRetryInterceptor(config.maxRetries()))
                .build();
      }

      return new SarvamAi(modelName, config, client);
    }
  }
}
