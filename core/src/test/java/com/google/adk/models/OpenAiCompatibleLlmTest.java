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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class OpenAiCompatibleLlmTest {

  @Test
  public void builderRequiresBaseUrl() {
    NullPointerException exception =
        assertThrows(
            NullPointerException.class,
            () ->
                OpenAiCompatibleLlm.builder()
                    .modelName("test-model")
                    .headers(ImmutableMap.of("Authorization", "Bearer token"))
                    .build());

    assertThat(exception.getMessage()).contains("baseUrl");
  }

  @Test
  public void builderAcceptsValidBaseUrl() {
    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .build();

    assertThat(llm.model()).isEqualTo("test-model");
  }

  @Test
  public void builderRejectsInvalidBaseUrl() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                OpenAiCompatibleLlm.builder()
                    .baseUrl("not-a-valid-url")
                    .modelName("test-model")
                    .build());

    assertThat(exception.getMessage()).contains("not a valid HTTP(S) URL");
  }

  @Test
  public void builderAcceptsCustomTimeout() {
    // Just verify the builder accepts timeout without error
    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .timeoutMillis(60_000)
            .build();

    assertThat(llm.model()).isEqualTo("test-model");
  }

  @Test
  public void builderAcceptsHeadersAsImmutableMap() {
    ImmutableMap<String, String> headers =
        ImmutableMap.of(
            "Authorization", "Bearer test-token",
            "X-Custom-Header", "custom-value");

    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .headers(headers)
            .build();

    // Headers are internal, just verify build succeeds
    assertThat(llm.model()).isEqualTo("test-model");
  }

  @Test
  public void builderAcceptsHeadersAsMutableMap() {
    Map<String, String> headers =
        Map.of(
            "Authorization", "Bearer test-token",
            "X-Custom-Header", "custom-value");

    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .headers(headers)
            .build();

    // Headers are internal, just verify build succeeds
    assertThat(llm.model()).isEqualTo("test-model");
  }

  @Test
  public void modelNameIsAccessible() {
    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("groq-llama3-70b")
            .build();

    assertThat(llm.model()).isEqualTo("groq-llama3-70b");
  }

  @Test
  public void connectThrowsUnsupportedOperationException() {
    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(Content.fromParts(Part.fromText("test"))))
            .build();

    UnsupportedOperationException exception =
        assertThrows(UnsupportedOperationException.class, () -> llm.connect(request));

    assertThat(exception.getMessage()).contains("Live bidirectional connections are not supported");
  }

  @Test
  public void registerWithPatternRegistersInLlmRegistry() {
    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .build();

    llm.registerWithPattern("test-.*");

    // Verify the model can be resolved from registry
    BaseLlm resolvedLlm = LlmRegistry.getLlm("test-anything");
    assertThat(resolvedLlm).isNotNull();
    assertThat(resolvedLlm).isInstanceOf(OpenAiCompatibleLlm.class);
  }

  @Test
  public void registerWithPatternAllowsMultipleModels() {
    OpenAiCompatibleLlm groq =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.groq.com/openai/v1/")
            .modelName("groq-base")
            .build();
    groq.registerWithPattern("groq-.*");

    OpenAiCompatibleLlm ollama =
        OpenAiCompatibleLlm.builder()
            .baseUrl("http://localhost:11434/v1/")
            .modelName("ollama-base")
            .build();
    ollama.registerWithPattern("ollama-.*");

    // Both should resolve correctly
    BaseLlm groqResolved = LlmRegistry.getLlm("groq-llama3");
    BaseLlm ollamaResolved = LlmRegistry.getLlm("ollama-llama2");

    assertThat(groqResolved).isInstanceOf(OpenAiCompatibleLlm.class);
    assertThat(ollamaResolved).isInstanceOf(OpenAiCompatibleLlm.class);
  }

  @Test
  public void builderExampleFromJavadocGroq() {
    // This test verifies the example code in the class Javadoc compiles and runs
    OpenAiCompatibleLlm groq =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.groq.com/openai/v1/")
            .headers(ImmutableMap.of("Authorization", "Bearer fake-key"))
            .modelName("groq-llama3-70b")
            .build();

    assertThat(groq.model()).isEqualTo("groq-llama3-70b");
  }

  @Test
  public void builderExampleFromJavadocOllama() {
    // This test verifies the example code in the class Javadoc compiles and runs
    OpenAiCompatibleLlm ollama =
        OpenAiCompatibleLlm.builder()
            .baseUrl("http://localhost:11434/v1/")
            .headers(ImmutableMap.of())
            .modelName("ollama-llama2")
            .build();

    assertThat(ollama.model()).isEqualTo("ollama-llama2");
  }

  @Test
  public void clientIsInitializedAfterBuild() {
    OpenAiCompatibleLlm llm =
        OpenAiCompatibleLlm.builder()
            .baseUrl("https://api.example.com/v1/")
            .modelName("test-model")
            .build();

    // Verify the LLM was constructed successfully by checking model name
    assertThat(llm.model()).isEqualTo("test-model");
  }
}
