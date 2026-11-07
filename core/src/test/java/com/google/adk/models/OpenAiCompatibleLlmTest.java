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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.models.chat.ChatCompletionsClient;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link OpenAiCompatibleLlm}. */
@RunWith(JUnit4.class)
public final class OpenAiCompatibleLlmTest {

  @Test
  public void normalizeBaseUrl_stripsChatCompletionsSuffix() {
    assertThat(OpenAiCompatibleLlm.normalizeBaseUrl("http://host/v1/chat/completions"))
        .isEqualTo("http://host/v1");
  }

  @Test
  public void normalizeBaseUrl_keepsUrlWithoutSuffix() {
    assertThat(OpenAiCompatibleLlm.normalizeBaseUrl("http://host/v1")).isEqualTo("http://host/v1");
  }

  @Test
  public void generateContent_delegatesToClient() {
    ChatCompletionsClient client = mock(ChatCompletionsClient.class);
    when(client.complete(any(), anyBoolean())).thenReturn(Flowable.empty());
    OpenAiCompatibleLlm llm = new OpenAiCompatibleLlm("some-model", client);
    LlmRequest request = LlmRequest.builder().build();

    llm.generateContent(request, true).test().assertComplete();

    verify(client).complete(request, true);
  }

  @Test
  public void model_isTheBareNamePassedAtConstruction() {
    OpenAiCompatibleLlm llm =
        new OpenAiCompatibleLlm("some-model", mock(ChatCompletionsClient.class));

    assertThat(llm.model()).isEqualTo("some-model");
  }

  @Test
  public void constructor_blankModelName_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new OpenAiCompatibleLlm(" ", mock(ChatCompletionsClient.class)));
  }

  @Test
  public void connect_isUnsupported() {
    OpenAiCompatibleLlm llm =
        new OpenAiCompatibleLlm("some-model", mock(ChatCompletionsClient.class));

    assertThrows(
        UnsupportedOperationException.class, () -> llm.connect(LlmRequest.builder().build()));
  }
}
