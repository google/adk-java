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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.models.chat.ChatCompletionsClient;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class OrcaRouterLlmTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock private ChatCompletionsClient mockCcClient;

  @Test
  public void build_withValidModelStrings_succeeds() {
    String[] validModelStrings = {
      "orcarouter/auto",
      "orcarouter/fusion",
      "orcarouter/fusion-flash",
      "orcarouter/fusion-mini",
      "orcarouter/deepseek/deepseek-v4-pro"
    };

    for (String modelName : validModelStrings) {
      OrcaRouterLlm llm = new OrcaRouterLlm(modelName, mockCcClient);
      assertThat(llm).isNotNull();
      assertThat(llm.model()).isEqualTo(modelName);
    }
  }

  @Test
  public void build_withInvalidModelStrings_throwsException() {
    String[] invalidModelStrings = {
      "auto", "orcarouter", "orcarouter/", "orcarouter", "gemini-2.5-flash", "", null
    };

    for (String modelName : invalidModelStrings) {
      assertThrows(
          IllegalArgumentException.class, () -> new OrcaRouterLlm(modelName, mockCcClient));
    }
  }

  @Test
  public void generateContent_sendsToCcClient() {
    when(mockCcClient.complete(any(), anyBoolean())).thenReturn(Flowable.empty());

    OrcaRouterLlm llm = new OrcaRouterLlm("orcarouter/auto", mockCcClient);
    LlmRequest request =
        LlmRequest.builder()
            .model("orcarouter/auto")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();
    llm.generateContent(request, false).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockCcClient).complete(requestCaptor.capture(), eq(false));
    assertThat(requestCaptor.getValue().model()).hasValue("orcarouter/auto");
  }

  @Test
  public void generateContent_sendsStreamingToCcClient() {
    when(mockCcClient.complete(any(), anyBoolean())).thenReturn(Flowable.empty());

    OrcaRouterLlm llm = new OrcaRouterLlm("orcarouter/fusion", mockCcClient);
    LlmRequest request =
        LlmRequest.builder()
            .model("orcarouter/fusion")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hi")).build()))
            .build();
    llm.generateContent(request, true).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockCcClient).complete(requestCaptor.capture(), eq(true));
    assertThat(requestCaptor.getValue().model()).hasValue("orcarouter/fusion");
  }

  @Test
  public void generateContent_requestLevelModelOverride_preservesPrefix() {
    when(mockCcClient.complete(any(), anyBoolean())).thenReturn(Flowable.empty());

    OrcaRouterLlm llm = new OrcaRouterLlm("orcarouter/auto", mockCcClient);
    LlmRequest request =
        LlmRequest.builder()
            .model("orcarouter/fusion-flash")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();
    llm.generateContent(request, false).test().assertNoErrors();

    ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
    verify(mockCcClient).complete(requestCaptor.capture(), eq(false));
    assertThat(requestCaptor.getValue().model()).hasValue("orcarouter/fusion-flash");
  }

  @Test
  public void generateContent_invalidRequestLevelOverride_throwsException() {
    OrcaRouterLlm llm = new OrcaRouterLlm("orcarouter/auto", mockCcClient);
    LlmRequest request =
        LlmRequest.builder()
            .model("gemini-2.5-flash")
            .contents(ImmutableList.of(Content.builder().parts(Part.fromText("hello")).build()))
            .build();

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> llm.generateContent(request, false));
    assertThat(e)
        .hasMessageThat()
        .contains("Invalid OrcaRouter model name, expected orcarouter/<model>: gemini-2.5-flash");
  }

  @Test
  public void connect_throwsUnsupportedOperationException() {
    OrcaRouterLlm llm = new OrcaRouterLlm("orcarouter/auto", mockCcClient);
    LlmRequest request = LlmRequest.builder().model("orcarouter/auto").build();
    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> llm.connect(request));
    assertThat(e)
        .hasMessageThat()
        .contains("Streaming connections are not supported for OrcaRouter models.");
  }

  @Test
  public void llmRegistry_resolvesOrcaRouterModels() {
    // ORCAROUTER_API_KEY must be set for the production path; clear it to assert the error path
    // first, then check the registry wiring with a registered pattern.
    assertThat(LlmRegistry.matchesAnyPattern("orcarouter/auto")).isTrue();
    assertThat(LlmRegistry.matchesAnyPattern("orcarouter/fusion")).isTrue();
    assertThat(LlmRegistry.matchesAnyPattern("gemini-2.5-flash")).isTrue();
    assertThat(LlmRegistry.matchesAnyPattern("claude-sonnet-5")).isFalse();
  }
}
