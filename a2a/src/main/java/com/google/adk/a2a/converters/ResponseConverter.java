package com.google.adk.a2a;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import io.a2a.spec.EventKind;
import io.a2a.spec.Message;
import io.a2a.spec.Message.Builder;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for converting ADK events to A2A spec messages (and back).
 *
 * @apiNote **EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do
 *     not use in production code.
 */
public final class ResponseConverter {
  private static final Logger logger = LoggerFactory.getLogger(ResponseConverter.class);

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
      return List.of();
    }

    EventKind result = response.getResult();
    if (result == null) {
      logger.warn("SendMessageResponse result was null for invocation {}", invocationId);
      return List.of();
    }

    if (result instanceof Message message) {
      return messageToEvents(message, invocationId, branch);
    }

    logger.warn(
        "Unsupported SendMessageResponse result type {} for invocation {}; expected Message",
        result.getClass().getSimpleName(),
        invocationId);
    return List.of();
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

    Builder builder =
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

    Builder builder =
        new Message.Builder()
            .messageId(event.id() != null ? event.id() : UUID.randomUUID().toString())
            .role(event.author().equalsIgnoreCase("user") ? Message.Role.USER : Message.Role.AGENT)
            .parts(parts);
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return builder.build();
  }

  /** Converts an A2A message back to ADK events. */
  public static List<Event> messageToEvents(Message message, String invocationId, String branch) {
    List<Event> events = new ArrayList<>();
    if (message == null || message.getParts() == null) {
      events.add(emptyUserEvent(invocationId, branch));
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
                                  .parts(List.of(genaiPart))
                                  .build())
                          .timestamp(Instant.now().toEpochMilli())
                          .build()));
    }

    if (events.isEmpty()) {
      events.add(emptyUserEvent(invocationId, branch));
    }
    return events;
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

  private static Message emptyAgentMessage(String contextId) {
    Builder builder =
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(List.of(new io.a2a.spec.TextPart("")));
    if (contextId != null) {
      builder.contextId(contextId);
    }
    return builder.build();
  }

  private static Event emptyUserEvent(String invocationId, String branch) {
    Event.Builder builder =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId(invocationId)
            .author("user")
            .content(
                Content.builder()
                    .role("user")
                    .parts(List.of(com.google.genai.types.Part.builder().text("").build()))
                    .build())
            .timestamp(Instant.now().toEpochMilli());
    if (branch != null) {
      builder.branch(branch);
    }
    return builder.build();
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
