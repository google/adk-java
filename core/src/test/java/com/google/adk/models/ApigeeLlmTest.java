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
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Objects;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ApigeeLlmTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private Gemini mockGeminiDelegate;

  private static final String PROXY_URL = "https://test.apigee.net";

  @Before
  public void checkApiKey() {
    assumeNotNull(System.getenv("GOOGLE_API_KEY"));
  }

  @Test
  public void build_withValidModelStrings_succeeds() {
    String[] validModelStrings = {
      "apigee/whatever-model",
      "apigee/v1/whatever-model",
      "apigee/vertex_ai/whatever-model",
      "apigee/gemini/v1/whatever-model",
      "apigee/vertex_ai/v1beta/whatever-model"
    };

    for (String modelName : validModelStrings) {
      ApigeeLlm llm = ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL).build();
      assertThat(llm).isNotNull();
    }
  }

  @Test
  public void build_withInvalidModelStrings_throwsException() {
    String[] invalidModelStrings = {
      "apigee/openai/v1/gpt",
      "apigee/",
      "apigee",
      "gemini-pro",
      "apigee/vertex_ai/v1/model/extra",
      "apigee/unknown/model",
      "apigee/gemini//"
    };

    for (String modelName : invalidModelStrings) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL).build());
    }
  }

  @Test
  public void generateContent_stripsApigeePrefixAndSendsToDelegate() {
    when(mockGeminiDelegate.generateContent(any(), anyBoolean())).thenReturn(Flowable.empty());

    ApigeeLlm llm = new ApigeeLlm("apigee/gemini/v1/whatever-model", mockGeminiDelegate);

    LlmRequest request =
        LlmRequest.builder()
            .model("apigee/gemini/v1/whatever-model")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hi")).build()))
            .build();
    llm.generateContent(request, true).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockGeminiDelegate).generateContent(requestCaptor.capture(), eq(true));
    assertThat(requestCaptor.getValue().model()).hasValue("whatever-model");
  }

  // Add a test to verify the vertexAI flag is set correctly.
  @Test
  public void generateContent_setsVertexAiFlagCorrectly_withVertexAi() {
    ApigeeLlm llm =
        ApigeeLlm.builder()
            .modelName("apigee/vertex_ai/whatever-model")
            .proxyUrl(PROXY_URL)
            .build();
    assertThat(llm.getApiClient().vertexAI()).isTrue();
  }

  @Test
  public void generateContent_setsVertexAiFlagCorrectly_withOrWithoutVertexAi() {

    ApigeeLlm llm =
        ApigeeLlm.builder().modelName("apigee/whatever-model").proxyUrl(PROXY_URL).build();
    String useVertexAi = System.getenv("GOOGLE_GENAI_USE_VERTEXAI");

    if (Objects.equals(useVertexAi, "true") || Objects.equals(useVertexAi, "1")) {
      assertThat(llm.getApiClient().vertexAI()).isTrue();
    } else {
      assertThat(llm.getApiClient().vertexAI()).isFalse();
    }
  }

  @Test
  public void generateContent_setsVertexAiFlagCorrectly_withGemini() {
    ApigeeLlm llm =
        ApigeeLlm.builder().modelName("apigee/gemini/whatever-model").proxyUrl(PROXY_URL).build();
    assertThat(llm.getApiClient().vertexAI()).isFalse();
  }

  // Add a test to verify the api version is set correctly.
  @Test
  public void generateContent_setsApiVersionCorrectly() {
    ImmutableMap<String, String> modelToApiVersion =
        ImmutableMap.of(
            "apigee/whatever-model", "",
            "apigee/v1/whatever-model", "v1",
            "apigee/vertex_ai/whatever-model", "",
            "apigee/gemini/v1/whatever-model", "v1",
            "apigee/vertex_ai/v1beta/whatever-model", "v1beta");

    for (Map.Entry<String, String> entry : modelToApiVersion.entrySet()) {
      String modelName = entry.getKey();
      String expectedApiVersion = entry.getValue();
      ApigeeLlm llm = ApigeeLlm.builder().modelName(modelName).proxyUrl(PROXY_URL).build();
      if (expectedApiVersion.isEmpty()) {
        assertThat(llm.getHttpOptions().apiVersion()).isEmpty();
      } else {
        assertThat(llm.getHttpOptions().apiVersion()).hasValue(expectedApiVersion);
      }
    }
  }

  @Test
  public void build_withCustomHeaders_setsHeadersInHttpOptions() {
    ImmutableMap<String, String> customHeaders = ImmutableMap.of("X-Test-Header", "TestValue");
    ApigeeLlm llm =
        ApigeeLlm.builder()
            .modelName("apigee/whatever-model")
            .proxyUrl(PROXY_URL)
            .customHeaders(customHeaders)
            .build();
    assertThat(llm.getHttpOptions().headers().get()).containsKey("X-Test-Header");
    assertThat(llm.getHttpOptions().headers().get()).containsEntry("X-Test-Header", "TestValue");
    // Also check for tracking headers
    assertThat(llm.getHttpOptions().headers().get()).containsKey("x-goog-api-client");
    assertThat(llm.getHttpOptions().headers().get()).containsKey("user-agent");
  }

  @Test
  public void build_withTrailingSlashInModel_parsesVersionAndModelId() {
    when(mockGeminiDelegate.generateContent(any(), anyBoolean())).thenReturn(Flowable.empty());
    ApigeeLlm llm = new ApigeeLlm("apigee/gemini/v1/", mockGeminiDelegate);
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hi")).build()))
            .build();
    assertThrows(IllegalArgumentException.class, () -> llm.generateContent(request, false));
    verify(mockGeminiDelegate, never()).generateContent(any(), anyBoolean());
  }

  @Test
  public void build_withoutProxyUrl_readsFromEnvironment() {
    String envProxyUrl = System.getenv("APIGEE_PROXY_URL");
    if (envProxyUrl != null) {
      ApigeeLlm llm = ApigeeLlm.builder().modelName("apigee/whatever-model").build();
      assertThat(llm.getHttpOptions().baseUrl()).hasValue(envProxyUrl);
    } else {
      assertThrows(
          IllegalArgumentException.class,
          () -> ApigeeLlm.builder().modelName("apigee/whatever-model").build());
      ApigeeLlm llm =
          ApigeeLlm.builder().proxyUrl(PROXY_URL).modelName("apigee/whatever-model").build();
      assertThat(llm.getHttpOptions().baseUrl()).hasValue(PROXY_URL);
    }
  }
}
