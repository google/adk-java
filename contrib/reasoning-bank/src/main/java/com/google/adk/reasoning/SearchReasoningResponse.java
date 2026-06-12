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

/** Represents the response from a reasoning strategy search. */
@AutoValue
public abstract class SearchReasoningResponse {

  /** Returns a list of reasoning strategies that match the search query. */
  public abstract ImmutableList<ReasoningStrategy> strategies();

  /** Creates a new builder for {@link SearchReasoningResponse}. */
  public static Builder builder() {
    return new AutoValue_SearchReasoningResponse.Builder().setStrategies(ImmutableList.of());
  }

  /** Builder for {@link SearchReasoningResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    abstract Builder setStrategies(ImmutableList<ReasoningStrategy> strategies);

    /** Sets the list of reasoning strategies using a list. */
    public Builder setStrategies(List<ReasoningStrategy> strategies) {
      return setStrategies(ImmutableList.copyOf(strategies));
    }

    /** Builds the immutable {@link SearchReasoningResponse} object. */
    public abstract SearchReasoningResponse build();
  }
}
