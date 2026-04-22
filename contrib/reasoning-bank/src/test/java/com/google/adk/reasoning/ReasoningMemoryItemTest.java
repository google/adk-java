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

/** Unit tests for {@link ReasoningMemoryItem}. */
@RunWith(JUnit4.class)
public final class ReasoningMemoryItemTest {

  @Test
  public void builder_createsValidItem() {
    ReasoningMemoryItem item =
        ReasoningMemoryItem.builder()
            .id("mem-1")
            .title("Verify page identifier before pagination")
            .description("Always confirm the active page before loading more results.")
            .content(
                "When scrolling through paginated lists, cross-reference the current page id "
                    + "with active filters to avoid infinite scroll traps.")
            .tags(ImmutableList.of("web", "pagination"))
            .sourceTraceSuccessful(false)
            .createdAt("2025-01-05T10:00:00Z")
            .build();

    assertThat(item.id()).isEqualTo("mem-1");
    assertThat(item.title()).isEqualTo("Verify page identifier before pagination");
    assertThat(item.description()).contains("active page");
    assertThat(item.content()).contains("page id");
    assertThat(item.tags()).containsExactly("web", "pagination").inOrder();
    assertThat(item.sourceTraceSuccessful()).isFalse();
    assertThat(item.createdAt()).isEqualTo("2025-01-05T10:00:00Z");
  }

  @Test
  public void builder_defaultsTagsEmptyAndSuccessful() {
    ReasoningMemoryItem item =
        ReasoningMemoryItem.builder()
            .id("mem-1")
            .title("Test")
            .description("d")
            .content("c")
            .build();

    assertThat(item.tags()).isEmpty();
    assertThat(item.sourceTraceSuccessful()).isTrue();
  }

  @Test
  public void builder_createdAtFromInstant() {
    Instant now = Instant.parse("2025-01-05T12:00:00Z");
    ReasoningMemoryItem item =
        ReasoningMemoryItem.builder()
            .id("mem-1")
            .title("t")
            .description("d")
            .content("c")
            .createdAt(now)
            .build();

    assertThat(item.createdAt()).isEqualTo("2025-01-05T12:00:00Z");
  }

  @Test
  public void toBuilder_copiesAndOverrides() {
    ReasoningMemoryItem original =
        ReasoningMemoryItem.builder()
            .id("mem-1")
            .title("Original")
            .description("d")
            .content("c")
            .build();

    ReasoningMemoryItem modified =
        original.toBuilder().title("Modified").sourceTraceSuccessful(false).build();

    assertThat(original.title()).isEqualTo("Original");
    assertThat(original.sourceTraceSuccessful()).isTrue();
    assertThat(modified.title()).isEqualTo("Modified");
    assertThat(modified.sourceTraceSuccessful()).isFalse();
    assertThat(modified.id()).isEqualTo(original.id());
  }
}
