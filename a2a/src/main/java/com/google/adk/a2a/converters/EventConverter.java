package com.google.adk.a2a.converters;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.spec.Message;
import io.a2a.spec.TextPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converter for ADK Events to A2A Messages.
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public final class EventConverter {
  private static final Logger logger = LoggerFactory.getLogger(EventConverter.class);

  private EventConverter() {}

  /**
   * Aggregation mode for converting events to A2A messages.
   *
   * <p>AS_IS: Parts are aggregated as-is.
   *
   * <p>EXTERNAL_HANDOFF: Parts are aggregated as-is, except for function responses, which are
   * converted to text parts with the function name and response map.
   */
  public enum AggregationMode {
    AS_IS,
    EXTERNAL_HANDOFF
  }

  public static ImmutableList<io.a2a.spec.Part<?>> contentToParts(Optional<Content> content) {
    if (content.isPresent() && content.get().parts().isPresent()) {
      return content.get().parts().get().stream()
          .map(PartConverter::fromGenaiPart)
          .flatMap(Optional::stream)
          .collect(toImmutableList());
    }
    return ImmutableList.of();
  }

  public static Optional<Message> convertEventsToA2AMessage(InvocationContext context) {
    return convertEventsToA2AMessage(context, AggregationMode.AS_IS);
  }

  public static Optional<Message> convertEventsToA2AMessage(
      InvocationContext context, AggregationMode mode) {
    if (context.session().events().isEmpty()) {
      logger.warn("No events in session, cannot convert to A2A message.");
      return Optional.empty();
    }

    List<io.a2a.spec.Part<?>> parts = new ArrayList<>();
    for (Event event : context.session().events()) {
      appendContentParts(event.content(), mode, parts);
    }

    context
        .userContent()
        .ifPresent(content -> appendContentParts(Optional.of(content), mode, parts));

    if (parts.isEmpty()) {
      logger.warn("No suitable content found to build A2A request message.");
      return Optional.empty();
    }

    return Optional.of(
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .parts(parts)
            .role(Message.Role.USER)
            .build());
  }

  private static void appendContentParts(
      Optional<Content> contentOpt, AggregationMode mode, List<io.a2a.spec.Part<?>> target) {
    if (contentOpt.isEmpty() || contentOpt.get().parts().isEmpty()) {
      return;
    }

    for (Part part : contentOpt.get().parts().get()) {
      if (part.text().isPresent()) {
        target.add(new TextPart(part.text().get()));
        continue;
      }

      if (part.functionCall().isPresent()) {
        if (mode == AggregationMode.AS_IS) {
          PartConverter.convertGenaiPartToA2aPart(part).ifPresent(target::add);
        }
        continue;
      }

      if (part.functionResponse().isPresent()) {
        if (mode == AggregationMode.AS_IS) {
          PartConverter.convertGenaiPartToA2aPart(part).ifPresent(target::add);
        } else {
          String name = part.functionResponse().get().name().orElse("");
          String mapStr = String.valueOf(part.functionResponse().get().response().orElse(null));
          target.add(new TextPart(String.format("%s response: %s", name, mapStr)));
        }
        continue;
      }

      PartConverter.fromGenaiPart(part).ifPresent(target::add);
    }
  }
}
