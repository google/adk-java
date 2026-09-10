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
package com.google.adk.reasoning;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LlmMemoryExtractor}. */
@RunWith(JUnit4.class)
public final class LlmMemoryExtractorTest {

  private static final String ONE_ITEM =
      "[{\"title\":\"Verify before paginating\",\"description\":\"Use when results may span"
          + " pages\",\"content\":\"Confirm the page id before loading more.\"}]";

  private static ReasoningTrace trace(String id, boolean successful) {
    return ReasoningTrace.builder()
        .id(id)
        .task("Book a flight")
        .output("done")
        .successful(successful)
        .build();
  }

  /** A JSON array of {@code n} well-formed memory items. */
  private static String items(int n) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < n; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"title\":\"t").append(i).append("\",\"description\":\"d\",\"content\":\"c\"}");
    }
    return sb.append(']').toString();
  }

  private static String system(FakeLlm llm) {
    return llm.lastSystemText().toLowerCase(Locale.ROOT);
  }

  @Test
  public void extract_emptyTrajectories_returnsEmpty() {
    FakeLlm llm = FakeLlm.returningText("[]");

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm).extract("q", ImmutableList.of()).blockingGet();

    assertThat(out).isEmpty();
  }

  @Test
  public void extract_successTrace_usesSuccessPrompt_andSetsProvenance() {
    FakeLlm llm = FakeLlm.returningText(ONE_ITEM);

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm)
            .extract("Book a flight", ImmutableList.of(trace("tr-1", true)))
            .blockingGet();

    assertThat(out).hasSize(1);
    ReasoningMemoryItem item = out.get(0);
    assertThat(item.title()).isEqualTo("Verify before paginating");
    assertThat(item.description()).contains("results may span");
    assertThat(item.content()).contains("page id");
    assertThat(item.sourceTraceSuccessful()).isTrue();
    assertThat(item.sourceTraceId()).isEqualTo("tr-1");
    assertThat(system(llm)).contains("success");
  }

  @Test
  public void extract_failureTrace_usesFailurePrompt_andMarksItem() {
    FakeLlm llm = FakeLlm.returningText(ONE_ITEM);

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm)
            .extract("Book a flight", ImmutableList.of(trace("tr-2", false)))
            .blockingGet();

    assertThat(out).hasSize(1);
    assertThat(out.get(0).sourceTraceSuccessful()).isFalse();
    assertThat(system(llm)).contains("prevent");
  }

  @Test
  public void extract_singleTrace_capsAtThree() {
    FakeLlm llm = FakeLlm.returningText(items(5));

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm).extract("q", ImmutableList.of(trace("tr", true))).blockingGet();

    assertThat(out).hasSize(3);
  }

  @Test
  public void extract_parallelTraces_usesParallelPrompt_andCapsAtFive() {
    FakeLlm llm = FakeLlm.returningText(items(6));

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm)
            .extract("q", ImmutableList.of(trace("a", true), trace("b", false)))
            .blockingGet();

    assertThat(out).hasSize(5);
    assertThat(system(llm)).contains("contrast");
  }

  @Test
  public void extract_malformedOutput_returnsEmpty_neverThrows() {
    FakeLlm llm = FakeLlm.returningText("sorry, I cannot help with that");

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm).extract("q", ImmutableList.of(trace("tr", true))).blockingGet();

    assertThat(out).isEmpty();
  }

  @Test
  public void extract_llmErrors_returnsEmpty() {
    FakeLlm llm = FakeLlm.erroring(new RuntimeException("boom"));

    List<ReasoningMemoryItem> out =
        new LlmMemoryExtractor(llm).extract("q", ImmutableList.of(trace("tr", true))).blockingGet();

    assertThat(out).isEmpty();
  }
}
