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

/** Unit tests for {@link ReasoningTrace}. */
@RunWith(JUnit4.class)
public final class ReasoningTraceTest {

  @Test
  public void builder_createsValidTrace() {
    ReasoningTrace trace =
        ReasoningTrace.builder()
            .id("trace-1")
            .task("Calculate the area of a circle with radius 5")
            .output("The area is 78.54 square units")
            .reasoningSteps(
                ImmutableList.of(
                    "Recall the formula: A = πr²",
                    "Substitute r = 5",
                    "Calculate: A = π × 25 = 78.54"))
            .successful(true)
            .capturedAt("2025-01-05T10:00:00Z")
            .metadata("source=test")
            .build();

    assertThat(trace.id()).isEqualTo("trace-1");
    assertThat(trace.task()).isEqualTo("Calculate the area of a circle with radius 5");
    assertThat(trace.output()).isEqualTo("The area is 78.54 square units");
    assertThat(trace.reasoningSteps()).hasSize(3);
    assertThat(trace.successful()).isTrue();
    assertThat(trace.capturedAt()).isEqualTo("2025-01-05T10:00:00Z");
    assertThat(trace.metadata()).isEqualTo("source=test");
  }

  @Test
  public void builder_defaultsToSuccessful() {
    ReasoningTrace trace =
        ReasoningTrace.builder().id("trace-1").task("Test task").output("Test output").build();

    assertThat(trace.successful()).isTrue();
  }

  @Test
  public void builder_defaultReasoningStepsIsEmpty() {
    ReasoningTrace trace =
        ReasoningTrace.builder().id("trace-1").task("Test task").output("Test output").build();

    assertThat(trace.reasoningSteps()).isEmpty();
  }

  @Test
  public void builder_capturedAtWithInstant() {
    Instant now = Instant.parse("2025-01-05T12:00:00Z");
    ReasoningTrace trace =
        ReasoningTrace.builder()
            .id("trace-1")
            .task("Test task")
            .output("Test output")
            .capturedAt(now)
            .build();

    assertThat(trace.capturedAt()).isEqualTo("2025-01-05T12:00:00Z");
  }

  @Test
  public void toBuilder_createsCopy() {
    ReasoningTrace original =
        ReasoningTrace.builder()
            .id("trace-1")
            .task("Original task")
            .output("Original output")
            .successful(true)
            .build();

    ReasoningTrace modified = original.toBuilder().task("Modified task").successful(false).build();

    assertThat(original.task()).isEqualTo("Original task");
    assertThat(original.successful()).isTrue();
    assertThat(modified.task()).isEqualTo("Modified task");
    assertThat(modified.successful()).isFalse();
    assertThat(modified.id()).isEqualTo(original.id());
  }
}
