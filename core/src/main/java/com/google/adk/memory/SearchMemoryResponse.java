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

package com.google.adk.memory;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;

/** Represents the response from a memory search. */
@AutoValue
public abstract class SearchMemoryResponse {

  /** Returns a list of memory entries that relate to the search query. */
  public abstract ImmutableList<MemoryEntry> memories();

  /** Creates a new builder for {@link SearchMemoryResponse}. */
  public static Builder builder() {
    return new AutoValue_SearchMemoryResponse.Builder().memories(ImmutableList.of());
  }

  /** Builder for {@link SearchMemoryResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setMemories(ImmutableList<MemoryEntry> memories) {
      return memories(memories);
    }

    /** Sets the list of memory entries using a list. */
    @Deprecated
    @CanIgnoreReturnValue
    public final Builder setMemories(List<MemoryEntry> memories) {
      return memories(ImmutableList.copyOf(memories));
    }

    @CanIgnoreReturnValue
    public abstract Builder memories(ImmutableList<MemoryEntry> memories);

    @CanIgnoreReturnValue
    public Builder memories(List<MemoryEntry> memories) {
      return memories(ImmutableList.copyOf(memories));
    }

    /** Builds the immutable {@link SearchMemoryResponse} object. */
    public abstract SearchMemoryResponse build();
  }
}
