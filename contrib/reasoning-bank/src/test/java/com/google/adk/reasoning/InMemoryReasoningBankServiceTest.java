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

  @Test
  public void searchStrategies_emptyBank_returnsEmpty() {
    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "math problem").blockingGet();

    assertThat(response.strategies()).isEmpty();
  }

  @Test
  public void storeAndSearch_findsMatchingStrategy() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Math Problem Solving")
            .problemPattern("Mathematical calculations involving algebra")
            .steps(ImmutableList.of("Identify unknowns", "Set up equations", "Solve"))
            .tags(ImmutableList.of("math", "algebra"))
            .build();

    service.storeStrategy(APP_NAME, strategy).blockingAwait();

    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "algebra problem").blockingGet();

    assertThat(response.strategies()).hasSize(1);
    assertThat(response.strategies().get(0).id()).isEqualTo("strategy-1");
  }

  @Test
  public void searchStrategies_noMatch_returnsEmpty() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Math Problem Solving")
            .problemPattern("Mathematical calculations")
            .steps(ImmutableList.of("Step 1"))
            .build();

    service.storeStrategy(APP_NAME, strategy).blockingAwait();

    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "biology chemistry").blockingGet();

    assertThat(response.strategies()).isEmpty();
  }

  @Test
  public void searchStrategies_matchesByName() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Debugging Code")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    service.storeStrategy(APP_NAME, strategy).blockingAwait();

    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "code debugging").blockingGet();

    assertThat(response.strategies()).hasSize(1);
  }

  @Test
  public void searchStrategies_matchesByTags() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Test Strategy")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .tags(ImmutableList.of("python", "programming"))
            .build();

    service.storeStrategy(APP_NAME, strategy).blockingAwait();

    SearchReasoningResponse response = service.searchStrategies(APP_NAME, "python").blockingGet();

    assertThat(response.strategies()).hasSize(1);
  }

  @Test
  public void searchStrategies_rankedByRelevance() {
    // Strategy with pattern match (highest weight)
    ReasoningStrategy patternMatch =
        ReasoningStrategy.builder()
            .id("pattern-match")
            .name("Other Name")
            .problemPattern("algorithm optimization problems")
            .steps(ImmutableList.of("Step 1"))
            .build();

    // Strategy with name match (medium weight)
    ReasoningStrategy nameMatch =
        ReasoningStrategy.builder()
            .id("name-match")
            .name("Algorithm Design")
            .problemPattern("Other pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    service.storeStrategy(APP_NAME, nameMatch).blockingAwait();
    service.storeStrategy(APP_NAME, patternMatch).blockingAwait();

    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "algorithm").blockingGet();

    assertThat(response.strategies()).hasSize(2);
    // Pattern match should rank higher than name match
    assertThat(response.strategies().get(0).id()).isEqualTo("pattern-match");
  }

  @Test
  public void searchStrategies_respectsMaxResults() {
    for (int i = 0; i < 10; i++) {
      ReasoningStrategy strategy =
          ReasoningStrategy.builder()
              .id("strategy-" + i)
              .name("Test Strategy " + i)
              .problemPattern("Common problem pattern")
              .steps(ImmutableList.of("Step 1"))
              .build();
      service.storeStrategy(APP_NAME, strategy).blockingAwait();
    }

    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "problem pattern", 3).blockingGet();

    assertThat(response.strategies()).hasSize(3);
  }

  @Test
  public void searchStrategies_differentApps_isolated() {
    ReasoningStrategy strategy1 =
        ReasoningStrategy.builder()
            .id("app1-strategy")
            .name("Test Strategy")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    ReasoningStrategy strategy2 =
        ReasoningStrategy.builder()
            .id("app2-strategy")
            .name("Test Strategy")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    service.storeStrategy("app1", strategy1).blockingAwait();
    service.storeStrategy("app2", strategy2).blockingAwait();

    SearchReasoningResponse response1 = service.searchStrategies("app1", "test").blockingGet();
    SearchReasoningResponse response2 = service.searchStrategies("app2", "test").blockingGet();

    assertThat(response1.strategies()).hasSize(1);
    assertThat(response1.strategies().get(0).id()).isEqualTo("app1-strategy");

    assertThat(response2.strategies()).hasSize(1);
    assertThat(response2.strategies().get(0).id()).isEqualTo("app2-strategy");
  }

  @Test
  public void storeTrace_tracesAreStored() {
    ReasoningTrace trace =
        ReasoningTrace.builder()
            .id("trace-1")
            .task("Test task")
            .output("Test output")
            .reasoningSteps(ImmutableList.of("Step 1"))
            .successful(true)
            .build();

    // Should complete without error
    service.storeTrace(APP_NAME, trace).blockingAwait();
  }

  @Test
  public void searchStrategies_emptyQuery_returnsEmpty() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Test Strategy")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    service.storeStrategy(APP_NAME, strategy).blockingAwait();

    SearchReasoningResponse response = service.searchStrategies(APP_NAME, "").blockingGet();

    assertThat(response.strategies()).isEmpty();
  }

  @Test
  public void searchStrategies_caseInsensitive() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Test Strategy")
            .problemPattern("UPPERCASE pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    service.storeStrategy(APP_NAME, strategy).blockingAwait();

    SearchReasoningResponse response =
        service.searchStrategies(APP_NAME, "uppercase").blockingGet();

    assertThat(response.strategies()).hasSize(1);
  }
}
