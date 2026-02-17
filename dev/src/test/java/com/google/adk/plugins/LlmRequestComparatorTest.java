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
package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;

/** Tests for {@link LlmRequestComparator}. */
class LlmRequestComparatorTest {

  private final LlmRequestComparator comparator = new LlmRequestComparator();

  // Standard base request used by all tests
  private static final LlmRequest BASE_REQUEST =
      LlmRequest.builder()
          .model("gemini-2.0-flash")
          .contents(ImmutableList.of(userContent("Hello")))
          .config(GenerateContentConfig.builder().temperature(0.5f).build())
          .build();

  private static Content userContent(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  @Test
  void equals_identicalRequests_returnsTrue() {
    LlmRequest request1 = BASE_REQUEST;
    LlmRequest request2 = BASE_REQUEST.toBuilder().build();

    assertThat(comparator.equals(request1, request2)).isTrue();
  }

  @Test
  void equals_differentModels_returnsFalse() {
    LlmRequest request1 = BASE_REQUEST;
    LlmRequest request2 = BASE_REQUEST.toBuilder().model("gemini-1.5-pro").build();

    assertThat(comparator.equals(request1, request2)).isFalse();
  }

  @Test
  void equals_differentContents_returnsFalse() {
    LlmRequest request1 = BASE_REQUEST;
    LlmRequest request2 =
        BASE_REQUEST.toBuilder().contents(ImmutableList.of(userContent("Goodbye"))).build();

    assertThat(comparator.equals(request1, request2)).isFalse();
  }

  @Test
  void equals_differentSystemInstructions_returnsFalse() {
    LlmRequest request1 =
        BASE_REQUEST.toBuilder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(userContent("Be helpful"))
                    .build())
            .build();

    LlmRequest request2 =
        BASE_REQUEST.toBuilder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(userContent("Be concise"))
                    .build())
            .build();

    assertThat(comparator.equals(request1, request2)).isFalse();
  }

  @Test
  void equals_differentHttpOptions_returnsTrue() {
    // httpOptions should be excluded from comparison
    LlmRequest request1 =
        BASE_REQUEST.toBuilder()
            .config(
                BASE_REQUEST.config().get().toBuilder()
                    .httpOptions(HttpOptions.builder().timeout(1000).build())
                    .build())
            .build();

    LlmRequest request2 =
        BASE_REQUEST.toBuilder()
            .config(
                BASE_REQUEST.config().get().toBuilder()
                    .httpOptions(HttpOptions.builder().timeout(5000).build())
                    .build())
            .build();

    assertThat(comparator.equals(request1, request2)).isTrue();
  }

  @Test
  void equals_differentLabels_returnsTrue() {
    // labels should be excluded from comparison
    LlmRequest request1 =
        BASE_REQUEST.toBuilder()
            .config(
                BASE_REQUEST.config().get().toBuilder()
                    .labels(ImmutableMap.of("env", "dev"))
                    .build())
            .build();

    LlmRequest request2 =
        BASE_REQUEST.toBuilder()
            .config(
                BASE_REQUEST.config().get().toBuilder()
                    .labels(ImmutableMap.of("env", "prod"))
                    .build())
            .build();

    assertThat(comparator.equals(request1, request2)).isTrue();
  }

  @Test
  void equals_differentLiveConnectConfig_returnsTrue() {
    // liveConnectConfig should be excluded from comparison
    LlmRequest request1 =
        BASE_REQUEST.toBuilder()
            .liveConnectConfig(
                LiveConnectConfig.builder().systemInstruction(userContent("Live config 1")).build())
            .build();

    LlmRequest request2 =
        BASE_REQUEST.toBuilder()
            .liveConnectConfig(
                LiveConnectConfig.builder().systemInstruction(userContent("Live config 2")).build())
            .build();

    assertThat(comparator.equals(request1, request2)).isTrue();
  }

  @Test
  void equals_sameRequestDifferentOnlyInExcludedFields_returnsTrue() {
    // All excluded fields differ, but core fields are the same
    LlmRequest request1 =
        BASE_REQUEST.toBuilder()
            .config(
                BASE_REQUEST.config().get().toBuilder()
                    .httpOptions(HttpOptions.builder().timeout(1000).build())
                    .labels(ImmutableMap.of("env", "dev"))
                    .build())
            .liveConnectConfig(
                LiveConnectConfig.builder().systemInstruction(userContent("Live 1")).build())
            .build();

    LlmRequest request2 =
        BASE_REQUEST.toBuilder()
            .config(
                BASE_REQUEST.config().get().toBuilder()
                    .httpOptions(HttpOptions.builder().timeout(9999).build())
                    .labels(ImmutableMap.of("env", "prod", "version", "v2"))
                    .build())
            .liveConnectConfig(
                LiveConnectConfig.builder().systemInstruction(userContent("Live 2")).build())
            .build();

    assertThat(comparator.equals(request1, request2)).isTrue();
  }
}
