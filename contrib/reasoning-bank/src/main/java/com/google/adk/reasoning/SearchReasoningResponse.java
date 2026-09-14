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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Response from a reasoning memory search. */
@AutoValue
public abstract class SearchReasoningResponse {

  /** Returns the memory items that match the search query, ordered by relevance (best first). */
  public abstract ImmutableList<ReasoningMemoryItem> memoryItems();

  /** Creates a new builder for {@link SearchReasoningResponse}. */
  public static Builder builder() {
    return new AutoValue_SearchReasoningResponse.Builder().setMemoryItems(ImmutableList.of());
  }

  /** Builder for {@link SearchReasoningResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    abstract Builder setMemoryItems(ImmutableList<ReasoningMemoryItem> memoryItems);

    /** Sets the memory items from a list. */
    public Builder setMemoryItems(List<ReasoningMemoryItem> memoryItems) {
      return setMemoryItems(ImmutableList.copyOf(memoryItems));
    }

    /** Builds the response. */
    public abstract SearchReasoningResponse build();
  }
}
