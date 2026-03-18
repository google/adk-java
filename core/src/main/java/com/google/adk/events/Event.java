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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

// TODO - b/413761119 update Agent.java when resolved.
/** Represents an event in a session. */
@JsonDeserialize(builder = Event.Builder.class)
public class Event extends JsonBaseModel {

  private String id;
  private String invocationId;
  private String author;
  private @Nullable Content content;
  private EventActions actions;
  private @Nullable Set<String> longRunningToolIds;
  private @Nullable Boolean partial;
  private @Nullable Boolean turnComplete;
  private @Nullable FinishReason errorCode;
  private @Nullable String errorMessage;
  private @Nullable FinishReason finishReason;
  private @Nullable GenerateContentResponseUsageMetadata usageMetadata;
  private @Nullable Double avgLogprobs;
  private @Nullable Boolean interrupted;
  private @Nullable String branch;
  private @Nullable GroundingMetadata groundingMetadata;
  private @Nullable List<CustomMetadata> customMetadata;
  private @Nullable String modelVersion;
  private long timestamp;

  private Event() {}

  public static String generateEventId() {
    return UUID.randomUUID().toString();
  }

  /** The event id. */
  @JsonProperty("id")
  public String id() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  /** Id of the invocation that this event belongs to. */
  @JsonProperty("invocationId")
  public String invocationId() {
    return invocationId;
  }

  public void setInvocationId(String invocationId) {
    this.invocationId = invocationId;
  }

  /** The author of the event, it could be the name of the agent or "user" literal. */
  @JsonProperty("author")
  public String author() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  @JsonProperty("content")
  public Optional<Content> content() {
    return Optional.ofNullable(content);
  }

  public void setContent(@Nullable Content content) {
    this.content = content;
  }

  @JsonProperty("actions")
  public EventActions actions() {
    return actions;
  }

  public void setActions(EventActions actions) {
    this.actions = actions;
  }

  /**
   * Set of ids of the long running function calls. Agent client will know from this field about
   * which function call is long running.
   */
  @JsonProperty("longRunningToolIds")
  public Optional<Set<String>> longRunningToolIds() {
    return Optional.ofNullable(longRunningToolIds);
  }

  public void setLongRunningToolIds(@Nullable Set<String> longRunningToolIds) {
    this.longRunningToolIds = longRunningToolIds;
  }

  /**
   * partial is true for incomplete chunks from the LLM streaming response. The last chunk's partial
   * is False.
   */
  @JsonProperty("partial")
  public Optional<Boolean> partial() {
    return Optional.ofNullable(partial);
  }

  public void setPartial(@Nullable Boolean partial) {
    this.partial = partial;
  }

  @JsonProperty("turnComplete")
  public Optional<Boolean> turnComplete() {
    return Optional.ofNullable(turnComplete);
  }

  public void setTurnComplete(@Nullable Boolean turnComplete) {
    this.turnComplete = turnComplete;
  }

  @JsonProperty("errorCode")
  public Optional<FinishReason> errorCode() {
    return Optional.ofNullable(errorCode);
  }

  @JsonProperty("finishReason")
  public Optional<FinishReason> finishReason() {
    return Optional.ofNullable(finishReason);
  }

  public void setErrorCode(@Nullable FinishReason errorCode) {
    this.errorCode = errorCode;
  }

  @Deprecated
  @SuppressWarnings("checkstyle:IllegalType")
  public void setFinishReason(Optional<FinishReason> finishReason) {
    this.finishReason = finishReason.orElse(null);
  }

  public void setFinishReason(@Nullable FinishReason finishReason) {
    this.finishReason = finishReason;
  }

  @JsonProperty("errorMessage")
  public Optional<String> errorMessage() {
    return Optional.ofNullable(errorMessage);
  }

  public void setErrorMessage(@Nullable String errorMessage) {
    this.errorMessage = errorMessage;
  }

  @JsonProperty("usageMetadata")
  public Optional<GenerateContentResponseUsageMetadata> usageMetadata() {
    return Optional.ofNullable(usageMetadata);
  }

  public void setUsageMetadata(@Nullable GenerateContentResponseUsageMetadata usageMetadata) {
    this.usageMetadata = usageMetadata;
  }

  @JsonProperty("avgLogprobs")
  public Optional<Double> avgLogprobs() {
    return Optional.ofNullable(avgLogprobs);
  }

  public void setAvgLogprobs(@Nullable Double avgLogprobs) {
    this.avgLogprobs = avgLogprobs;
  }

  @JsonProperty("interrupted")
  public Optional<Boolean> interrupted() {
    return Optional.ofNullable(interrupted);
  }

  public void setInterrupted(@Nullable Boolean interrupted) {
    this.interrupted = interrupted;
  }

  /**
   * The branch of the event. The format is like agent_1.agent_2.agent_3, where agent_1 is the
   * parent of agent_2, and agent_2 is the parent of agent_3. Branch is used when multiple sub-agent
   * shouldn't see their peer agents' conversation history.
   */
  @JsonProperty("branch")
  public Optional<String> branch() {
    return Optional.ofNullable(branch);
  }

  /**
   * Sets the branch for this event.
   *
   * <p>Format: agentA.agentB.agentC — shows hierarchy of nested agents.
   *
   * @param branch Branch identifier.
   */
  public void branch(@Nullable String branch) {
    this.branch = branch;
  }

  /** The grounding metadata of the event. */
  @JsonProperty("groundingMetadata")
  public Optional<GroundingMetadata> groundingMetadata() {
    return Optional.ofNullable(groundingMetadata);
  }

  public void setGroundingMetadata(@Nullable GroundingMetadata groundingMetadata) {
    this.groundingMetadata = groundingMetadata;
  }

  /** The custom metadata of the event. */
  @JsonProperty("customMetadata")
  public Optional<List<CustomMetadata>> customMetadata() {
    return Optional.ofNullable(customMetadata);
  }

  public void setCustomMetadata(@Nullable List<CustomMetadata> customMetadata) {
    this.customMetadata = customMetadata;
  }

  /** The model version used to generate the response. */
  @JsonProperty("modelVersion")
  public Optional<String> modelVersion() {
    return Optional.ofNullable(modelVersion);
  }

  public void setModelVersion(@Nullable String modelVersion) {
    this.modelVersion = modelVersion;
  }

  /** The timestamp of the event. */
  @JsonProperty("timestamp")
  public long timestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  /** Returns all function calls from this event. */
  @JsonIgnore
  public final ImmutableList<FunctionCall> functionCalls() {
    return content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        .flatMap(part -> part.functionCall().stream())
        .collect(toImmutableList());
  }

  /** Returns all function responses from this event. */
  @JsonIgnore
  public final ImmutableList<FunctionResponse> functionResponses() {
    return content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        .flatMap(part -> part.functionResponse().stream())
        .collect(toImmutableList());
  }

  /** Returns whether the event has a trailing code execution result. */
  @JsonIgnore
  public final boolean hasTrailingCodeExecutionResult() {
    return content()
        .flatMap(Content::parts)
        .filter(parts -> !parts.isEmpty())
        .map(parts -> Iterables.getLast(parts))
        .flatMap(part -> part.codeExecutionResult())
        .isPresent();
  }

  /** Returns true if this is a final response. */
  @JsonIgnore
  public final boolean finalResponse() {
    if (actions().skipSummarization().orElse(false)) {
      return true;
    }
    return functionCalls().isEmpty()
        && functionResponses().isEmpty()
        && !partial().orElse(false)
        && !hasTrailingCodeExecutionResult();
  }

  /**
   * Converts the event content into a readable string.
   *
   * <p>Includes text, function calls, and responses.
   *
   * @return Stringified content.
   */
  public final String stringifyContent() {
    StringBuilder sb = new StringBuilder();
    content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        .forEach(
            part -> {
              part.text().ifPresent(sb::append);
              part.functionCall()
                  .ifPresent(functionCall -> sb.append("Function Call: ").append(functionCall));
              part.functionResponse()
                  .ifPresent(
                      functionResponse ->
                          sb.append("Function Response: ").append(functionResponse));
            });
    return sb.toString();
  }

  /** Builder for {@link Event}. */
  public static class Builder {

    private String id;
    private String invocationId;
    private String author;
    private @Nullable Content content;
    private EventActions actions;
    private @Nullable Set<String> longRunningToolIds;
    private @Nullable Boolean partial;
    private @Nullable Boolean turnComplete;
    private @Nullable FinishReason errorCode;
    private @Nullable String errorMessage;
    private @Nullable FinishReason finishReason;
    private @Nullable GenerateContentResponseUsageMetadata usageMetadata;
    private @Nullable Double avgLogprobs;
    private @Nullable Boolean interrupted;
    private @Nullable String branch;
    private @Nullable GroundingMetadata groundingMetadata;
    private @Nullable List<CustomMetadata> customMetadata;
    private @Nullable String modelVersion;
    private @Nullable Long timestamp;

    @JsonCreator
    private static Builder create() {
      return new Builder();
    }

    @CanIgnoreReturnValue
    @JsonProperty("id")
    public Builder id(String value) {
      this.id = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("invocationId")
    public Builder invocationId(String value) {
      this.invocationId = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("author")
    public Builder author(String value) {
      this.author = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("content")
    public Builder content(@Nullable Content value) {
      this.content = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("actions")
    public Builder actions(EventActions value) {
      this.actions = value;
      return this;
    }

    Optional<EventActions> actions() {
      return Optional.ofNullable(actions);
    }

    @CanIgnoreReturnValue
    @JsonProperty("longRunningToolIds")
    public Builder longRunningToolIds(@Nullable Set<String> value) {
      this.longRunningToolIds = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("partial")
    public Builder partial(@Nullable Boolean value) {
      this.partial = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("turnComplete")
    public Builder turnComplete(@Nullable Boolean value) {
      this.turnComplete = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("errorCode")
    public Builder errorCode(@Nullable FinishReason value) {
      this.errorCode = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("errorMessage")
    public Builder errorMessage(@Nullable String value) {
      this.errorMessage = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("finishReason")
    public Builder finishReason(@Nullable FinishReason value) {
      this.finishReason = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("usageMetadata")
    public Builder usageMetadata(@Nullable GenerateContentResponseUsageMetadata value) {
      this.usageMetadata = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("avgLogprobs")
    public Builder avgLogprobs(@Nullable Double value) {
      this.avgLogprobs = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("interrupted")
    public Builder interrupted(@Nullable Boolean value) {
      this.interrupted = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("timestamp")
    public Builder timestamp(long value) {
      this.timestamp = value;
      return this;
    }

    // Getter for builder's timestamp, used in build()
    Optional<Long> timestamp() {
      return Optional.ofNullable(timestamp);
    }

    @CanIgnoreReturnValue
    @JsonProperty("branch")
    public Builder branch(@Nullable String value) {
      this.branch = value;
      return this;
    }

    // Getter for builder's branch, used in build()
    Optional<String> branch() {
      return Optional.ofNullable(branch);
    }

    @CanIgnoreReturnValue
    @JsonProperty("groundingMetadata")
    public Builder groundingMetadata(@Nullable GroundingMetadata value) {
      this.groundingMetadata = value;
      return this;
    }

    Optional<GroundingMetadata> groundingMetadata() {
      return Optional.ofNullable(groundingMetadata);
    }

    @CanIgnoreReturnValue
    @JsonProperty("customMetadata")
    public Builder customMetadata(@Nullable List<CustomMetadata> value) {
      this.customMetadata = value;
      return this;
    }

    @CanIgnoreReturnValue
    @JsonProperty("modelVersion")
    public Builder modelVersion(@Nullable String value) {
      this.modelVersion = value;
      return this;
    }

    public Event build() {
      Event event = new Event();
      event.setId(id);
      event.setInvocationId(invocationId);
      event.setAuthor(author);
      event.setContent(content);
      event.setLongRunningToolIds(longRunningToolIds);
      event.setPartial(partial);
      event.setTurnComplete(turnComplete);
      event.setErrorCode(errorCode);
      event.setErrorMessage(errorMessage);
      event.setFinishReason(finishReason);
      event.setUsageMetadata(usageMetadata);
      event.setAvgLogprobs(avgLogprobs);
      event.setInterrupted(interrupted);
      event.branch(branch);
      event.setGroundingMetadata(groundingMetadata);
      event.setCustomMetadata(customMetadata);
      event.setModelVersion(modelVersion);
      event.setActions(actions().orElseGet(() -> EventActions.builder().build()));
      event.setTimestamp(timestamp().orElseGet(() -> Instant.now().toEpochMilli()));
      return event;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Parses an event from a JSON string. */
  public static Event fromJson(String json) {
    return fromJsonString(json, Event.class);
  }

  /** Creates a builder pre-filled with this event's values. */
  public Builder toBuilder() {
    Builder builder =
        new Builder()
            .id(this.id)
            .invocationId(this.invocationId)
            .author(this.author)
            .content(this.content)
            .actions(this.actions)
            .longRunningToolIds(this.longRunningToolIds)
            .partial(this.partial)
            .turnComplete(this.turnComplete)
            .errorCode(this.errorCode)
            .errorMessage(this.errorMessage)
            .finishReason(this.finishReason)
            .usageMetadata(this.usageMetadata)
            .avgLogprobs(this.avgLogprobs)
            .interrupted(this.interrupted)
            .branch(this.branch)
            .groundingMetadata(this.groundingMetadata)
            .customMetadata(this.customMetadata)
            .modelVersion(this.modelVersion);
    if (this.timestamp != 0) {
      builder.timestamp(this.timestamp);
    }
    return builder;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof Event other)) {
      return false;
    }
    return timestamp == other.timestamp
        && Objects.equals(id, other.id)
        && Objects.equals(invocationId, other.invocationId)
        && Objects.equals(author, other.author)
        && Objects.equals(content, other.content)
        && Objects.equals(actions, other.actions)
        && Objects.equals(longRunningToolIds, other.longRunningToolIds)
        && Objects.equals(partial, other.partial)
        && Objects.equals(turnComplete, other.turnComplete)
        && Objects.equals(errorCode, other.errorCode)
        && Objects.equals(errorMessage, other.errorMessage)
        && Objects.equals(finishReason, other.finishReason)
        && Objects.equals(usageMetadata, other.usageMetadata)
        && Objects.equals(avgLogprobs, other.avgLogprobs)
        && Objects.equals(interrupted, other.interrupted)
        && Objects.equals(branch, other.branch)
        && Objects.equals(groundingMetadata, other.groundingMetadata)
        && Objects.equals(customMetadata, other.customMetadata)
        && Objects.equals(modelVersion, other.modelVersion);
  }

  @Override
  public String toString() {
    return toJson();
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id,
        invocationId,
        author,
        content,
        actions,
        longRunningToolIds,
        partial,
        turnComplete,
        errorCode,
        errorMessage,
        finishReason,
        usageMetadata,
        avgLogprobs,
        interrupted,
        branch,
        groundingMetadata,
        customMetadata,
        modelVersion,
        timestamp);
  }
}
