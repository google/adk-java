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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link InMemoryReasoningBankService}. */
@RunWith(JUnit4.class)
public final class InMemoryReasoningBankServiceTest {

  private static final String APP_NAME = "test-app";

  private InMemoryReasoningBankService service;

  @Before
  public void setUp() {
    service = new InMemoryReasoningBankService();
  }

  private static ReasoningMemoryItem.Builder item(String id) {
    return ReasoningMemoryItem.builder().id(id).title("t").description("d").content("c");
  }

  @Test
  public void search_emptyBank_returnsEmpty() {
    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "math problem").blockingGet();

    assertThat(response.memoryItems()).isEmpty();
  }

  @Test
  public void storeAndSearch_findsMatchingItem() {
    ReasoningMemoryItem mem =
        item("mem-1")
            .title("Algebra problem solving")
            .description("Strategy for algebraic word problems")
            .content("Identify unknowns, set up equations, then solve.")
            .tags(ImmutableList.of("math", "algebra"))
            .build();

    service.storeMemoryItem(APP_NAME, mem).blockingAwait();

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "algebra problem").blockingGet();

    assertThat(response.memoryItems()).hasSize(1);
    assertThat(response.memoryItems().get(0).id()).isEqualTo("mem-1");
  }

  @Test
  public void search_noMatch_returnsEmpty() {
    service
        .storeMemoryItem(
            APP_NAME, item("mem-1").title("Math").description("x").content("y").build())
        .blockingAwait();

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "biology chemistry").blockingGet();

    assertThat(response.memoryItems()).isEmpty();
  }

  @Test
  public void search_matchesByDescription() {
    service
        .storeMemoryItem(
            APP_NAME,
            item("mem-1")
                .title("Unrelated")
                .description("Handles debugging of compiled code")
                .content("...")
                .build())
        .blockingAwait();

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "debugging").blockingGet();

    assertThat(response.memoryItems()).hasSize(1);
  }

  @Test
  public void search_matchesByTags() {
    service
        .storeMemoryItem(
            APP_NAME,
            item("mem-1")
                .title("Unrelated")
                .description("x")
                .content("y")
                .tags(ImmutableList.of("python", "programming"))
                .build())
        .blockingAwait();

    SearchReasoningResponse response = service.searchMemoryItems(APP_NAME, "python").blockingGet();

    assertThat(response.memoryItems()).hasSize(1);
  }

  @Test
  public void search_rankedByRelevance_titleOutranksDescription() {
    ReasoningMemoryItem titleMatch =
        item("title")
            .title("Algorithm optimization")
            .description("Other pattern")
            .content("...")
            .build();
    ReasoningMemoryItem descMatch =
        item("desc")
            .title("Other")
            .description("Handles algorithm questions")
            .content("...")
            .build();

    service.storeMemoryItem(APP_NAME, descMatch).blockingAwait();
    service.storeMemoryItem(APP_NAME, titleMatch).blockingAwait();

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "algorithm").blockingGet();

    assertThat(response.memoryItems()).hasSize(2);
    assertThat(response.memoryItems().get(0).id()).isEqualTo("title");
  }

  @Test
  public void search_respectsMaxResults() {
    for (int i = 0; i < 10; i++) {
      service
          .storeMemoryItem(
              APP_NAME,
              item("mem-" + i)
                  .title("Shared keyword title " + i)
                  .description("desc")
                  .content("c")
                  .build())
          .blockingAwait();
    }

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "keyword", 3).blockingGet();

    assertThat(response.memoryItems()).hasSize(3);
  }

  @Test
  public void search_differentApps_isolated() {
    service.storeMemoryItem("app1", item("a1").title("shared").build()).blockingAwait();
    service.storeMemoryItem("app2", item("a2").title("shared").build()).blockingAwait();

    assertThat(service.searchMemoryItems("app1", "shared").blockingGet().memoryItems()).hasSize(1);
    assertThat(service.searchMemoryItems("app1", "shared").blockingGet().memoryItems().get(0).id())
        .isEqualTo("a1");
    assertThat(service.searchMemoryItems("app2", "shared").blockingGet().memoryItems().get(0).id())
        .isEqualTo("a2");
  }

  @Test
  public void storeTrace_storesWithoutError() {
    ReasoningTrace trace =
        ReasoningTrace.builder()
            .id("trace-1")
            .task("Test task")
            .output("Test output")
            .reasoningSteps(ImmutableList.of("Step 1"))
            .successful(false)
            .build();

    service.storeTrace(APP_NAME, trace).blockingAwait();
  }

  @Test
  public void search_emptyQuery_returnsEmpty() {
    service.storeMemoryItem(APP_NAME, item("mem-1").title("anything").build()).blockingAwait();

    assertThat(service.searchMemoryItems(APP_NAME, "").blockingGet().memoryItems()).isEmpty();
  }

  @Test
  public void search_caseInsensitive() {
    service
        .storeMemoryItem(
            APP_NAME,
            item("mem-1")
                .title("Unrelated")
                .description("UPPERCASE content in description")
                .content("...")
                .build())
        .blockingAwait();

    assertThat(service.searchMemoryItems(APP_NAME, "uppercase").blockingGet().memoryItems())
        .hasSize(1);
  }

  @Test
  public void search_failureDerivedItems_areReturned() {
    // Items distilled from failed trajectories must still be retrievable — they are the
    // preventative lessons the paper emphasises.
    ReasoningMemoryItem failureLesson =
        item("pitfall")
            .title("Avoid infinite scroll trap")
            .description("Verify page identifier before loading more results")
            .content("...")
            .sourceTraceSuccessful(false)
            .build();
    service.storeMemoryItem(APP_NAME, failureLesson).blockingAwait();

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "scroll trap").blockingGet();

    assertThat(response.memoryItems()).hasSize(1);
    assertThat(response.memoryItems().get(0).sourceTraceSuccessful()).isFalse();
  }

  @Test
  public void search_defaultCap_isThreeItems() {
    // The paper's k-ablation shows retrieving more memories monotonically hurts; the default cap
    // should be 3 (one experience-equivalent), not 5.
    for (int i = 0; i < 4; i++) {
      service
          .storeMemoryItem(APP_NAME, item("m" + i).title("pagination guardrail").build())
          .blockingAwait();
    }

    SearchReasoningResponse response =
        service.searchMemoryItems(APP_NAME, "pagination").blockingGet();

    assertThat(response.memoryItems()).hasSize(3);
  }
}
