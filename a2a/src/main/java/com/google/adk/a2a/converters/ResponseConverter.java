package com.google.adk.a2a.converters;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.EventKind;
import io.a2a.spec.Message;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for converting ADK events to A2A spec messages (and back).
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public final class ResponseConverter {
  private static final Logger logger = LoggerFactory.getLogger(ResponseConverter.class);
  private static final ImmutableSet<TaskState> PENDING_STATES =
      ImmutableSet.of(TaskState.WORKING, TaskState.SUBMITTED);

  private ResponseConverter() {}


  /**
   * Converts a {@link SendMessageResponse} containing a {@link Message} result into ADK events.
   *
   * <p>Non-message results are ignored in the message-only integration and logged for awareness.
   */
  public static List<Event> sendMessageResponseToEvents(
      SendMessageResponse response, String invocationId, String branch) {
    if (response == null) {
      logger.warn("SendMessageResponse was null; returning no events.");
      return ImmutableList.of();
    }

    EventKind result = response.getResult();
    if (result == null) {
      logger.warn("SendMessageResponse result was null for invocation {}", invocationId);
      return ImmutableList.of();
    }

    if (result instanceof Message message) {
      return messageToEvents(message, invocationId, branch);
    }

    logger.warn(
        "Unsupported SendMessageResponse result type {} for invocation {}; expected Message",
        result.getClass().getSimpleName(),
        invocationId);
    return ImmutableList.of();
  }

  /** Converts an A2A message back to ADK events. */
  public static List<Event> messageToEvents(Message message, String invocationId, String branch) {
    List<Event> events = new ArrayList<>();
    if (message == null || message.getParts() == null) {
      return events;
    }

    for (io.a2a.spec.Part<?> part : message.getParts()) {
      PartConverter.toGenaiPart(part)
          .ifPresent(
              genaiPart ->
                  events.add(
                      Event.builder()
                          .id(UUID.randomUUID().toString())
                          .invocationId(invocationId)
                          .author(message.getRole() == Message.Role.AGENT ? "agent" : "user")
                          .branch(branch)
                          .content(
                              Content.builder()
                                  .role(message.getRole() == Message.Role.AGENT ? "model" : "user")
                                  .parts(ImmutableList.of(genaiPart))
                                  .build())
                          .timestamp(Instant.now().toEpochMilli())
                          .build()));
    }
    return events;
  }

  private static Message emptyAgentMessage(String contextId) {
    Message.Builder builder =
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("")));
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return builder.build();
  }

  /** Converts a list of ADK events into a single aggregated A2A message. */
  public static Message eventsToMessage(List<Event> events, String contextId, String taskId) {
    if (events == null || events.isEmpty()) {
      return emptyAgentMessage(contextId);
    }

    if (events.size() == 1) {
      return eventToMessage(events.get(0), contextId);
    }

    List<io.a2a.spec.Part<?>> parts = new ArrayList<>();
    for (Event event : events) {
      parts.addAll(eventParts(event));
    }

    Message.Builder builder =
        new Message.Builder()
            .messageId(taskId != null ? taskId : UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(parts);
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return builder.build();
  }

  /** Converts a single ADK event into an A2A message. */
  public static Message eventToMessage(Event event, String contextId) {
    List<io.a2a.spec.Part<?>> parts = eventParts(event);

    Message.Builder builder =
        new Message.Builder()
            .messageId(event.id() != null ? event.id() : UUID.randomUUID().toString())
            .role(event.author().equalsIgnoreCase("user") ? Message.Role.USER : Message.Role.AGENT)
            .parts(parts);
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return builder.build();
  }

  public static boolean isCompleted(ClientEvent event) {
    TaskState executionState = TaskState.UNKNOWN;
    if (event instanceof TaskEvent taskEvent) {
      executionState = taskEvent.getTask().getStatus().state();
    } else if (event instanceof TaskUpdateEvent updateEvent) {
      executionState = updateEvent.getTask().getStatus().state();
    }
    return executionState.equals(TaskState.COMPLETED);
  }

  public static @Nullable Event clientEventToEvent(
      ClientEvent event, InvocationContext invocationContext) {
    if (event instanceof MessageEvent messageEvent) {
      return messageToEvent(messageEvent.getMessage(), invocationContext);
    } else if (event instanceof TaskEvent taskEvent) {
      return taskToEvent(taskEvent.getTask(), invocationContext);
    } else if (event instanceof TaskUpdateEvent updateEvent) {
      return handleTaskUpdate(updateEvent, invocationContext);
    }
    throw new IllegalArgumentException("Unsupported ClientEvent type: " + event.getClass());
  }

  private static @Nullable Event handleTaskUpdate(
      TaskUpdateEvent event, InvocationContext context) {
    var updateEvent = event.getUpdateEvent();

    if (updateEvent instanceof TaskArtifactUpdateEvent artifactEvent) {
      if (Objects.equals(artifactEvent.isAppend(), false)
          || Objects.equals(artifactEvent.isLastChunk(), true)) {
        return taskToEvent(event.getTask(), context);
      }
    }
    if (updateEvent instanceof TaskStatusUpdateEvent statusEvent) {
      var status = statusEvent.getStatus();
      if (status != null && status.message() != null) {
        return messageToEvent(
            statusEvent.getStatus().message(),
            context,
            PENDING_STATES.contains(event.getTask().getStatus().state()));
      }
      return null;
    }
    throw new IllegalArgumentException(
        "Unsupported TaskUpdateEvent type: " + updateEvent.getClass());
  }

  public static Event finalTaskToEvent(
      TaskStatusUpdateEvent event, InvocationContext invocationContext) {
    Message eventMessage = event.getStatus().message();
    Event.Builder eventBuilder = remoteAgentEventBuilder(invocationContext);

    if (eventMessage != null) {
      eventBuilder.content(fromModelParts(PartConverter.toGenaiParts(eventMessage.getParts())));
    }
    return eventBuilder.turnComplete(true).build();
  }

  /** Converts an A2A message back to ADK events. */
  public static Event messageToEvent(Message message, InvocationContext invocationContext) {
    if (message == null || message.getParts() == null) {
      logger.warn("Null message received, creating empty user event");
      return emptyEvent(invocationContext);
    }
    List<com.google.genai.types.Part> genaiParts = PartConverter.toGenaiParts(message.getParts());

    return remoteAgentEventBuilder(invocationContext).content(fromModelParts(genaiParts)).build();
  }

  /** Converts an A2A message back to ADK events, replacing thoughts with pending markers. */
  public static Event messageToEvent(
      Message message, InvocationContext invocationContext, boolean isPending) {
    if (message == null || message.getParts() == null) {
      logger.warn("Null message received, creating empty user event");
      return emptyEvent(invocationContext);
    }
    List<com.google.genai.types.Part> genaiParts = PartConverter.toGenaiParts(message.getParts());
    if (isPending) {
      genaiParts.replaceAll(part -> part.toBuilder().thought(true).build());
    }

    return remoteAgentEventBuilder(invocationContext).content(fromModelParts(genaiParts)).build();
  }

  public static Event taskToEvent(Task task, InvocationContext invocationContext) {
    if (task == null) {
      return emptyEvent(invocationContext);
    }
    Message taskMessage = null;

    if (task.getArtifacts() != null && !task.getArtifacts().isEmpty()) {
      taskMessage =
          new Message.Builder()
              .messageId("")
              .role(Message.Role.AGENT)
              .parts(Iterables.getLast(task.getArtifacts()).parts())
              .build();
    } else if (task.getStatus() != null && task.getStatus().message() != null) {
      taskMessage = task.getStatus().message();
    } else if (task.getHistory() != null && !task.getHistory().isEmpty()) {
      taskMessage = Iterables.getLast(task.getHistory());
    }

    if (taskMessage != null) {
      return messageToEvent(taskMessage, invocationContext);
    }
    return emptyEvent(invocationContext);
  }

  private static List<io.a2a.spec.Part<?>> eventParts(Event event) {
    List<io.a2a.spec.Part<?>> parts = new ArrayList<>();
    Optional<Content> content = event.content();
    if (content.isEmpty() || content.get().parts().isEmpty()) {
      return parts;
    }

    for (com.google.genai.types.Part genaiPart : content.get().parts().get()) {
      PartConverter.fromGenaiPart(genaiPart).ifPresent(parts::add);
    }
    return parts;
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

  private static Content fromModelParts(List<com.google.genai.types.Part> parts) {
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

  /** Simple REST-friendly wrapper to carry either a message result or a task result. */
  public record MessageSendResult(@Nullable Message message, @Nullable Task task) {
    public static MessageSendResult fromMessage(Message message) {
      return new MessageSendResult(message, null);
    }

    public static MessageSendResult fromTask(Task task) {
      return new MessageSendResult(null, task);
    }
  }
}
