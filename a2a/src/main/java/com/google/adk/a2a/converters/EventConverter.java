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

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import io.a2a.spec.Part;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Converter for ADK Events to A2A Messages. */
public final class EventConverter {
  public static final String ADK_TASK_ID_KEY = "adk_task_id";
  public static final String ADK_CONTEXT_ID_KEY = "adk_context_id";

  private EventConverter() {}

  /**
   * Returns the task ID from the event.
   *
   * <p>Task ID is stored in the event's custom metadata with the key {@link #ADK_TASK_ID_KEY}.
   *
   * @param event The event to get the task ID from.
   * @return The task ID, or an empty string if not found.
   */
  public static String taskId(Event event) {
    return metadataValue(event, ADK_TASK_ID_KEY);
  }

  /**
   * Returns the context ID from the event.
   *
   * <p>Context ID is stored in the event's custom metadata with the key {@link
   * #ADK_CONTEXT_ID_KEY}.
   *
   * @param event The event to get the context ID from.
   * @return The context ID, or an empty string if not found.
   */
  public static String contextId(Event event) {
    return metadataValue(event, ADK_CONTEXT_ID_KEY);
  }

  /**
   * Returns the last user function call event from the list of events.
   *
   * @param events The list of events to find the user function call event from.
   * @return The user function call event, or null if not found.
   */
  public static @Nullable Event findUserFunctionCall(List<Event> events) {
    Event candidate = Iterables.getLast(events);
    if (!candidate.author().equals("user")) {
      return null;
    }
    FunctionResponse functionResponse = findUserFunctionResponse(candidate);
    if (functionResponse == null || functionResponse.id().isEmpty()) {
      return null;
    }
    for (int i = events.size() - 2; i >= 0; i--) {
      Event event = events.get(i);
      if (isUserFunctionCall(event, functionResponse.id().get())) {
        return event;
      }
    }
    return null;
  }

  private static @Nullable FunctionResponse findUserFunctionResponse(Event candidate) {
    if (candidate.content().isEmpty() || candidate.content().get().parts().isEmpty()) {
      return null;
    }
    return candidate.content().get().parts().get().stream()
        .filter(part -> part.functionResponse().isPresent())
        .findFirst()
        .map(part -> part.functionResponse().get())
        .orElse(null);
  }

  private static boolean isUserFunctionCall(Event event, String functionResponseId) {
    if (event.content().isEmpty()) {
      return false;
    }
    return event.content().get().parts().get().stream()
        .anyMatch(
            part ->
                part.functionCall().isPresent()
                    && part.functionCall()
                        .get()
                        .id()
                        .map(id -> id.equals(functionResponseId))
                        .orElse(false));
  }

  /**
   * Converts a GenAI Content object to a list of A2A Parts.
   *
   * @param content The GenAI Content object to convert.
   * @param isPartial Whether the content is partial.
   * @return A list of A2A Parts.
   */
  public static ImmutableList<Part<?>> contentToParts(
      Optional<Content> content, boolean isPartial) {
    return content.flatMap(Content::parts).stream()
        .flatMap(Collection::stream)
        .map(part -> PartConverter.fromGenaiPart(part, isPartial))
        .collect(toImmutableList());
  }

  /**
   * Returns the parts from the context events that should be sent to the agent.
   *
   * <p>All session events from the previous remote agent response (or the beginning of the session
   * in case of the first agent invocation) are included into the A2A message. Events from other
   * agents are presented as user messages and rephased as if a user was telling what happened in
   * the session up to the point.
   *
   * @param context The invocation context to get the parts from.
   * @return A list of A2A Parts.
   */
  public static ImmutableList<Part<?>> messagePartsFromContext(InvocationContext context) {
    if (context.session().events().isEmpty()) {
      return ImmutableList.of();
    }
    List<Event> events = context.session().events();
    int lastResponseIndex = -1;
    String contextId = "";
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (event.author().equals(context.agent().name())) {
        lastResponseIndex = i;
        contextId = contextId(event);
        break;
      }
    }
    ImmutableList.Builder<Part<?>> partsBuilder = ImmutableList.builder();
    for (int i = lastResponseIndex + 1; i < events.size(); i++) {
      Event event = events.get(i);
      if (!event.author().equals("user") && !event.author().equals(context.agent().name())) {
        event = presentAsUserMessage(event, contextId);
      }
      contentToParts(event.content(), event.partial().orElse(false)).forEach(partsBuilder::add);
    }
    return partsBuilder.build();
  }

  private static Event presentAsUserMessage(Event event, String contextId) {
    Event.Builder userEvent =
        new Event.Builder().id(UUID.randomUUID().toString()).invocationId(contextId).author("user");
    ImmutableList<com.google.genai.types.Part> parts =
        event.content().flatMap(Content::parts).stream()
            .flatMap(Collection::stream)
            // convert only non-thought parts to user message parts, skip thought parts as they are
            // not meant to be shown to the user
            .filter(part -> !part.thought().orElse(false))
            .map(part -> PartConverter.remoteCallAsUserPart(event.author(), part))
            .collect(toImmutableList());
    if (parts.isEmpty()) {
      return userEvent.build();
    }
    com.google.genai.types.Part forContext =
        com.google.genai.types.Part.builder().text("For context:").build();
    return userEvent
        .content(
            Content.builder()
                .parts(
                    ImmutableList.<com.google.genai.types.Part>builder()
                        .add(forContext)
                        .addAll(parts)
                        .build())
                .build())
        .build();
  }

  private static String metadataValue(Event event, String key) {
    if (event.customMetadata().isEmpty()) {
      return "";
    }
    return event.customMetadata().get().stream()
        .filter(m -> m.key().map(k -> k.equals(key)).orElse(false))
        .findFirst()
        .flatMap(m -> m.stringValue())
        .orElse("");
  }
}
