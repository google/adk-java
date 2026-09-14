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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ConsolidationPolicy}. */
@RunWith(JUnit4.class)
public final class ConsolidationPolicyTest {

  private static ReasoningMemoryItem item(String id, String createdAt) {
    ReasoningMemoryItem.Builder b =
        ReasoningMemoryItem.builder().id(id).title("t").description("d").content("c");
    if (createdAt != null) {
      b.createdAt(createdAt);
    }
    return b.build();
  }

  @Test
  public void identity_appendsKeepingDuplicates() {
    ReasoningMemoryItem a = item("a", null);
    ReasoningMemoryItem b = item("b", null);

    List<ReasoningMemoryItem> kept =
        ConsolidationPolicy.identity().reconcile(ImmutableList.of(a), b);

    assertThat(kept).containsExactly(a, b).inOrder();
  }

  @Test
  public void identity_emptyExisting_returnsSingleton() {
    ReasoningMemoryItem a = item("a", null);

    assertThat(ConsolidationPolicy.identity().reconcile(ImmutableList.of(), a)).containsExactly(a);
  }

  @Test
  public void boundedByCreatedAt_evictsOldest_retainsNewest() {
    ReasoningMemoryItem t0 = item("t0", "2025-01-01T00:00:00Z");
    ReasoningMemoryItem t1 = item("t1", "2025-01-02T00:00:00Z");
    ReasoningMemoryItem t2 = item("t2", "2025-01-03T00:00:00Z");

    List<ReasoningMemoryItem> kept =
        ConsolidationPolicy.boundedByCreatedAt(2).reconcile(ImmutableList.of(t0, t1), t2);

    assertThat(kept).containsExactly(t1, t2).inOrder();
  }

  @Test
  public void boundedByCreatedAt_nullCreatedAt_evictedFirst() {
    ReasoningMemoryItem nullItem = item("n", null);
    ReasoningMemoryItem t1 = item("t1", "2025-01-02T00:00:00Z");

    List<ReasoningMemoryItem> kept =
        ConsolidationPolicy.boundedByCreatedAt(1).reconcile(ImmutableList.of(nullItem), t1);

    assertThat(kept).containsExactly(t1);
  }

  @Test
  public void boundedByCreatedAt_underCapacity_keepsAll() {
    ReasoningMemoryItem a = item("a", "2025-01-01T00:00:00Z");

    assertThat(ConsolidationPolicy.boundedByCreatedAt(5).reconcile(ImmutableList.of(), a))
        .containsExactly(a);
  }

  @Test
  public void boundedByCreatedAt_zeroOrNegative_throws() {
    assertThrows(IllegalArgumentException.class, () -> ConsolidationPolicy.boundedByCreatedAt(0));
    assertThrows(IllegalArgumentException.class, () -> ConsolidationPolicy.boundedByCreatedAt(-1));
  }
}
