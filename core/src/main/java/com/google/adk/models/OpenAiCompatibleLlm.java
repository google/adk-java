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

import com.google.adk.models.chat.ChatCompletionsHttpClient;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Objects;

/**
 * Represents any OpenAI-compatible LLM endpoint.
 *
 * <p>This class enables ADK agents to connect to any LLM provider that implements the OpenAI Chat
 * Completions API format, including:
 *
 * <ul>
 *   <li>Groq
 *   <li>Ollama (local models)
 *   <li>Azure OpenAI
 *   <li>Perplexity
 *   <li>Any custom endpoint following OpenAI's API schema
 * </ul>
 *
 * <p>Example usage with Groq:
 *
 * <pre>{@code
 * OpenAiCompatibleLlm groq = OpenAiCompatibleLlm.builder()
 *     .baseUrl("https://api.groq.com/openai/v1/")
 *     .headers(ImmutableMap.of("Authorization", "Bearer " + apiKey))
 *     .modelName("groq-llama3-70b")
 *     .build();
 * groq.registerWithPattern("groq-.*");
 *
 * LlmAgent agent = LlmAgent.builder()
 *     .model("groq-llama3-70b")
 *     .instruction("You are a helpful assistant.")
 *     .build();
 * }</pre>
 *
 * <p>Example usage with Ollama (local):
 *
 * <pre>{@code
 * OpenAiCompatibleLlm ollama = OpenAiCompatibleLlm.builder()
 *     .baseUrl("http://localhost:11434/v1/")
 *     .headers(ImmutableMap.of())
 *     .modelName("ollama-llama2")
 *     .build();
 * ollama.registerWithPattern("ollama-.*");
 * }</pre>
 *
 * <p><b>Note:</b> Streaming support depends on {@link ChatCompletionsHttpClient} implementation.
 * Currently non-streaming only. Live bidirectional connections are not supported as the OpenAI Chat
 * Completions API does not provide this capability.
 */
public class OpenAiCompatibleLlm extends BaseLlm {

  private final ChatCompletionsHttpClient client;

  /**
   * Constructs a new OpenAiCompatibleLlm instance.
   *
   * @param modelName The model name for registry identification
   * @param client The HTTP client configured for the endpoint
   */
  private OpenAiCompatibleLlm(String modelName, ChatCompletionsHttpClient client) {
    super(modelName);
    this.client = Objects.requireNonNull(client, "client cannot be null");
  }

  /**
   * Creates a new builder for configuring an OpenAI-compatible LLM.
   *
   * @return a new builder instance
   */
  public static Builder builder() {
    return new Builder();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    return client.complete(llmRequest, stream);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        "Live bidirectional connections are not supported for OpenAI-compatible HTTP endpoints. "
            + "The OpenAI Chat Completions API does not provide live connection capabilities.");
  }

  /**
   * Convenience method to register this LLM with a pattern in {@link LlmRegistry}.
   *
   * <p>This allows agents to reference the model by name pattern. For example:
   *
   * <pre>{@code
   * llm.registerWithPattern("groq-.*");
   * // Now agents can use model names like "groq-llama3-70b", "groq-mixtral", etc.
   * }</pre>
   *
   * @param pattern regex pattern for model name matching (e.g., "groq-.*", "ollama-.*")
   */
  public void registerWithPattern(String pattern) {
    LlmRegistry.registerLlm(pattern, modelName -> this);
  }

  /** Builder for {@link OpenAiCompatibleLlm}. */
  public static class Builder {
    private String baseUrl;
    private ImmutableMap<String, String> headers = ImmutableMap.of();
    private int timeoutMillis = 300_000; // 5 minutes default
    private String modelName;

    private Builder() {}

    /**
     * Sets the base URL of the OpenAI-compatible endpoint.
     *
     * <p>The base URL should end with the API version path (e.g., "/v1/"). The client will
     * automatically append "/chat/completions" to form the complete endpoint URL.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>Groq: {@code "https://api.groq.com/openai/v1/"}
     *   <li>Ollama: {@code "http://localhost:11434/v1/"}
     *   <li>Azure OpenAI: {@code
     *       "https://<resource>.openai.azure.com/openai/deployments/<deployment>/"}
     * </ul>
     *
     * @param url the base URL (required)
     * @return this builder
     */
    public Builder baseUrl(String url) {
      this.baseUrl = url;
      return this;
    }

    /**
     * Sets custom HTTP headers to include in all requests.
     *
     * <p>Typically used for authorization headers. Example:
     *
     * <pre>{@code
     * .headers(ImmutableMap.of("Authorization", "Bearer " + apiKey))
     * }</pre>
     *
     * @param headers map of header names to values
     * @return this builder
     */
    public Builder headers(ImmutableMap<String, String> headers) {
      this.headers = Objects.requireNonNull(headers, "headers cannot be null");
      return this;
    }

    /**
     * Sets custom HTTP headers from a regular map.
     *
     * @param headers map of header names to values
     * @return this builder
     */
    public Builder headers(Map<String, String> headers) {
      return headers(ImmutableMap.copyOf(headers));
    }

    /**
     * Sets the request timeout in milliseconds.
     *
     * <p>Defaults to 300,000ms (5 minutes) if not specified. Set to 0 for infinite timeout (not
     * recommended for production).
     *
     * @param millis timeout in milliseconds
     * @return this builder
     */
    public Builder timeoutMillis(int millis) {
      this.timeoutMillis = millis;
      return this;
    }

    /**
     * Sets the model name used for registry pattern matching.
     *
     * @param name the model name (e.g., "groq-llama3-70b")
     * @return this builder
     */
    public Builder modelName(String name) {
      this.modelName = name;
      return this;
    }

    /**
     * Builds the {@link OpenAiCompatibleLlm} instance.
     *
     * @return a configured OpenAiCompatibleLlm instance
     * @throws IllegalArgumentException if baseUrl or modelName is not set, or if baseUrl is invalid
     */
    public OpenAiCompatibleLlm build() {
      Objects.requireNonNull(baseUrl, "baseUrl must be set");
      Objects.requireNonNull(modelName, "modelName must be set");

      HttpOptions httpOptions =
          HttpOptions.builder().baseUrl(baseUrl).headers(headers).timeout(timeoutMillis).build();

      ChatCompletionsHttpClient httpClient = new ChatCompletionsHttpClient(httpOptions);

      return new OpenAiCompatibleLlm(modelName, httpClient);
    }
  }
}
