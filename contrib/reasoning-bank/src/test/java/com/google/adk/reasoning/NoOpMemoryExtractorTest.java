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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NoOpMemoryExtractor}. */
@RunWith(JUnit4.class)
public final class NoOpMemoryExtractorTest {

  @Test
  public void extract_returnsEmptyList() {
    ReasoningTrace trace =
        ReasoningTrace.builder().id("t1").task("task").output("out").successful(true).build();

    ImmutableList<ReasoningMemoryItem> result =
        new NoOpMemoryExtractor().extract("query", ImmutableList.of(trace)).blockingGet();

    assertThat(result).isEmpty();
  }

  @Test
  public void extract_emptyTrajectories_returnsEmptyList() {
    ImmutableList<ReasoningMemoryItem> result =
        new NoOpMemoryExtractor().extract("query", ImmutableList.of()).blockingGet();

    assertThat(result).isEmpty();
  }
}
