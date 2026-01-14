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
package com.google.adk.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Part;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/** Represents the actions attached to an event. */
// TODO - b/414081262 make json wire camelCase
@JsonDeserialize(builder = EventActions.Builder.class)
public class EventActions {

  private Optional<Boolean> skipSummarization;
  private ConcurrentMap<String, Object> stateDelta;
  private ConcurrentMap<String, Part> artifactDelta;
  private Optional<String> transferToAgent;
  private Optional<Boolean> escalate;
  private ConcurrentMap<String, ConcurrentMap<String, Object>> requestedAuthConfigs;
  private ConcurrentMap<String, ToolConfirmation> requestedToolConfirmations;
  private Optional<Boolean> endInvocation;
  private Optional<EventCompaction> compaction;

  /** Default constructor for Jackson. */
  public EventActions() {
    this.skipSummarization = Optional.empty();
    this.stateDelta = new ConcurrentHashMap<>();
    this.artifactDelta = new ConcurrentHashMap<>();
    this.transferToAgent = Optional.empty();
    this.escalate = Optional.empty();
    this.requestedAuthConfigs = new ConcurrentHashMap<>();
    this.requestedToolConfirmations = new ConcurrentHashMap<>();
    this.endInvocation = Optional.empty();
    this.compaction = Optional.empty();
  }

  private EventActions(Builder builder) {
    this.skipSummarization = builder.skipSummarization;
    this.stateDelta = builder.stateDelta;
    this.artifactDelta = builder.artifactDelta;
    this.transferToAgent = builder.transferToAgent;
    this.escalate = builder.escalate;
    this.requestedAuthConfigs = builder.requestedAuthConfigs;
    this.requestedToolConfirmations = builder.requestedToolConfirmations;
    this.endInvocation = builder.endInvocation;
    this.compaction = builder.compaction;
  }

  @JsonProperty("skipSummarization")
  public Optional<Boolean> skipSummarization() {
    return skipSummarization;
  }

  public void setSkipSummarization(@Nullable Boolean skipSummarization) {
    this.skipSummarization = Optional.ofNullable(skipSummarization);
  }

  public void setSkipSummarization(Optional<Boolean> skipSummarization) {
    this.skipSummarization = skipSummarization;
  }

  public void setSkipSummarization(boolean skipSummarization) {
    this.skipSummarization = Optional.of(skipSummarization);
  }

  @JsonProperty("stateDelta")
  public ConcurrentMap<String, Object> stateDelta() {
    return stateDelta;
  }

  public void setStateDelta(ConcurrentMap<String, Object> stateDelta) {
    this.stateDelta = stateDelta;
  }

  @JsonProperty("artifactDelta")
  public ConcurrentMap<String, Part> artifactDelta() {
    return artifactDelta;
  }

  public void setArtifactDelta(ConcurrentMap<String, Part> artifactDelta) {
    this.artifactDelta = artifactDelta;
  }

  @JsonProperty("transferToAgent")
  public Optional<String> transferToAgent() {
    return transferToAgent;
  }

  public void setTransferToAgent(Optional<String> transferToAgent) {
    this.transferToAgent = transferToAgent;
  }

  public void setTransferToAgent(String transferToAgent) {
    this.transferToAgent = Optional.ofNullable(transferToAgent);
  }

  @JsonProperty("escalate")
  public Optional<Boolean> escalate() {
    return escalate;
  }

  public void setEscalate(Optional<Boolean> escalate) {
    this.escalate = escalate;
  }

  public void setEscalate(boolean escalate) {
    this.escalate = Optional.of(escalate);
  }

  @JsonProperty("requestedAuthConfigs")
  public ConcurrentMap<String, ConcurrentMap<String, Object>> requestedAuthConfigs() {
    return requestedAuthConfigs;
  }

  public void setRequestedAuthConfigs(
      ConcurrentMap<String, ConcurrentMap<String, Object>> requestedAuthConfigs) {
    this.requestedAuthConfigs = requestedAuthConfigs;
  }

  @JsonProperty("requestedToolConfirmations")
  public ConcurrentMap<String, ToolConfirmation> requestedToolConfirmations() {
    return requestedToolConfirmations;
  }

  public void setRequestedToolConfirmations(
      ConcurrentMap<String, ToolConfirmation> requestedToolConfirmations) {
    this.requestedToolConfirmations = requestedToolConfirmations;
  }

  @JsonProperty("endInvocation")
  public Optional<Boolean> endInvocation() {
    return endInvocation;
  }

  public void setEndInvocation(Optional<Boolean> endInvocation) {
    this.endInvocation = endInvocation;
  }

  public void setEndInvocation(boolean endInvocation) {
    this.endInvocation = Optional.of(endInvocation);
  }

  @JsonProperty("compaction")
  public Optional<EventCompaction> compaction() {
    return compaction;
  }

  public void setCompaction(Optional<EventCompaction> compaction) {
    this.compaction = compaction;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EventActions that)) {
      return false;
    }
    return Objects.equals(skipSummarization, that.skipSummarization)
        && Objects.equals(stateDelta, that.stateDelta)
        && Objects.equals(artifactDelta, that.artifactDelta)
        && Objects.equals(transferToAgent, that.transferToAgent)
        && Objects.equals(escalate, that.escalate)
        && Objects.equals(requestedAuthConfigs, that.requestedAuthConfigs)
        && Objects.equals(requestedToolConfirmations, that.requestedToolConfirmations)
        && Objects.equals(endInvocation, that.endInvocation)
        && Objects.equals(compaction, that.compaction);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        skipSummarization,
        stateDelta,
        artifactDelta,
        transferToAgent,
        escalate,
        requestedAuthConfigs,
        requestedToolConfirmations,
        endInvocation,
        compaction);
  }

  /** Builder for {@link EventActions}. */
  public static class Builder {
    private Optional<Boolean> skipSummarization;
    private ConcurrentMap<String, Object> stateDelta;
    private ConcurrentMap<String, Part> artifactDelta;
    private Optional<String> transferToAgent;
    private Optional<Boolean> escalate;
    private ConcurrentMap<String, ConcurrentMap<String, Object>> requestedAuthConfigs;
    private ConcurrentMap<String, ToolConfirmation> requestedToolConfirmations;
    private Optional<Boolean> endInvocation;
    private Optional<EventCompaction> compaction;

    public Builder() {
      this.skipSummarization = Optional.empty();
      this.stateDelta = new ConcurrentHashMap<>();
      this.artifactDelta = new ConcurrentHashMap<>();
      this.transferToAgent = Optional.empty();
      this.escalate = Optional.empty();
      this.requestedAuthConfigs = new ConcurrentHashMap<>();
      this.requestedToolConfirmations = new ConcurrentHashMap<>();
      this.endInvocation = Optional.empty();
      this.compaction = Optional.empty();
    }

    private Builder(EventActions eventActions) {
      this.skipSummarization = eventActions.skipSummarization();
      this.stateDelta = new ConcurrentHashMap<>(eventActions.stateDelta());
      this.artifactDelta = new ConcurrentHashMap<>(eventActions.artifactDelta());
      this.transferToAgent = eventActions.transferToAgent();
      this.escalate = eventActions.escalate();
      this.requestedAuthConfigs = new ConcurrentHashMap<>(eventActions.requestedAuthConfigs());
      this.requestedToolConfirmations =
          new ConcurrentHashMap<>(eventActions.requestedToolConfirmations());
      this.endInvocation = eventActions.endInvocation();
      this.compaction = eventActions.compaction();
    }

    @CanIgnoreReturnValue
    @JsonProperty("skipSummarization")
    public Builder skipSummarization(boolean skipSummarization) {
      this.skipSummarization = Optional.of(skipSummarization);
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("stateDelta")
    public Builder stateDelta(ConcurrentMap<String, Object> value) {
      this.stateDelta = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("artifactDelta")
    public Builder artifactDelta(ConcurrentMap<String, Part> value) {
      this.artifactDelta = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("transferToAgent")
    public Builder transferToAgent(String agentId) {
      this.transferToAgent = Optional.ofNullable(agentId);
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("escalate")
    public Builder escalate(boolean escalate) {
      this.escalate = Optional.of(escalate);
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("requestedAuthConfigs")
    public Builder requestedAuthConfigs(
        ConcurrentMap<String, ConcurrentMap<String, Object>> value) {
      this.requestedAuthConfigs = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("requestedToolConfirmations")
    public Builder requestedToolConfirmations(ConcurrentMap<String, ToolConfirmation> value) {
      this.requestedToolConfirmations = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("endInvocation")
    public Builder endInvocation(boolean endInvocation) {
      this.endInvocation = Optional.of(endInvocation);
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("compaction")
    public Builder compaction(EventCompaction value) {
      this.compaction = Optional.ofNullable(value);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder merge(EventActions other) {
      other.skipSummarization().ifPresent(this::skipSummarization);
      this.stateDelta.putAll(other.stateDelta());
      this.artifactDelta.putAll(other.artifactDelta());
      other.transferToAgent().ifPresent(this::transferToAgent);
      other.escalate().ifPresent(this::escalate);
      this.requestedAuthConfigs.putAll(other.requestedAuthConfigs());
      this.requestedToolConfirmations.putAll(other.requestedToolConfirmations());
      other.endInvocation().ifPresent(this::endInvocation);
      other.compaction().ifPresent(this::compaction);
      return this;
    }

    public EventActions build() {
      return new EventActions(this);
    }
  }
}
