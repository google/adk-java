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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link BaseLlm} implementation for calling the <a
 * href="https://www.orcarouter.ai">OrcaRouter</a> model gateway.
 *
 * <p>OrcaRouter is an OpenAI-compatible model gateway that routes each request to a model hosted by
 * one of its upstream providers. This implementation talks to the gateway's {@code
 * /v1/chat/completions} endpoint using {@link ChatCompletionsHttpClient}, supporting both
 * non-streaming and streaming responses.
 *
 * <p>The API key is read from the {@code ORCAROUTER_API_KEY} environment variable. The base URL
 * defaults to {@code https://api.orcarouter.ai/v1} and can be overridden with the {@code
 * ORCAROUTER_BASE_URL} environment variable.
 *
 * <p>Model names must use the {@code orcarouter/<model>} format, e.g. {@code orcarouter/auto}
 * (smart routing) or {@code orcarouter/fusion}. The gateway accepts the model identifier as-is,
 * including the {@code orcarouter/} namespace prefix.
 */
public class OrcaRouterLlm extends BaseLlm {
  private static final Logger logger = LoggerFactory.getLogger(OrcaRouterLlm.class);

  static final String API_KEY_ENV_VAR = "ORCAROUTER_API_KEY";
  static final String BASE_URL_ENV_VAR = "ORCAROUTER_BASE_URL";
  static final String DEFAULT_BASE_URL = "https://api.orcarouter.ai/v1";
  static final String MODEL_PREFIX = "orcarouter/";

  private final ChatCompletionsClient chatCompletionsClient;

  /**
   * Constructs a new {@code OrcaRouterLlm} instance.
   *
   * <p>The chat completions client is built from the {@code ORCAROUTER_API_KEY} and {@code
   * ORCAROUTER_BASE_URL} environment variables.
   *
   * @param modelName the OrcaRouter model name (e.g., {@code orcarouter/auto})
   * @throws IllegalArgumentException if the model name is not in the {@code orcarouter/<model>}
   *     format or the API key is not configured
   */
  public OrcaRouterLlm(String modelName) {
    this(modelName, buildChatCompletionsClient());
  }

  /**
   * Constructs a new {@code OrcaRouterLlm} instance with the given chat completions client, for
   * testing purposes.
   *
   * @param modelName the OrcaRouter model name (e.g., {@code orcarouter/auto})
   * @param chatCompletionsClient the client used to call the gateway
   */
  @VisibleForTesting
  OrcaRouterLlm(String modelName, ChatCompletionsClient chatCompletionsClient) {
    super(validateModelName(modelName));
    this.chatCompletionsClient =
        Objects.requireNonNull(chatCompletionsClient, "chatCompletionsClient cannot be null");
  }

  /** Builds the production chat completions client from environment variables. */
  private static ChatCompletionsClient buildChatCompletionsClient() {
    String apiKey = System.getenv(API_KEY_ENV_VAR);
    if (isNullOrEmpty(apiKey)) {
      throw new IllegalArgumentException(
          "OrcaRouter API key is not set. Set the " + API_KEY_ENV_VAR + " environment variable.");
    }
    String baseUrl = System.getenv(BASE_URL_ENV_VAR);
    if (isNullOrEmpty(baseUrl)) {
      baseUrl = DEFAULT_BASE_URL;
    }
    HttpOptions httpOptions =
        HttpOptions.builder()
            .baseUrl(baseUrl)
            .headers(ImmutableMap.of("Authorization", "Bearer " + apiKey))
            .build();
    logger.debug("OrcaRouterLlm constructed with baseUrl={}", baseUrl);
    return new ChatCompletionsHttpClient(httpOptions);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    String modelToUse = llmRequest.model().orElse(model());
    LlmRequest newLlmRequest = llmRequest.toBuilder().model(validateModelName(modelToUse)).build();
    return chatCompletionsClient.complete(newLlmRequest, stream);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "Streaming connections are not supported for OrcaRouter models.");
  }

  private static String validateModelName(@Nullable String modelName) {
    if (isNullOrEmpty(modelName)
        || !modelName.startsWith(MODEL_PREFIX)
        || modelName.length() == MODEL_PREFIX.length()) {
      throw new IllegalArgumentException(
          "Invalid OrcaRouter model name, expected orcarouter/<model>: " + modelName);
    }
    return modelName;
  }
}
