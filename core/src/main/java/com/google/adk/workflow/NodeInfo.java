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

package com.google.adk.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;

/**
 * The identity of the workflow-node activation that emitted an {@link com.google.adk.events.Event}.
 */
@Experimental
@AutoValue
@JsonDeserialize(builder = NodeInfo.Builder.class)
public abstract class NodeInfo {

  /**
   * Returns the emitting node's path. Segments are {@code /}-separated and each is {@code
   * name@runId}, so a node of workflow {@code wf} reads {@code wf@1/a@1}; an empty path means the
   * event did not come from a workflow node.
   */
  @JsonProperty("path")
  public abstract String path();

  /**
   * Returns the node paths this event's output counts for: the emitting node's path, followed by
   * the paths of the ancestor workflows that use this event as their output. It is set on an event
   * that carries the node's output, either in its {@code output} field or as a message-as-output
   * event whose content is the output; an empty list means the output counts for no node.
   */
  @JsonProperty("outputFor")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public abstract ImmutableList<String> outputFor();

  /**
   * Returns {@code true} if this event's content is the node's output, so no separate output event
   * follows.
   */
  @JsonProperty("messageAsOutput")
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public abstract boolean messageAsOutput();

  public static Builder builder() {
    return new AutoValue_NodeInfo.Builder()
        .path("")
        .outputFor(ImmutableList.of())
        .messageAsOutput(false);
  }

  public abstract Builder toBuilder();

  /** Builder for {@link NodeInfo}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return builder();
    }

    @CanIgnoreReturnValue
    @JsonProperty("path")
    public abstract Builder path(String path);

    @CanIgnoreReturnValue
    @JsonProperty("outputFor")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    public abstract Builder outputFor(List<String> outputFor);

    @CanIgnoreReturnValue
    @JsonProperty("messageAsOutput")
    public abstract Builder messageAsOutput(boolean messageAsOutput);

    public abstract NodeInfo build();
  }
}
