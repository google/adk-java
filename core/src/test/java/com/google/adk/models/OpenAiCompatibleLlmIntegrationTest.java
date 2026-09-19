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
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for {@link OpenAiCompatibleLlm} against real endpoints.
 *
 * <p>These tests require external services to be running. To separate from fast unit tests, run
 * these manually or configure your build to skip them in CI.
 *
 * <p><b>Prerequisites:</b>
 *
 * <ul>
 *   <li>Ollama tests: Ollama must be running locally on port 11434 with a model pulled (e.g.,
 *       {@code ollama pull llama2})
 * </ul>
 */
@RunWith(JUnit4.class)
public final class OpenAiCompatibleLlmIntegrationTest {

  private static final String OLLAMA_BASE_URL = "http://localhost:11434/v1/";
  private static final String OLLAMA_MODEL = "llama2";

  @Before
  public void checkOllamaAvailable() {
    assumeTrue(
        "Ollama is not running on localhost:11434. "
            + "Start Ollama and pull a model (e.g., 'ollama pull llama2') to run this test.",
        isOllamaRunning());
  }

  @Test
  public void testOllamaSimpleCompletion() {
    OpenAiCompatibleLlm ollama =
        OpenAiCompatibleLlm.builder()
            .baseUrl(OLLAMA_BASE_URL)
            .headers(ImmutableMap.of()) // Ollama doesn't require auth for local
            .modelName(OLLAMA_MODEL)
            .timeoutMillis(30_000) // 30 seconds for slower local models
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(Content.fromParts(Part.fromText("Say 'hello' and nothing else."))))
            .build();

    LlmResponse response = ollama.generateContent(request, false).blockingFirst();

    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).isNotEmpty();
    String responseText =
        response.content().get().parts().get().get(0).text().orElse("").toLowerCase();
    assertThat(responseText).contains("hello");
  }

  @Test
  public void testOllamaMultiTurnConversation() {
    OpenAiCompatibleLlm ollama =
        OpenAiCompatibleLlm.builder()
            .baseUrl(OLLAMA_BASE_URL)
            .headers(ImmutableMap.of())
            .modelName(OLLAMA_MODEL)
            .timeoutMillis(30_000)
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.fromParts(Part.fromText("What is 2+2?")),
                    Content.fromParts(Part.fromText("2+2 equals 4.")),
                    Content.fromParts(Part.fromText("What about 3+3?"))))
            .build();

    LlmResponse response = ollama.generateContent(request, false).blockingFirst();

    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).isNotEmpty();
    String responseText = response.content().get().parts().get().get(0).text().orElse("");
    assertThat(responseText).containsMatch("[6|six]"); // Could be "6" or "six"
  }

  @Test
  public void testOllamaWithCustomTimeout() {
    OpenAiCompatibleLlm ollama =
        OpenAiCompatibleLlm.builder()
            .baseUrl(OLLAMA_BASE_URL)
            .headers(ImmutableMap.of())
            .modelName(OLLAMA_MODEL)
            .timeoutMillis(60_000) // 1 minute for slower prompts
            .build();

    LlmRequest request =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    Content.fromParts(Part.fromText("List 3 colors, comma separated."))))
            .build();

    LlmResponse response = ollama.generateContent(request, false).blockingFirst();

    assertThat(response.content()).isPresent();
    assertThat(response.content().get().parts()).isPresent();
    assertThat(response.content().get().parts().get()).isNotEmpty();
  }

  @Test
  public void testOllamaRegistryIntegration() {
    OpenAiCompatibleLlm ollama =
        OpenAiCompatibleLlm.builder()
            .baseUrl(OLLAMA_BASE_URL)
            .headers(ImmutableMap.of())
            .modelName("ollama-test")
            .timeoutMillis(30_000)
            .build();

    ollama.registerWithPattern("ollama-test-.*");

    // Verify registry resolution works
    BaseLlm resolved = LlmRegistry.getLlm("ollama-test-model");
    assertThat(resolved).isNotNull();
    assertThat(resolved).isInstanceOf(OpenAiCompatibleLlm.class);

    // Verify the resolved model can make requests
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(Content.fromParts(Part.fromText("Say 'test'."))))
            .build();

    LlmResponse response = resolved.generateContent(request, false).blockingFirst();
    assertThat(response.content()).isPresent();
  }

  /**
   * Checks if Ollama is accessible at the expected URL.
   *
   * @return true if Ollama is running and responsive, false otherwise
   */
  private static boolean isOllamaRunning() {
    try {
      URL url = new URL("http://localhost:11434/api/tags");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(2000);
      connection.setReadTimeout(2000);
      int responseCode = connection.getResponseCode();
      return responseCode == 200;
    } catch (IOException e) {
      return false;
    }
  }
}
