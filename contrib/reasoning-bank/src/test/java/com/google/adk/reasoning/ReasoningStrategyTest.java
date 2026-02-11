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
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReasoningStrategy}. */
@RunWith(JUnit4.class)
public final class ReasoningStrategyTest {

  @Test
  public void builder_createsValidStrategy() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Math Problem Solving")
            .problemPattern("Mathematical word problems involving rates")
            .steps(
                ImmutableList.of(
                    "Identify the known quantities",
                    "Identify what needs to be found",
                    "Set up equations",
                    "Solve and verify"))
            .tags(ImmutableList.of("math", "rates"))
            .createdAt("2025-01-05T10:00:00Z")
            .build();

    assertThat(strategy.id()).isEqualTo("strategy-1");
    assertThat(strategy.name()).isEqualTo("Math Problem Solving");
    assertThat(strategy.problemPattern()).isEqualTo("Mathematical word problems involving rates");
    assertThat(strategy.steps()).hasSize(4);
    assertThat(strategy.tags()).containsExactly("math", "rates");
    assertThat(strategy.createdAt()).isEqualTo("2025-01-05T10:00:00Z");
  }

  @Test
  public void builder_defaultTagsIsEmpty() {
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Test Strategy")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    assertThat(strategy.tags()).isEmpty();
  }

  @Test
  public void builder_createdAtWithInstant() {
    Instant now = Instant.parse("2025-01-05T12:00:00Z");
    ReasoningStrategy strategy =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Test Strategy")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .createdAt(now)
            .build();

    assertThat(strategy.createdAt()).isEqualTo("2025-01-05T12:00:00Z");
  }

  @Test
  public void toBuilder_createsCopy() {
    ReasoningStrategy original =
        ReasoningStrategy.builder()
            .id("strategy-1")
            .name("Original")
            .problemPattern("Test pattern")
            .steps(ImmutableList.of("Step 1"))
            .build();

    ReasoningStrategy modified = original.toBuilder().name("Modified").build();

    assertThat(original.name()).isEqualTo("Original");
    assertThat(modified.name()).isEqualTo("Modified");
    assertThat(modified.id()).isEqualTo(original.id());
  }
}
