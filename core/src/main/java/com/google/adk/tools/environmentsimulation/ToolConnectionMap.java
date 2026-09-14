/*
 * Copyright 2026 Google LLC
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

package com.google.adk.tools.environmentsimulation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** How the simulated tools connect to one another through shared, stateful parameters. */
@AutoValue
@JsonDeserialize(builder = ToolConnectionMap.Builder.class)
abstract class ToolConnectionMap extends JsonBaseModel {

  /** The stateful parameters and their connections. */
  @JsonProperty("stateful_parameters")
  abstract ImmutableList<StatefulParameter> statefulParameters();

  static Builder builder() {
    return new AutoValue_ToolConnectionMap.Builder().statefulParameters(ImmutableList.of());
  }

  /** Builder for {@link ToolConnectionMap}. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  abstract static class Builder {

    @JsonCreator
    static Builder jacksonBuilder() {
      return ToolConnectionMap.builder();
    }

    @CanIgnoreReturnValue
    @JsonProperty("stateful_parameters")
    abstract Builder statefulParameters(Iterable<StatefulParameter> statefulParameters);

    abstract ToolConnectionMap build();
  }
}
