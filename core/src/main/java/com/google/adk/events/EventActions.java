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
import com.google.adk.sessions.State;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/** Represents the actions attached to an event. */
// TODO - b/414081262 make json wire camelCase
@JsonDeserialize(builder = EventActions.Builder.class)
public class EventActions extends JsonBaseModel {

  private @Nullable Boolean skipSummarization;
  private ConcurrentMap<String, Object> stateDelta;
  private ConcurrentMap<String, Integer> artifactDelta;
  private Set<String> deletedArtifactIds;
  private @Nullable String transferToAgent;
  private @Nullable Boolean escalate;
  private ConcurrentMap<String, Map<String, Object>> requestedAuthConfigs;
  private ConcurrentMap<String, ToolConfirmation> requestedToolConfirmations;
  private boolean endOfAgent;
  private @Nullable EventCompaction compaction;

  /** Default constructor for Jackson. */
  public EventActions() {
    this.stateDelta = new ConcurrentHashMap<>();
    this.artifactDelta = new ConcurrentHashMap<>();
    this.deletedArtifactIds = new HashSet<>();
    this.requestedAuthConfigs = new ConcurrentHashMap<>();
    this.requestedToolConfirmations = new ConcurrentHashMap<>();
    this.endOfAgent = false;
  }

  private EventActions(Builder builder) {
    this.skipSummarization = builder.skipSummarization;
    this.stateDelta = builder.stateDelta;
    this.artifactDelta = builder.artifactDelta;
    this.deletedArtifactIds = builder.deletedArtifactIds;
    this.transferToAgent = builder.transferToAgent;
    this.escalate = builder.escalate;
    this.requestedAuthConfigs = builder.requestedAuthConfigs;
    this.requestedToolConfirmations = builder.requestedToolConfirmations;
    this.endOfAgent = builder.endOfAgent;
    this.compaction = builder.compaction;
  }

  @JsonProperty("skipSummarization")
  public Optional<Boolean> skipSummarization() {
    return Optional.ofNullable(skipSummarization);
  }

  public void setSkipSummarization(@Nullable Boolean skipSummarization) {
    this.skipSummarization = skipSummarization;
  }

  public void setSkipSummarization(boolean skipSummarization) {
    this.skipSummarization = skipSummarization;
  }

  @JsonProperty("stateDelta")
  public Map<String, Object> stateDelta() {
    return stateDelta;
  }

  @Deprecated // Use stateDelta() and removeStateByKey() instead.
  public void setStateDelta(ConcurrentMap<String, Object> stateDelta) {
    this.stateDelta = stateDelta;
  }

  /**
   * Removes a key from the state delta.
   *
   * @param key The key to remove.
   */
  public void removeStateByKey(String key) {
    stateDelta.put(key, State.REMOVED);
  }

  @JsonProperty("artifactDelta")
  public Map<String, Integer> artifactDelta() {
    return artifactDelta;
  }

  public void setArtifactDelta(Map<String, Integer> artifactDelta) {
    this.artifactDelta = new ConcurrentHashMap<>(artifactDelta);
  }

  @JsonProperty("deletedArtifactIds")
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public Set<String> deletedArtifactIds() {
    return deletedArtifactIds;
  }

  public void setDeletedArtifactIds(Set<String> deletedArtifactIds) {
    this.deletedArtifactIds = deletedArtifactIds;
  }

  @JsonProperty("transferToAgent")
  public Optional<String> transferToAgent() {
    return Optional.ofNullable(transferToAgent);
  }

  public void setTransferToAgent(@Nullable String transferToAgent) {
    this.transferToAgent = transferToAgent;
  }

  @JsonProperty("escalate")
  public Optional<Boolean> escalate() {
    return Optional.ofNullable(escalate);
  }

  public void setEscalate(@Nullable Boolean escalate) {
    this.escalate = escalate;
  }

  @JsonProperty("requestedAuthConfigs")
  public Map<String, Map<String, Object>> requestedAuthConfigs() {
    return requestedAuthConfigs;
  }

  public void setRequestedAuthConfigs(
      Map<String, ? extends Map<String, Object>> requestedAuthConfigs) {
    if (requestedAuthConfigs == null) {
      this.requestedAuthConfigs = new ConcurrentHashMap<>();
    } else {
      this.requestedAuthConfigs = new ConcurrentHashMap<>(requestedAuthConfigs);
    }
  }

  @JsonProperty("requestedToolConfirmations")
  public Map<String, ToolConfirmation> requestedToolConfirmations() {
    return requestedToolConfirmations;
  }

  public void setRequestedToolConfirmations(
      Map<String, ToolConfirmation> requestedToolConfirmations) {
    if (requestedToolConfirmations == null) {
      this.requestedToolConfirmations = new ConcurrentHashMap<>();
    } else {
      this.requestedToolConfirmations = new ConcurrentHashMap<>(requestedToolConfirmations);
    }
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
  public void setEndInvocation(boolean endInvocation) {
    this.endOfAgent = endInvocation;
  }

  @JsonProperty("compaction")
  public Optional<EventCompaction> compaction() {
    return Optional.ofNullable(compaction);
  }

  public void setCompaction(@Nullable EventCompaction compaction) {
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
        && Objects.equals(deletedArtifactIds, that.deletedArtifactIds)
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
        deletedArtifactIds,
        transferToAgent,
        escalate,
        requestedAuthConfigs,
        requestedToolConfirmations,
        endOfAgent,
        compaction);
  }

  /** Builder for {@link EventActions}. */
  public static class Builder {
    private @Nullable Boolean skipSummarization;
    private ConcurrentMap<String, Object> stateDelta;
    private ConcurrentMap<String, Integer> artifactDelta;
    private Set<String> deletedArtifactIds;
    private @Nullable String transferToAgent;
    private @Nullable Boolean escalate;
    private ConcurrentMap<String, Map<String, Object>> requestedAuthConfigs;
    private ConcurrentMap<String, ToolConfirmation> requestedToolConfirmations;
    private boolean endOfAgent = false;
    private @Nullable EventCompaction compaction;

    public Builder() {
      this.stateDelta = new ConcurrentHashMap<>();
      this.artifactDelta = new ConcurrentHashMap<>();
      this.deletedArtifactIds = new HashSet<>();
      this.requestedAuthConfigs = new ConcurrentHashMap<>();
      this.requestedToolConfirmations = new ConcurrentHashMap<>();
    }

    private Builder(EventActions eventActions) {
      this.skipSummarization = eventActions.skipSummarization;
      this.stateDelta = new ConcurrentHashMap<>(eventActions.stateDelta());
      this.artifactDelta = new ConcurrentHashMap<>(eventActions.artifactDelta());
      this.deletedArtifactIds = new HashSet<>(eventActions.deletedArtifactIds());
      this.transferToAgent = eventActions.transferToAgent;
      this.escalate = eventActions.escalate;
      this.requestedAuthConfigs = new ConcurrentHashMap<>(eventActions.requestedAuthConfigs());
      this.requestedToolConfirmations =
          new ConcurrentHashMap<>(eventActions.requestedToolConfirmations());
      this.endOfAgent = eventActions.endOfAgent;
      this.compaction = eventActions.compaction;
    }

    @CanIgnoreReturnValue
    @JsonProperty("skipSummarization")
    public Builder skipSummarization(boolean skipSummarization) {
      this.skipSummarization = skipSummarization;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("stateDelta")
    public Builder stateDelta(@Nullable Map<String, Object> value) {
      if (value == null) {
        this.stateDelta = new ConcurrentHashMap<>();
      } else {
        this.stateDelta = new ConcurrentHashMap<>(value);
      }
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("artifactDelta")
    public Builder artifactDelta(@Nullable Map<String, Integer> value) {
      if (value == null) {
        this.artifactDelta = new ConcurrentHashMap<>();
      } else {
        this.artifactDelta = new ConcurrentHashMap<>(value);
      }
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("deletedArtifactIds")
    public Builder deletedArtifactIds(Set<String> value) {
      this.deletedArtifactIds = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("transferToAgent")
    public Builder transferToAgent(@Nullable String agentId) {
      this.transferToAgent = agentId;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("escalate")
    public Builder escalate(boolean escalate) {
      this.escalate = escalate;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("requestedAuthConfigs")
    public Builder requestedAuthConfigs(
        @Nullable Map<String, ? extends Map<String, Object>> value) {
      if (value == null) {
        this.requestedAuthConfigs = new ConcurrentHashMap<>();
      } else {
        this.requestedAuthConfigs = new ConcurrentHashMap<>(value);
      }
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("requestedToolConfirmations")
    public Builder requestedToolConfirmations(@Nullable Map<String, ToolConfirmation> value) {
      if (value == null) {
        this.requestedToolConfirmations = new ConcurrentHashMap<>();
      } else {
        this.requestedToolConfirmations = new ConcurrentHashMap<>(value);
      }
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
    public Builder compaction(@Nullable EventCompaction value) {
      this.compaction = value;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder merge(EventActions other) {
      other.skipSummarization().ifPresent(this::skipSummarization);
      other.stateDelta().forEach((key, value) -> stateDelta.merge(key, value, Builder::deepMerge));
      this.artifactDelta.putAll(other.artifactDelta());
      this.deletedArtifactIds.addAll(other.deletedArtifactIds());
      other.transferToAgent().ifPresent(this::transferToAgent);
      other.escalate().ifPresent(this::escalate);
      this.requestedAuthConfigs.putAll(other.requestedAuthConfigs());
      this.requestedToolConfirmations.putAll(other.requestedToolConfirmations());
      this.endOfAgent = other.endOfAgent();
      other.compaction().ifPresent(this::compaction);
      return this;
    }

    private static Object deepMerge(Object target, Object source) {
      if (!(target instanceof Map) || !(source instanceof Map)) {
        // If one of them is not a map, the source value overwrites the target.
        return source;
      }

      Map<?, ?> targetMap = (Map<?, ?>) target;
      Map<?, ?> sourceMap = (Map<?, ?>) source;

      if (!targetMap.isEmpty() && !sourceMap.isEmpty()) {
        Object targetKey = targetMap.keySet().iterator().next();
        Object sourceKey = sourceMap.keySet().iterator().next();
        if (targetKey != null
            && sourceKey != null
            && !targetKey.getClass().equals(sourceKey.getClass())) {
          throw new IllegalArgumentException(
              String.format(
                  "Cannot merge maps with different key types: %s vs %s",
                  targetKey.getClass().getName(), sourceKey.getClass().getName()));
        }
      }

      // Create a new map to prevent UnsupportedOperationException from immutable maps
      Map<Object, Object> mergedMap = new ConcurrentHashMap<>(targetMap);
      sourceMap.forEach((key, value) -> mergedMap.merge(key, value, Builder::deepMerge));
      return mergedMap;
    }

    public EventActions build() {
      return new EventActions(this);
    }
  }
}
