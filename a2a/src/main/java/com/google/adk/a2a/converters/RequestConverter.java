package com.google.adk.a2a.converters;

import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * rfe Converter for A2A Messages to ADK Events. This is used on the A2A service side to convert
 * incoming A2A requests to ADK Events.
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public final class RequestConverter {
  private static final Logger logger = LoggerFactory.getLogger(RequestConverter.class);

  private RequestConverter() {}

  /**
   * Convert an A2A Message to an ADK Event. This is used when the A2A service receives a request
   * and needs to process it with ADK.
   *
   * @param message The A2A message to convert.
   * @param invocationId The invocation ID for the event.
   * @return Optional containing the converted ADK Event, or empty if conversion fails.
   */
  public static Optional<Event> convertA2aMessageToAdkEvent(Message message, String invocationId) {
    if (message == null) {
      // Create an empty user message event
      logger.info("Null message received, creating empty user event");
      Event event =
          Event.builder()
              .id(UUID.randomUUID().toString())
              .invocationId(invocationId != null ? invocationId : UUID.randomUUID().toString())
              .author("user")
              .content(
                  Content.builder()
                      .role("user")
                      .parts(
                          ImmutableList.of(com.google.genai.types.Part.builder().text("").build()))
                      .build())
              .timestamp(Instant.now().toEpochMilli())
              .build();
      return Optional.of(event);
    }

    List<com.google.genai.types.Part> genaiParts = new ArrayList<>();

    // Convert each A2A Part to GenAI Part
    if (message.getParts() != null) {
      for (Part<?> a2aPart : message.getParts()) {
        Optional<com.google.genai.types.Part> genaiPart = PartConverter.toGenaiPart(a2aPart);
        genaiPart.ifPresent(genaiParts::add);
      }
    }

    if (genaiParts.isEmpty()) {
      logger.warn("No convertible parts found in A2A message");
      return Optional.empty();
    }

    // Treat inbound A2A requests as user input for the ADK agent.
    String author = "user";

    // Build the Content object
    Content content = Content.builder().role("user").parts(genaiParts).build();

    // Build the Event
    Event event =
        Event.builder()
            .id(
                !message.getMessageId().isEmpty()
                    ? message.getMessageId()
                    : UUID.randomUUID().toString())
            .invocationId(invocationId != null ? invocationId : UUID.randomUUID().toString())
            .author(author)
            .content(content)
            .timestamp(Instant.now().toEpochMilli())
            .build();

    return Optional.of(event);
  }

  /**
   * Convert an aggregated A2A Message to multiple ADK Events. This reconstructs the original event
   * sequence from an aggregated message.
   *
   * @param message The aggregated A2A message to convert.
   * @param invocationId The invocation ID for the events.
   * @return List of ADK Events representing the conversation history.
   */
  public static ImmutableList<Event> convertAggregatedA2aMessageToAdkEvents(
      Message message, String invocationId) {
    if (message == null || message.getParts() == null || message.getParts().isEmpty()) {
      logger.info("Null or empty message received, creating empty user event");
      Event event =
          Event.builder()
              .id(UUID.randomUUID().toString())
              .invocationId(invocationId != null ? invocationId : UUID.randomUUID().toString())
              .author("user")
              .content(
                  Content.builder()
                      .role("user")
                      .parts(
                          ImmutableList.of(com.google.genai.types.Part.builder().text("").build()))
                      .build())
              .timestamp(Instant.now().toEpochMilli())
              .build();
      return ImmutableList.of(event);
    }

    List<Event> events = new ArrayList<>();

    // Emit exactly one ADK Event per A2A Part, preserving order.
    for (Part<?> a2aPart : message.getParts()) {
      Optional<com.google.genai.types.Part> genaiPart = PartConverter.toGenaiPart(a2aPart);
      if (genaiPart.isEmpty()) {
        continue;
      }

      String author = extractAuthorFromMetadata(a2aPart);
      String role = determineRoleFromAuthor(author);

      events.add(createEvent(ImmutableList.of(genaiPart.get()), author, role, invocationId));
    }

    if (events.isEmpty()) {
      logger.warn("No events created from aggregated message; returning single empty user event");
      Event event =
          Event.builder()
              .id(UUID.randomUUID().toString())
              .invocationId(invocationId)
              .author("user")
              .content(
                  Content.builder()
                      .role("user")
                      .parts(
                          ImmutableList.of(com.google.genai.types.Part.builder().text("").build()))
                      .build())
              .timestamp(Instant.now().toEpochMilli())
              .build();
      events.add(event);
    }

    logger.info("Converted aggregated A2A message to {} ADK events", events.size());
    return ImmutableList.copyOf(events);
  }

  private static String extractAuthorFromMetadata(Part<?> a2aPart) {
    if (a2aPart instanceof DataPart dataPart) {
      Map<String, Object> metadata =
          Optional.ofNullable(dataPart.getMetadata()).orElse(ImmutableMap.of());
      String type =
          metadata.getOrDefault(PartConverter.A2A_DATA_PART_METADATA_TYPE_KEY, "").toString();
      if (type.equals(PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL)) {
        return "model";
      }
      if (type.equals(PartConverter.A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE)) {
        return "user";
      }
      Map<String, Object> data = Optional.ofNullable(dataPart.getData()).orElse(ImmutableMap.of());
      if (data.containsKey("args")) {
        return "model";
      }
      if (data.containsKey("response")) {
        return "user";
      }
    }
    return "user";
  }

  private static String determineRoleFromAuthor(String author) {
    return author.equals("model") ? "model" : "user";
  }

  private static Event createEvent(
      List<com.google.genai.types.Part> parts, String author, String role, String invocationId) {
    return Event.builder()
        .id(UUID.randomUUID().toString())
        .invocationId(invocationId)
        .author(author)
        .content(Content.builder().role(role).parts(new ArrayList<>(parts)).build())
        .timestamp(Instant.now().toEpochMilli())
        .build();
  }
}
