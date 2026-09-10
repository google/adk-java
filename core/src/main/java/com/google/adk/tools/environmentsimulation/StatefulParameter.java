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

/** A parameter shared between tools, and the tools on each side of it. */
@AutoValue
@JsonDeserialize(builder = StatefulParameter.Builder.class)
abstract class StatefulParameter extends JsonBaseModel {

  /** The name of the shared parameter, for example {@code ticket_id}. */
  @JsonProperty("parameter_name")
  abstract String parameterName();

  /** The tools that generate this parameter. */
  @JsonProperty("creating_tools")
  abstract ImmutableList<String> creatingTools();

  /** The tools that take this parameter as input. */
  @JsonProperty("consuming_tools")
  abstract ImmutableList<String> consumingTools();

  static Builder builder() {
    return new AutoValue_StatefulParameter.Builder()
        .creatingTools(ImmutableList.of())
        .consumingTools(ImmutableList.of());
  }

  /** Builder for {@link StatefulParameter}. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  abstract static class Builder {

    @JsonCreator
    static Builder jacksonBuilder() {
      return StatefulParameter.builder();
    }

    @CanIgnoreReturnValue
    @JsonProperty("parameter_name")
    abstract Builder parameterName(String parameterName);

    @CanIgnoreReturnValue
    @JsonProperty("creating_tools")
    abstract Builder creatingTools(Iterable<String> creatingTools);

    @CanIgnoreReturnValue
    @JsonProperty("consuming_tools")
    abstract Builder consumingTools(Iterable<String> consumingTools);

    abstract StatefulParameter build();
  }
}
