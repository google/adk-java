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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the only reason this record has two factories instead of one.
 *
 * <p>adk-python records <em>different subsets</em> of the same token counts in the two places it
 * reports them: three for an event in {@code on_event_callback}, and four for an LLM response in
 * {@code after_model_callback}, adding {@code cached_content_token_count}. {@link
 * UsageTrace#fromEvent} reproduces that by leaving the cached count absent.
 *
 * <p>Both factories are given the <b>same</b> fully-populated metadata, so the only thing that can
 * make the two expectations differ is the factory itself. Without this, collapsing the pair into
 * one method would silently diverge from adk-python and leave every other test green.
 */
@RunWith(JUnit4.class)
public class UsageTraceTest {

  private static final GenerateContentResponseUsageMetadata USAGE =
      GenerateContentResponseUsageMetadata.builder()
          .promptTokenCount(120)
          .candidatesTokenCount(45)
          .totalTokenCount(165)
          .cachedContentTokenCount(80)
          .build();

  private final ObjectMapper mapper = DebugYamlWriter.configure(new ObjectMapper());

  @Test
  public void fromEvent_recordsThreeCountsAndOmitsTheCachedOne() throws Exception {
    assertThat(mapper.writeValueAsString(UsageTrace.fromEvent(USAGE)))
        .isEqualTo(
            "{\"prompt_token_count\":120,\"candidates_token_count\":45,"
                + "\"total_token_count\":165}");
  }

  @Test
  public void fromResponse_addsTheCachedContentCount() throws Exception {
    assertThat(mapper.writeValueAsString(UsageTrace.fromResponse(USAGE)))
        .isEqualTo(
            "{\"prompt_token_count\":120,\"candidates_token_count\":45,"
                + "\"total_token_count\":165,\"cached_content_token_count\":80}");
  }

  /** Absent counts are omitted rather than written as nulls, in both shapes. */
  @Test
  public void fromResponse_withNoCountsAtAll_writesAnEmptyMapping() throws Exception {
    GenerateContentResponseUsageMetadata empty =
        GenerateContentResponseUsageMetadata.builder().build();

    assertThat(mapper.writeValueAsString(UsageTrace.fromResponse(empty))).isEqualTo("{}");
  }
}
