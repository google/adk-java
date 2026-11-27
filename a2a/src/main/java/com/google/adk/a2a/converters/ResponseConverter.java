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
package com.google.adk.a2a.converters;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for converting ADK events to A2A spec messages (and back).
 *
 * <p>Unparseable ADK metadata and unconvertible parts from a remote agent are logged and dropped;
 * the rest of the event is still converted.
 */
public final class ResponseConverter {
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private static final Logger logger = LoggerFactory.getLogger(ResponseConverter.class);
  private static final JavaType CUSTOM_METADATA_LIST_TYPE =
      objectMapper.getTypeFactory().constructCollectionType(List.class, CustomMetadata.class);
  // Custom metadata keys that hold a task or context ID.
  private static final ImmutableSet<String> REMOTE_ID_KEYS =
      ImmutableSet.of(
          AdkMetadataKey.TASK_ID.getType(),
          AdkMetadataKey.CONTEXT_ID.getType(),
          EventConverter.LEGACY_TASK_ID_KEY,
          EventConverter.LEGACY_CONTEXT_ID_KEY);
  // Keys of event fields whose value is an object. Citation is counted, as in ADK Python, but not
  // read into the event.
  private static final ImmutableList<String> OBJECT_FIELD_KEYS =
      ImmutableList.of(
          A2AMetadataKey.GROUNDING_METADATA.getType(),
          A2AMetadataKey.USAGE_METADATA.getType(),
          A2AMetadataKey.CUSTOM_METADATA.getType(),
          "adk_citation_metadata");

  private static final ImmutableSet<TaskState> PENDING_STATES =
      ImmutableSet.of(TaskState.WORKING, TaskState.SUBMITTED);

  private ResponseConverter() {}

  /**
   * Converts a A2A {@link ClientEvent} to an ADK {@link Event}, based on the event type. Returns an
   * empty optional if the event should be ignored: a TaskArtifactUpdateEvent that is not a final
   * update, a TaskStatusUpdateEvent with an empty message or none of whose parts convert and that
   * does not end the turn, or a message none of whose parts convert and that reports no error code.
   *
   * @throws IllegalArgumentException if the event type is not supported.
   */
  public static Optional<Event> clientEventToEvent(
      ClientEvent event, InvocationContext invocationContext) {
    if (event instanceof MessageEvent messageEvent) {
      return Optional.of(messageToEvent(messageEvent.getMessage(), invocationContext))
          .filter(converted -> hasParts(converted) || converted.errorCode().isPresent());
    } else if (event instanceof TaskEvent taskEvent) {
      return Optional.of(taskToEvent(taskEvent.getTask(), invocationContext));
    } else if (event instanceof TaskUpdateEvent updateEvent) {
      return handleTaskUpdate(updateEvent, invocationContext);
    }
    logger.warn("Unsupported ClientEvent type: {}", event.getClass());
    throw new IllegalArgumentException("Unsupported ClientEvent type: " + event.getClass());
  }

  private static boolean isPartial(@Nullable Map<String, Object> metadata) {
    if (metadata == null) {
      return false;
    }
    return Objects.equals(metadata.getOrDefault(A2AMetadataKey.PARTIAL.getType(), false), true);
  }

  private static boolean isLongRunning(@Nullable Map<String, Object> metadata) {
    return metadata != null
        && Objects.equals(metadata.get(A2AMetadataKey.IS_LONG_RUNNING.getType()), true);
  }

  /**
   * Converts a A2A {@link TaskUpdateEvent} to an ADK {@link Event}, if applicable. Returns empty if
   * the event is not a final update for TaskArtifactUpdateEvent, if the message is empty for
   * TaskStatusUpdateEvent, or if no part could be converted from the message of a status update
   * that does not end the turn.
   *
   * @throws IllegalArgumentException if the task update type is not supported.
   */
  private static Optional<Event> handleTaskUpdate(
      TaskUpdateEvent event, InvocationContext context) {
    var updateEvent = event.getUpdateEvent();

    if (updateEvent instanceof TaskArtifactUpdateEvent artifactEvent) {
      boolean isAppend = Objects.equals(artifactEvent.isAppend(), true);
      boolean isLastChunk = Objects.equals(artifactEvent.isLastChunk(), true);

      if (isLastChunk && isPartial(artifactEvent.getMetadata())) {
        return Optional.empty();
      }

      Event eventPart = artifactToEvent(artifactEvent.getArtifact(), context);
      if (!hasParts(eventPart)) {
        return Optional.empty();
      }
      eventPart.setPartial(isAppend || !isLastChunk);
      // append=true, lastChunk=false: emit as partial, update aggregation
      // append=false, lastChunk=false: emit as partial, reset aggregation
      // append=true, lastChunk=true: emit as partial, update aggregation and emit as non-partial
      // append=false, lastChunk=true: emit as non-partial, drop aggregation
      return Optional.of(
          updateEventMetadata(
              eventPart,
              mergeMetadata(artifactEvent.getArtifact().metadata(), artifactEvent.getMetadata()),
              artifactEvent.getTaskId(),
              artifactEvent.getContextId()));
    }

    if (updateEvent instanceof TaskStatusUpdateEvent statusEvent) {
      var status = statusEvent.getStatus();
      var taskState = event.getTask().getStatus().state();

      Message statusMessage = status.message();
      Optional<Event> messageEvent =
          Optional.ofNullable(statusMessage)
              .map(value -> {
                if (taskState == TaskState.FAILED) {
                  return messageToFailedEvent(value, context);
                }
                return messageToEvent(value, context, PENDING_STATES.contains(taskState));
              })
              .filter(converted -> taskState == TaskState.FAILED || hasParts(converted));

      if (statusEvent.isFinal()
          || taskState == TaskState.INPUT_REQUIRED
          || taskState == TaskState.AUTH_REQUIRED) {
        messageEvent =
            messageEvent
                .map(Event::toBuilder)
                .or(() -> Optional.of(remoteAgentEventBuilder(context)))
                .map(builder -> builder.turnComplete(true))
                .map(builder -> builder.partial(false))
                .map(Event.Builder::build);
      }
      Map<String, Object> metadata =
          mergeMetadata(
              statusMessage == null ? null : statusMessage.getMetadata(),
              statusEvent.getMetadata());
      return messageEvent.map(finalMessageEvent ->
          updateEventMetadata(
              finalMessageEvent, metadata, statusEvent.getTaskId(), statusEvent.getContextId()));
    }
    throw new IllegalArgumentException(
        "Unsupported TaskUpdateEvent type: " + updateEvent.getClass());
  }

  /** Converts an artifact to an ADK event. */
  public static Event artifactToEvent(Artifact artifact, InvocationContext invocationContext) {
    Event.Builder eventBuilder = remoteAgentEventBuilder(invocationContext);
    ConvertedParts converted = convertParts(artifact.parts());
    eventBuilder
        .content(fromModelParts(converted.parts()))
        .longRunningToolIds(converted.longRunningToolIds());
    return eventBuilder.build();
  }

  /** Converts an A2A message for a failed task to ADK event filling in the error message. */
  public static Event messageToFailedEvent(Message message, InvocationContext invocationContext) {
    Event.Builder builder = remoteAgentEventBuilder(invocationContext);
    Optional.ofNullable(Iterables.getFirst(message.getParts(), null))
        .flatMap(PartConverter::toTextPart)
        .ifPresent(textPart -> builder.errorMessage(textPart.getText()));

    return builder.build();
  }

  /** Converts an A2A message back to ADK events. */
  public static Event messageToEvent(Message message, InvocationContext invocationContext) {
    return updateEventMetadata(
        remoteAgentEventBuilder(invocationContext)
            .content(fromModelParts(convertParts(message.getParts()).parts()))
            .build(),
        message.getMetadata(),
        message.getTaskId(),
        message.getContextId());
  }

  /**
   * Converts an A2A message back to ADK events. For streaming task in pending state it sets the
   * thought field to true, to mark them as thought updates.
   */
  public static Event messageToEvent(
      Message message, InvocationContext invocationContext, boolean isPending) {

    ImmutableList<Part> genaiParts =
        convertParts(message.getParts()).parts().stream()
            .map(part -> part.toBuilder().thought(isPending).build())
            .collect(toImmutableList());

    return remoteAgentEventBuilder(invocationContext).content(fromModelParts(genaiParts)).build();
  }

  /**
   * Converts an A2A {@link Task} to an ADK {@link Event} with the parts of its artifacts and status
   * message. Its metadata comes from the first artifact, then the status message, that has an event
   * field, with keys missing there taken from the task's own metadata. A pending task with no parts
   * gives an empty event.
   */
  public static Event taskToEvent(Task task, InvocationContext invocationContext) {
    ImmutableList.Builder<Part> genaiParts = ImmutableList.builder();
    ImmutableSet.Builder<String> longRunningToolIds = ImmutableSet.builder();

    for (Artifact artifact : task.getArtifacts()) {
      ConvertedParts converted = convertParts(artifact.parts());
      longRunningToolIds.addAll(converted.longRunningToolIds());
      genaiParts.addAll(converted.parts());
    }

    Event.Builder eventBuilder = remoteAgentEventBuilder(invocationContext);

    if (task.getStatus().message() != null) {
      ConvertedParts convertedMessage = convertParts(task.getStatus().message().getParts());
      ImmutableList<Part> msgParts = convertedMessage.parts();
      longRunningToolIds.addAll(convertedMessage.longRunningToolIds());
      if (task.getStatus().state() == TaskState.FAILED
          && msgParts.size() == 1
          && msgParts.get(0).text().isPresent()) {
        eventBuilder.errorMessage(msgParts.get(0).text().get());
      } else {
        genaiParts.addAll(msgParts);
      }
    }

    ImmutableList<Part> finalParts = genaiParts.build();
    boolean isFinal =
        task.getStatus().state().isFinal()
            || task.getStatus().state() == TaskState.INPUT_REQUIRED
            || task.getStatus().state() == TaskState.AUTH_REQUIRED;

    if (finalParts.isEmpty() && !isFinal) {
      return emptyEvent(invocationContext);
    }
    if (!finalParts.isEmpty()) {
      eventBuilder.content(fromModelParts(finalParts));
    }
    if (task.getStatus().state() == TaskState.INPUT_REQUIRED
        || task.getStatus().state() == TaskState.AUTH_REQUIRED) {
      eventBuilder.longRunningToolIds(longRunningToolIds.build());
    }
    eventBuilder.turnComplete(isFinal);
    return updateEventMetadata(
        eventBuilder.build(),
        mergeMetadata(innerMetadata(task), task.getMetadata()),
        task.getId(),
        task.getContextId());
  }

  /**
   * Returns the metadata of the first of {@code task}'s artifacts, then its status message, that
   * has an event field, or an empty map if none does.
   */
  private static Map<String, Object> innerMetadata(Task task) {
    for (Artifact artifact : task.getArtifacts()) {
      if (hasEventFields(artifact.metadata())) {
        return artifact.metadata();
      }
    }
    Message statusMessage = task.getStatus().message();
    if (statusMessage != null && hasEventFields(statusMessage.getMetadata())) {
      return statusMessage.getMetadata();
    }
    return ImmutableMap.of();
  }

  /**
   * Returns whether {@code metadata} has an event field: a grounding, usage, custom or citation
   * metadata object, or an error code that is not JSON null. A JSON string is read first. Unlike
   * ADK Python, an object counts without being validated.
   */
  private static boolean hasEventFields(@Nullable Map<String, Object> metadata) {
    if (metadata == null) {
      return false;
    }
    for (String key : OBJECT_FIELD_KEYS) {
      if (readMetadataValue(metadata.get(key)) instanceof Map) {
        return true;
      }
    }
    return readMetadataValue(metadata.get(A2AMetadataKey.ERROR_CODE.getType())) != null;
  }

  /** Returns {@code value}, with a JSON string read into its value first. */
  private static @Nullable Object readMetadataValue(@Nullable Object value) {
    if (!(value instanceof String text)) {
      return value;
    }
    try {
      return objectMapper.readValue(text, Object.class);
    } catch (JsonProcessingException e) {
      // Text that is not JSON is the value itself.
      return text;
    }
  }

  /** GenAI parts converted from A2A parts, and the ids of the long-running calls among them. */
  private record ConvertedParts(
      ImmutableList<Part> parts, ImmutableSet<String> longRunningToolIds) {}

  /**
   * Converts peer-supplied parts, skipping any part that cannot be converted so that one malformed
   * part does not fail the whole event.
   */
  private static ConvertedParts convertParts(List<io.a2a.spec.Part<?>> a2aParts) {
    ImmutableList.Builder<Part> parts = ImmutableList.builder();
    ImmutableSet.Builder<String> longRunningToolIds = ImmutableSet.builder();
    for (io.a2a.spec.Part<?> a2aPart : a2aParts) {
      Part part;
      try {
        part = PartConverter.toGenaiPart(a2aPart);
      } catch (RuntimeException e) {
        // The exception message can quote the peer's bytes, so log only the types.
        logger.warn(
            "Skipping A2A part that could not be converted: {} ({})",
            a2aPart == null ? "null" : a2aPart.getClass().getSimpleName(),
            e.getClass().getSimpleName());
        continue;
      }
      parts.add(part);
      // A2A peers may omit metadata entirely, which deserializes to null.
      if (a2aPart instanceof DataPart dataPart && isLongRunning(dataPart.getMetadata())) {
        part.functionCall().flatMap(FunctionCall::id).ifPresent(longRunningToolIds::add);
      }
    }
    return new ConvertedParts(parts.build(), longRunningToolIds.build());
  }

  /**
   * Returns a copy of {@code inner}, the metadata of an artifact or status message, with missing or
   * null keys taken from {@code outer}, the task's or update's own metadata. {@code outer} is a
   * fallback for peers that write it there.
   */
  private static Map<String, Object> mergeMetadata(
      @Nullable Map<String, Object> inner, @Nullable Map<String, Object> outer) {
    Map<String, Object> merged = outer == null ? new HashMap<>() : new HashMap<>(outer);
    if (inner != null) {
      inner.forEach((key, value) -> {
        if (value != null) {
          merged.put(key, value);
        }
      });
    }
    return merged;
  }

  /**
   * Returns a copy of {@code event} with the grounding, usage, custom metadata and error code from
   * {@code clientMetadata}, plus {@code taskId} and {@code contextId} when not null. Task and
   * context IDs in the forwarded custom metadata are dropped, since they belong to calls the remote
   * agent made itself.
   */
  private static Event updateEventMetadata(
      Event event,
      @Nullable Map<String, Object> clientMetadata,
      @Nullable String taskId,
      @Nullable String contextId) {
    if (clientMetadata == null) {
      clientMetadata = ImmutableMap.of();
    }
    Event.Builder eventBuilder = event.toBuilder();
    eventBuilder.groundingMetadata(
        parseMetadata(clientMetadata, A2AMetadataKey.GROUNDING_METADATA, GroundingMetadata.class));
    eventBuilder.usageMetadata(
        parseMetadata(
            clientMetadata,
            A2AMetadataKey.USAGE_METADATA,
            GenerateContentResponseUsageMetadata.class));

    ImmutableList.Builder<CustomMetadata> customMetadataList = ImmutableList.builder();
    if (taskId != null) {
      customMetadataList.add(
          CustomMetadata.builder()
              .key(AdkMetadataKey.TASK_ID.getType())
              .stringValue(taskId)
              .build());
    }
    if (contextId != null) {
      customMetadataList.add(
          CustomMetadata.builder()
              .key(AdkMetadataKey.CONTEXT_ID.getType())
              .stringValue(contextId)
              .build());
    }
    // ADK Python sends custom metadata as a map, which this list cannot hold; it is skipped
    // quietly.
    List<CustomMetadata> parsedCustomMetadata =
        clientMetadata.get(A2AMetadataKey.CUSTOM_METADATA.getType()) instanceof Map
            ? null
            : parseMetadata(
                clientMetadata, A2AMetadataKey.CUSTOM_METADATA, CUSTOM_METADATA_LIST_TYPE);
    if (parsedCustomMetadata != null) {
      for (CustomMetadata m : parsedCustomMetadata) {
        // A peer can send a null entry, which is dropped like other malformed metadata.
        if (m != null && m.key().filter(REMOTE_ID_KEYS::contains).isEmpty()) {
          customMetadataList.add(m);
        }
      }
    }
    ImmutableList<CustomMetadata> customMetadata = customMetadataList.build();
    if (!customMetadata.isEmpty()) {
      eventBuilder.customMetadata(customMetadata);
    }

    eventBuilder.errorCode(errorCode(clientMetadata));

    return eventBuilder.build();
  }

  /**
   * Reads {@code key} out of the peer-supplied {@code clientMetadata} and deserializes it.
   *
   * <p>Returns null when the key is absent, and also when its value cannot be parsed: metadata is
   * peer-controlled, so a malformed value is logged and dropped rather than failing the whole
   * conversion.
   */
  private static <T> @Nullable T parseMetadata(
      Map<String, Object> clientMetadata, A2AMetadataKey key, Class<T> type) {
    return parseMetadata(clientMetadata, key, objectMapper.getTypeFactory().constructType(type));
  }

  /** Overload of {@link #parseMetadata(Map, A2AMetadataKey, Class)} for generic target types. */
  private static <T> @Nullable T parseMetadata(
      Map<String, Object> clientMetadata, A2AMetadataKey key, JavaType type) {
    Object metadata = clientMetadata.get(key.getType());
    try {
      if (metadata instanceof String jsonString) {
        return objectMapper.readValue(jsonString, type);
      } else {
        return objectMapper.convertValue(metadata, type);
      }
    } catch (IllegalArgumentException | JsonProcessingException e) {
      logDroppedMetadata(key, e);
      return null;
    }
  }

  /**
   * Returns the error code in {@code clientMetadata}. ADK Python and Go send it as a bare string; a
   * JSON string is decoded first, and other text, such as a number Go sends, is the error code
   * itself.
   */
  private static @Nullable FinishReason errorCode(Map<String, Object> clientMetadata) {
    if (!(clientMetadata.get(A2AMetadataKey.ERROR_CODE.getType()) instanceof String value)) {
      return parseMetadata(clientMetadata, A2AMetadataKey.ERROR_CODE, FinishReason.class);
    }
    if (value.isEmpty()) {
      return null;
    }
    try {
      FinishReason reason = objectMapper.readValue(value, FinishReason.class);
      // A JSON-encoded empty string reports no error code either, as in ADK Python.
      return reason == null || reason.toString().isEmpty() ? null : reason;
    } catch (JsonProcessingException e) {
      return new FinishReason(value);
    }
  }

  /**
   * Reports a dropped metadata value.
   *
   * <p>The parser's message quotes the peer's bytes, so the warning carries only the key and the
   * exception type. A peer that streams malformed metadata would otherwise be able to write
   * arbitrary content and a stack trace into the log on every event. The full exception is
   * available at debug level.
   */
  private static void logDroppedMetadata(A2AMetadataKey key, Exception e) {
    logger.warn(
        "Dropping unparseable A2A metadata for key {} ({})",
        key.getType(),
        e.getClass().getSimpleName());
    logger.debug("Unparseable A2A metadata for key {}", key.getType(), e);
  }

  private static Event emptyEvent(InvocationContext invocationContext) {
    Event.Builder builder =
        Event.builder()
            .id(invocationContext.newUuid())
            .invocationId(invocationContext.invocationId())
            .author(invocationContext.agent().name())
            .branch(invocationContext.branch().orElse(null))
            .content(Content.builder().role("user").parts(ImmutableList.of()).build())
            .timestamp(invocationContext.now().toEpochMilli());
    return builder.build();
  }

  private static Content fromModelParts(List<Part> parts) {
    return Content.builder().role("model").parts(parts).build();
  }

  private static boolean hasParts(Event event) {
    return !event.content().flatMap(Content::parts).orElse(ImmutableList.of()).isEmpty();
  }

  private static Event.Builder remoteAgentEventBuilder(InvocationContext invocationContext) {
    return Event.builder()
        .id(invocationContext.newUuid())
        .invocationId(invocationContext.invocationId())
        .author(invocationContext.agent().name())
        .branch(invocationContext.branch().orElse(null))
        .timestamp(invocationContext.now().toEpochMilli());
  }
}
