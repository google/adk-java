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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Streams.zip;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility for converting ADK events to A2A spec messages (and back). */
public final class ResponseConverter {
  private static final Logger logger = LoggerFactory.getLogger(ResponseConverter.class);
  private static final ImmutableSet<TaskState> PENDING_STATES =
      ImmutableSet.of(TaskState.WORKING, TaskState.SUBMITTED);

  private ResponseConverter() {}

  /**
   * Converts a A2A {@link ClientEvent} to an ADK {@link Event}, based on the event type. Returns an
   * empty optional if the event should be ignored (e.g. if the event is not a final update for
   * TaskArtifactUpdateEvent or if the message is empty for TaskStatusUpdateEvent).
   *
   * @throws IllegalArgumentException if the event type is not supported.
   */
  public static Optional<Event> clientEventToEvent(
      ClientEvent event, InvocationContext invocationContext) {
    if (event instanceof MessageEvent messageEvent) {
      return Optional.of(messageToEvent(messageEvent.getMessage(), invocationContext));
    } else if (event instanceof TaskEvent taskEvent) {
      return Optional.of(taskToEvent(taskEvent.getTask(), invocationContext));
    } else if (event instanceof TaskUpdateEvent updateEvent) {
      return handleTaskUpdate(updateEvent, invocationContext);
    }
    logger.warn("Unsupported ClientEvent type: {}", event.getClass());
    throw new IllegalArgumentException("Unsupported ClientEvent type: " + event.getClass());
  }

  private static boolean isPartial(Map<String, Object> metadata) {
    if (metadata == null) {
      return false;
    }
    return Objects.equals(
        metadata.getOrDefault(PartConverter.A2A_DATA_PART_METADATA_IS_PARTIAL_KEY, false), true);
  }

  /**
   * Converts a A2A {@link TaskUpdateEvent} to an ADK {@link Event}, if applicable. Returns null if
   * the event is not a final update for TaskArtifactUpdateEvent or if the message is empty for
   * TaskStatusUpdateEvent.
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
      if (eventPart.content().flatMap(Content::parts).orElse(ImmutableList.of()).isEmpty()) {
        return Optional.empty();
      }
      eventPart.setPartial(isAppend || !isLastChunk);
      // append=true, lastChunk=false: emit as partial, update aggregation
      // append=false, lastChunk=false: emit as partial, reset aggregation
      // append=true, lastChunk=true: emit as partial, update aggregation and emit as non-partial
      // append=false, lastChunk=true: emit as non-partial, drop aggregation
      return Optional.of(eventPart);
    }

    if (updateEvent instanceof TaskStatusUpdateEvent statusEvent) {
      var status = statusEvent.getStatus();
      var taskState = event.getTask().getStatus().state();

      Optional<Event> messageEvent =
          Optional.ofNullable(status.message())
              .map(
                  value -> {
                    if (taskState == TaskState.FAILED) {
                      return messageToFailedEvent(value, context);
                    }
                    return messageToEvent(value, context, PENDING_STATES.contains(taskState));
                  });

      if (statusEvent.isFinal()) {
        return messageEvent
            .map(Event::toBuilder)
            .or(() -> Optional.of(remoteAgentEventBuilder(context)))
            .map(builder -> builder.turnComplete(true))
            .map(builder -> builder.partial(false))
            .map(Event.Builder::build);
      }
      return messageEvent;
    }
    throw new IllegalArgumentException(
        "Unsupported TaskUpdateEvent type: " + updateEvent.getClass());
  }

  /** Converts an artifact to an ADK event. */
  public static Event artifactToEvent(Artifact artifact, InvocationContext invocationContext) {
    Event.Builder eventBuilder = remoteAgentEventBuilder(invocationContext);
    ImmutableList<Part> genaiParts = PartConverter.toGenaiParts(artifact.parts());
    eventBuilder
        .content(fromModelParts(genaiParts))
        .longRunningToolIds(getLongRunningToolIds(artifact.parts(), genaiParts));
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
    return remoteAgentEventBuilder(invocationContext)
        .content(fromModelParts(PartConverter.toGenaiParts(message.getParts())))
        .build();
  }

  /**
   * Converts an A2A message back to ADK events. For streaming task in pending state it sets the
   * thought field to true, to mark them as thought updates.
   */
  public static Event messageToEvent(
      Message message, InvocationContext invocationContext, boolean isPending) {

    ImmutableList<Part> genaiParts =
        PartConverter.toGenaiParts(message.getParts()).stream()
            .map(part -> part.toBuilder().thought(isPending).build())
            .collect(toImmutableList());

    return remoteAgentEventBuilder(invocationContext).content(fromModelParts(genaiParts)).build();
  }

  /**
   * Converts an A2A {@link Task} to an ADK {@link Event}. If the artifacts are present, the last
   * artifact is used. If not, the status message is used. If not, the last history message is used.
   * If none of these are present, an empty event is returned.
   */
  public static Event taskToEvent(Task task, InvocationContext invocationContext) {
    ImmutableList.Builder<Part> genaiParts = ImmutableList.builder();
    ImmutableSet.Builder<String> longRunningToolIds = ImmutableSet.builder();

    for (Artifact artifact : task.getArtifacts()) {
      ImmutableList<Part> converted = PartConverter.toGenaiParts(artifact.parts());
      longRunningToolIds.addAll(getLongRunningToolIds(artifact.parts(), converted));
      genaiParts.addAll(converted);
    }

    Event.Builder eventBuilder = remoteAgentEventBuilder(invocationContext);

    if (task.getStatus().message() != null) {
      ImmutableList<Part> msgParts =
          PartConverter.toGenaiParts(task.getStatus().message().getParts());
      longRunningToolIds.addAll(
          getLongRunningToolIds(task.getStatus().message().getParts(), msgParts));
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
        task.getStatus().state().isFinal() || task.getStatus().state() == TaskState.INPUT_REQUIRED;

    if (finalParts.isEmpty() && !isFinal) {
      return emptyEvent(invocationContext);
    }
    if (!finalParts.isEmpty()) {
      eventBuilder.content(fromModelParts(finalParts));
    }
    if (task.getStatus().state() == TaskState.INPUT_REQUIRED) {
      eventBuilder.longRunningToolIds(longRunningToolIds.build());
    }
    eventBuilder.turnComplete(isFinal);
    return eventBuilder.build();
  }

  private static ImmutableSet<String> getLongRunningToolIds(
      List<io.a2a.spec.Part<?>> parts, List<Part> convertedParts) {
    return zip(
            parts.stream(),
            convertedParts.stream(),
            (part, convertedPart) -> {
              if (!(part instanceof DataPart dataPart)) {
                return Optional.<String>empty();
              }
              Object isLongRunning =
                  dataPart
                      .getMetadata()
                      .get(PartConverter.A2A_DATA_PART_METADATA_IS_LONG_RUNNING_KEY);
              if (!Objects.equals(isLongRunning, true)) {
                return Optional.<String>empty();
              }
              if (convertedPart.functionCall().isEmpty()) {
                return Optional.<String>empty();
              }
              return convertedPart.functionCall().get().id();
            })
        .flatMap(Optional::stream)
        .collect(toImmutableSet());
  }

  private static Event emptyEvent(InvocationContext invocationContext) {
    Event.Builder builder =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId(invocationContext.invocationId())
            .author(invocationContext.agent().name())
            .branch(invocationContext.branch().orElse(null))
            .content(Content.builder().role("user").parts(ImmutableList.of()).build())
            .timestamp(Instant.now().toEpochMilli());
    return builder.build();
  }

  private static Content fromModelParts(List<Part> parts) {
    return Content.builder().role("model").parts(parts).build();
  }

  private static Event.Builder remoteAgentEventBuilder(InvocationContext invocationContext) {
    return Event.builder()
        .id(UUID.randomUUID().toString())
        .invocationId(invocationContext.invocationId())
        .author(invocationContext.agent().name())
        .branch(invocationContext.branch().orElse(null))
        .timestamp(Instant.now().toEpochMilli());
  }
}
