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
package com.google.adk.sessions.db.entity;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.db.converter.EventActionsUserType;
import com.google.adk.sessions.db.converter.JsonUserType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.hibernate.annotations.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entity for storing event data in the database. This is mapped to the "events" table. */
@Entity
@Table(name = "events")
@IdClass(EventId.class)
public class StorageEvent {

  private static final Logger logger = LoggerFactory.getLogger(StorageEvent.class);

  @Id
  @Column(name = "id", length = 128)
  private String id;

  @Id
  @Column(name = "app_name", length = 128)
  private String appName;

  @Id
  @Column(name = "user_id", length = 128)
  private String userId;

  @Id
  @Column(name = "session_id", length = 128)
  private String sessionId;

  @Column(name = "invocation_id", length = 256)
  private String invocationId;

  @Column(name = "author", length = 256)
  private String author;

  @Column(name = "actions")
  @Type(EventActionsUserType.class)
  private EventActions actions;

  @Column(name = "timestamp")
  private Instant timestamp;

  @Column(name = "content")
  @Type(JsonUserType.class)
  private Map<String, Object> content;

  @Column(name = "grounding_metadata")
  @Type(JsonUserType.class)
  private Map<String, Object> groundingMetadata;

  @Column(name = "custom_metadata")
  @Type(JsonUserType.class)
  private Map<String, Object> customMetadata;

  @Column(name = "usage_metadata")
  @Type(JsonUserType.class)
  private Map<String, Object> usageMetadata;

  @Column(name = "citation_metadata")
  @Type(JsonUserType.class)
  private Map<String, Object> citationMetadata;

  @Column(name = "partial")
  private Boolean partial;

  @Column(name = "turn_complete")
  private Boolean turnComplete;

  @Column(name = "error_code", length = 256)
  private String errorCode;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "interrupted")
  private Boolean interrupted;

  @Column(name = "branch", length = 256)
  private String branch;

  @Column(name = "long_running_tool_ids_json", columnDefinition = "TEXT")
  private String longRunningToolIdsJson;

  @Column(name = "input_transcription")
  @Type(JsonUserType.class)
  private Map<String, Object> inputTranscription;

  @Column(name = "output_transcription")
  @Type(JsonUserType.class)
  private Map<String, Object> outputTranscription;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumns({
    @JoinColumn(
        name = "app_name",
        referencedColumnName = "app_name",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "user_id",
        referencedColumnName = "user_id",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "session_id",
        referencedColumnName = "id",
        insertable = false,
        updatable = false)
  })
  private StorageSession session;

  // Default constructor
  public StorageEvent() {}

  // Getters and setters
  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getAppName() {
    return appName;
  }

  public void setAppName(String appName) {
    this.appName = appName;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getInvocationId() {
    return invocationId;
  }

  public void setInvocationId(String invocationId) {
    this.invocationId = invocationId;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public EventActions getActions() {
    return actions;
  }

  public void setActions(EventActions actions) {
    this.actions = actions;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(Instant timestamp) {
    this.timestamp = timestamp;
  }

  public Map<String, Object> getContent() {
    return content;
  }

  public void setContent(Map<String, Object> content) {
    this.content = content;
  }

  public Map<String, Object> getGroundingMetadata() {
    return groundingMetadata;
  }

  public void setGroundingMetadata(Map<String, Object> groundingMetadata) {
    this.groundingMetadata = groundingMetadata;
  }

  public Map<String, Object> getCustomMetadata() {
    return customMetadata;
  }

  public void setCustomMetadata(Map<String, Object> customMetadata) {
    this.customMetadata = customMetadata;
  }

  public Map<String, Object> getUsageMetadata() {
    return usageMetadata;
  }

  public void setUsageMetadata(Map<String, Object> usageMetadata) {
    this.usageMetadata = usageMetadata;
  }

  public Map<String, Object> getCitationMetadata() {
    return citationMetadata;
  }

  public void setCitationMetadata(Map<String, Object> citationMetadata) {
    this.citationMetadata = citationMetadata;
  }

  public Boolean getPartial() {
    return partial;
  }

  public void setPartial(Boolean partial) {
    this.partial = partial;
  }

  public Boolean getTurnComplete() {
    return turnComplete;
  }

  public void setTurnComplete(Boolean turnComplete) {
    this.turnComplete = turnComplete;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(String errorCode) {
    this.errorCode = errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Boolean getInterrupted() {
    return interrupted;
  }

  public void setInterrupted(Boolean interrupted) {
    this.interrupted = interrupted;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public String getLongRunningToolIdsJson() {
    return longRunningToolIdsJson;
  }

  public void setLongRunningToolIdsJson(String longRunningToolIdsJson) {
    this.longRunningToolIdsJson = longRunningToolIdsJson;
  }

  public Map<String, Object> getInputTranscription() {
    return inputTranscription;
  }

  public void setInputTranscription(Map<String, Object> inputTranscription) {
    this.inputTranscription = inputTranscription;
  }

  public Map<String, Object> getOutputTranscription() {
    return outputTranscription;
  }

  public void setOutputTranscription(Map<String, Object> outputTranscription) {
    this.outputTranscription = outputTranscription;
  }

  public StorageSession getSession() {
    return session;
  }

  public void setSession(StorageSession session) {
    this.session = session;
  }

  /**
   * Converts a storage entity to a domain model Event.
   *
   * @return A domain Event object created from this storage entity
   */
  public Event toDomainEvent() {
    Event.Builder builder =
        Event.builder().id(this.id).invocationId(this.invocationId).author(this.author);

    if (this.actions != null) {
      builder.actions(this.actions);
    }

    // Handle Optional<Boolean> fields
    builder.partial(Optional.ofNullable(this.partial));
    builder.turnComplete(Optional.ofNullable(this.turnComplete));
    builder.interrupted(Optional.ofNullable(this.interrupted));

    // Set timestamp (long expected)
    if (this.timestamp != null) {
      builder.timestamp(this.timestamp.toEpochMilli());
    }

    // Handle content map to Content object
    if (this.content != null) {
      try {
        com.google.genai.types.Content contentObj = deserializeContent(this.content);
        builder.content(Optional.of(contentObj));
      } catch (Exception e) {
        logger.warn("Failed to deserialize content for event {}: {}", this.id, e.getMessage(), e);
        builder.content(Optional.empty());
      }
    }

    if (this.errorCode != null && !this.errorCode.isEmpty()) {
      builder.errorCode(Optional.of(new com.google.genai.types.FinishReason(this.errorCode)));
    }

    if (this.errorMessage != null) {
      // Use explicit Optional.of() to avoid ambiguity
      builder.errorMessage(Optional.of(this.errorMessage));
    }

    if (this.groundingMetadata != null && !this.groundingMetadata.isEmpty()) {
      try {
        String json =
            com.google.adk.JsonBaseModel.getMapper().writeValueAsString(this.groundingMetadata);
        com.google.genai.types.GroundingMetadata metadata =
            com.google.adk.JsonBaseModel.getMapper()
                .readValue(json, com.google.genai.types.GroundingMetadata.class);
        builder.groundingMetadata(Optional.of(metadata));
      } catch (Exception e) {
        logger.warn(
            "Failed to deserialize grounding metadata for event {}: {}",
            this.id,
            e.getMessage(),
            e);
        builder.groundingMetadata(Optional.empty());
      }
    }

    // Set branch from storage
    builder.branch(Optional.ofNullable(this.branch));

    return builder.build();
  }

  /**
   * Creates a StorageEvent entity from a domain Event model.
   *
   * @param event The domain Event to convert
   * @param session The parent StorageSession
   * @return A StorageEvent entity
   */
  public static StorageEvent fromDomainEvent(Event event, StorageSession session) {
    StorageEvent storageEvent = new StorageEvent();
    storageEvent.setId(event.id());
    storageEvent.setAppName(session.getAppName());
    storageEvent.setUserId(session.getUserId());
    storageEvent.setSessionId(session.getId());
    storageEvent.setSession(session);
    storageEvent.setInvocationId(event.invocationId());
    storageEvent.setAuthor(event.author());

    storageEvent.setActions(event.actions());

    // Convert long timestamp to Instant
    storageEvent.setTimestamp(Instant.ofEpochMilli(event.timestamp()));

    // Handle content - Convert Content to Map if present
    event
        .content()
        .ifPresent(
            content -> {
              try {
                String json = com.google.adk.JsonBaseModel.getMapper().writeValueAsString(content);
                @SuppressWarnings("unchecked")
                Map<String, Object> contentMap =
                    com.google.adk.JsonBaseModel.getMapper()
                        .readValue(
                            json,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
                storageEvent.setContent(contentMap);
              } catch (Exception e) {
                logger.warn(
                    "Failed to serialize content for event {}: {}", event.id(), e.getMessage(), e);
              }
            });

    event
        .groundingMetadata()
        .ifPresent(
            metadata -> {
              try {
                String json = com.google.adk.JsonBaseModel.getMapper().writeValueAsString(metadata);
                @SuppressWarnings("unchecked")
                Map<String, Object> metadataMap =
                    com.google.adk.JsonBaseModel.getMapper()
                        .readValue(
                            json,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
                storageEvent.setGroundingMetadata(metadataMap);
              } catch (Exception e) {
                logger.warn(
                    "Failed to serialize grounding metadata for event {}: {}",
                    event.id(),
                    e.getMessage(),
                    e);
              }
            });

    // Handle Boolean fields that are now Optional
    storageEvent.setPartial(event.partial().orElse(null));
    storageEvent.setTurnComplete(event.turnComplete().orElse(null));
    storageEvent.setInterrupted(event.interrupted().orElse(null));

    // Handle error code - FinishReason enum to String
    event
        .errorCode()
        .ifPresent(
            code -> {
              // We can't directly use name(), so store the toString() result
              storageEvent.setErrorCode(code.toString());
            });

    // Handle error message
    event.errorMessage().ifPresent(storageEvent::setErrorMessage);

    // Set the parent session
    storageEvent.setSession(session);
    return storageEvent;
  }

  /**
   * Deserializes a Content object from a Map.
   *
   * @param contentMap The map containing content data
   * @return A Content object
   */
  private static com.google.genai.types.Content deserializeContent(Map<String, Object> contentMap) {
    if (contentMap == null) {
      return null;
    }

    try {
      return com.google.adk.JsonBaseModel.getMapper()
          .convertValue(contentMap, com.google.genai.types.Content.class);
    } catch (IllegalArgumentException e) {
      logger.warn("Failed to deserialize content from map: {}", e.getMessage(), e);
      return null;
    }
  }
}
