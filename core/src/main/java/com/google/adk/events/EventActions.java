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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** Represents the actions attached to an event. */
// TODO - b/414081262 make json wire camelCase
@JsonDeserialize(builder = EventActions.Builder.class)
public class EventActions extends JsonBaseModel {

  private Optional<Boolean> skipSummarization;
  private Map<String, Object> stateDelta;
  private Map<String, Integer> artifactDelta;
  private Optional<String> transferToAgent;
  private Optional<Boolean> escalate;
  private Map<String, Map<String, Object>> requestedAuthConfigs;
  private Map<String, ToolConfirmation> requestedToolConfirmations;
  private boolean endOfAgent;
  private Optional<EventCompaction> compaction;

  /** Default constructor for Jackson. */
  public EventActions() {
    this.skipSummarization = Optional.empty();
    this.stateDelta = Collections.synchronizedMap(new HashMap<>());
    this.artifactDelta = Collections.synchronizedMap(new HashMap<>());
    this.transferToAgent = Optional.empty();
    this.escalate = Optional.empty();
    this.requestedAuthConfigs = Collections.synchronizedMap(new HashMap<>());
    this.requestedToolConfirmations = Collections.synchronizedMap(new HashMap<>());
    this.endOfAgent = false;
    this.compaction = Optional.empty();
  }

  private EventActions(Builder builder) {
    this.skipSummarization = builder.skipSummarization;
    this.stateDelta = Collections.synchronizedMap(builder.stateDelta);
    this.artifactDelta = Collections.synchronizedMap(builder.artifactDelta);
    this.transferToAgent = builder.transferToAgent;
    this.escalate = builder.escalate;
    this.requestedAuthConfigs = Collections.synchronizedMap(builder.requestedAuthConfigs);
    this.requestedToolConfirmations =
        Collections.synchronizedMap(builder.requestedToolConfirmations);
    this.endOfAgent = builder.endOfAgent;
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
  public Map<String, Object> stateDelta() {
    return stateDelta;
  }

  public void setStateDelta(Map<String, Object> stateDelta) {
    this.stateDelta = Collections.synchronizedMap(new HashMap<>(stateDelta));
  }

  /**
   * Removes a key from the state delta.
   *
   * @param key The key to remove.
   * @deprecated Use {@link #stateDelta()}.put(key, null) instead.
   */
  @Deprecated
  public void removeStateByKey(String key) {
    stateDelta().put(key, null);
  }

  @JsonProperty("artifactDelta")
  public Map<String, Integer> artifactDelta() {
    return artifactDelta;
  }

  public void setArtifactDelta(Map<String, Integer> artifactDelta) {
    this.artifactDelta = Collections.synchronizedMap(new HashMap<>(artifactDelta));
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
  public Map<String, Map<String, Object>> requestedAuthConfigs() {
    return requestedAuthConfigs;
  }

  public void setRequestedAuthConfigs(Map<String, Map<String, Object>> requestedAuthConfigs) {
    this.requestedAuthConfigs = requestedAuthConfigs;
  }

  @JsonProperty("requestedToolConfirmations")
  public Map<String, ToolConfirmation> requestedToolConfirmations() {
    return requestedToolConfirmations;
  }

  public void setRequestedToolConfirmations(
      Map<String, ToolConfirmation> requestedToolConfirmations) {
    this.requestedToolConfirmations =
        Collections.synchronizedMap(new HashMap<>(requestedToolConfirmations));
  }

  @JsonProperty("endOfAgent")
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public boolean endOfAgent() {
    return endOfAgent;
  }

  public void setEndOfAgent(boolean endOfAgent) {
    this.endOfAgent = endOfAgent;
  }

  /**
   * @deprecated Use {@link #endOfAgent()} instead.
   */
  @Deprecated
  public Optional<Boolean> endInvocation() {
    return endOfAgent ? Optional.of(true) : Optional.empty();
  }

  /**
   * @deprecated Use {@link #setEndOfAgent(boolean)} instead.
   */
  @Deprecated
  public void setEndInvocation(Optional<Boolean> endInvocation) {
    this.endOfAgent = endInvocation.orElse(false);
  }

  /**
   * @deprecated Use {@link #setEndOfAgent(boolean)} instead.
   */
  @Deprecated
  public void setEndInvocation(boolean endInvocation) {
    this.endOfAgent = endInvocation;
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
        && (endOfAgent == that.endOfAgent)
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
        endOfAgent,
        compaction);
  }

  /** Builder for {@link EventActions}. */
  public static class Builder {
    private Optional<Boolean> skipSummarization;
    private Map<String, Object> stateDelta;
    private Map<String, Integer> artifactDelta;
    private Optional<String> transferToAgent;
    private Optional<Boolean> escalate;
    private Map<String, Map<String, Object>> requestedAuthConfigs;
    private Map<String, ToolConfirmation> requestedToolConfirmations;
    private boolean endOfAgent = false;
    private Optional<EventCompaction> compaction;

    public Builder() {
      this.skipSummarization = Optional.empty();
      this.stateDelta = new HashMap<>();
      this.artifactDelta = new HashMap<>();
      this.transferToAgent = Optional.empty();
      this.escalate = Optional.empty();
      this.requestedAuthConfigs = new HashMap<>();
      this.requestedToolConfirmations = new HashMap<>();
      this.compaction = Optional.empty();
    }

    private Builder(EventActions eventActions) {
      this.skipSummarization = eventActions.skipSummarization();
      this.stateDelta = new HashMap<>(eventActions.stateDelta());
      this.artifactDelta = new HashMap<>(eventActions.artifactDelta());
      this.transferToAgent = eventActions.transferToAgent();
      this.escalate = eventActions.escalate();
      this.requestedAuthConfigs = new HashMap<>(eventActions.requestedAuthConfigs());
      this.requestedToolConfirmations = new HashMap<>(eventActions.requestedToolConfirmations());
      this.endOfAgent = eventActions.endOfAgent();
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
    public Builder stateDelta(Map<String, Object> value) {
      this.stateDelta = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("artifactDelta")
    public Builder artifactDelta(Map<String, Integer> value) {
      this.artifactDelta = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("deletedArtifactIds")
    public Builder deletedArtifactIds(Set<String> value) {
      value.forEach(v -> artifactDelta.put(v, null));
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
    public Builder requestedAuthConfigs(Map<String, Map<String, Object>> value) {
      this.requestedAuthConfigs = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("requestedToolConfirmations")
    public Builder requestedToolConfirmations(Map<String, ToolConfirmation> value) {
      this.requestedToolConfirmations = Collections.synchronizedMap(new HashMap<>(value));
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("endOfAgent")
    public Builder endOfAgent(boolean endOfAgent) {
      this.endOfAgent = endOfAgent;
      return this;
    }

    /**
     * @deprecated Use {@link #endOfAgent(boolean)} instead.
     */
    @CanIgnoreReturnValue
    @JsonProperty("endInvocation")
    @Deprecated
    public Builder endInvocation(boolean endInvocation) {
      this.endOfAgent = endInvocation;
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
      this.endOfAgent = other.endOfAgent();
      other.compaction().ifPresent(this::compaction);
      return this;
    }

    public EventActions build() {
      return new EventActions(this);
    }
  }
}
