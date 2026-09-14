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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link BaseLlm} for any endpoint implementing the OpenAI Chat Completions API format (Groq,
 * Ollama, OpenRouter, Azure OpenAI, vLLM, and others).
 *
 * <p>HTTP transport and JSON mapping are delegated entirely to ADK's native {@link
 * ChatCompletionsHttpClient}. Instances are immutable: the underlying client (including its {@code
 * Authorization} header) is built once at construction.
 *
 * <p>Instances are constructed with the <em>bare</em> model identifier, which is what the backend
 * receives as the wire-format {@code "model"} field — any routing prefix such as {@code "groq/"}
 * must be stripped by the caller before construction (see {@link ModelProvider#create(String)}).
 *
 * <p>Example, together with the {@link ModelProvider} SPI:
 *
 * <pre>{@code
 * public final class GroqModelProvider implements ModelProvider {
 *   @Override
 *   public String prefix() {
 *     return "groq";
 *   }
 *
 *   @Override
 *   public BaseLlm createFromBareModelName(String bareModelName) {
 *     return new OpenAiCompatibleLlm(
 *         bareModelName,
 *         "https://api.groq.com/openai/v1",
 *         Optional.ofNullable(System.getenv("GROQ_API_KEY")));
 *   }
 * }
 * }</pre>
 *
 * <p>Live bidirectional connections are not supported; the Chat Completions API does not provide
 * this capability.
 */
public class OpenAiCompatibleLlm extends BaseLlm {

  private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

  private final ChatCompletionsClient client;

  /**
   * Creates a new OpenAI-compatible LLM.
   *
   * @param modelName the bare model name, sent to the backend as the {@code "model"} field
   * @param apiUrl the URL of the chat-completions endpoint; a trailing {@code /chat/completions}
   *     segment is accepted and stripped, since the client appends it internally
   * @param apiKey optional API key; if empty, no {@code Authorization} header is sent
   */
  public OpenAiCompatibleLlm(String modelName, String apiUrl, Optional<String> apiKey) {
    this(modelName, createClient(apiUrl, apiKey));
  }

  @VisibleForTesting
  OpenAiCompatibleLlm(String modelName, ChatCompletionsClient client) {
    super(requireNotBlank(modelName, "modelName cannot be blank"));
    this.client = checkNotNull(client, "client");
  }

  private static ChatCompletionsHttpClient createClient(String apiUrl, Optional<String> apiKey) {
    requireNotBlank(apiUrl, "apiUrl cannot be blank");
    HttpOptions.Builder optionsBuilder = HttpOptions.builder().baseUrl(normalizeBaseUrl(apiUrl));
    apiKey.ifPresent(key -> optionsBuilder.headers(Map.of("Authorization", "Bearer " + key)));
    return new ChatCompletionsHttpClient(optionsBuilder.build());
  }

  /**
   * Strips a trailing {@code /chat/completions} segment, which {@link ChatCompletionsHttpClient}
   * appends internally.
   */
  @VisibleForTesting
  static String normalizeBaseUrl(String apiUrl) {
    if (apiUrl.endsWith(CHAT_COMPLETIONS_PATH)) {
      return apiUrl.substring(0, apiUrl.length() - CHAT_COMPLETIONS_PATH.length());
    }
    return apiUrl;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return client.complete(llmRequest, stream);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "OpenAiCompatibleLlm does not support live bidirectional connections.");
  }

  private static String requireNotBlank(String s, String errorMessage) {
    checkArgument(!Strings.nullToEmpty(s).isBlank(), errorMessage);
    return s;
  }
}
