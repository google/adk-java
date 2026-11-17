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

import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import io.a2a.spec.Part;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Converter for ADK Events to A2A Messages. */
public final class EventConverter {
  // Keys that older sessions recorded task and context IDs under, read here without deprecation.
  static final String LEGACY_TASK_ID_KEY = "adk_task_id";
  static final String LEGACY_CONTEXT_ID_KEY = "adk_context_id";

  /**
   * Legacy task ID key, read only for events with neither {@code a2a:} ID key.
   *
   * @deprecated Use {@code AdkMetadataKey.TASK_ID.getType()}, the key task IDs are recorded under.
   */
  @Deprecated public static final String ADK_TASK_ID_KEY = LEGACY_TASK_ID_KEY;

  /**
   * Legacy context ID key, read only for events with neither {@code a2a:} ID key.
   *
   * @deprecated Use {@code AdkMetadataKey.CONTEXT_ID.getType()}, the key context IDs are recorded
   *     under.
   */
  @Deprecated public static final String ADK_CONTEXT_ID_KEY = LEGACY_CONTEXT_ID_KEY;

  private static final String REQUEST_CREDENTIAL_CALL_NAME = "adk_request_credential";

  // Calls whose answers carry credentials; the mock auth call itself holds the remote's prompt.
  private static final ImmutableSet<String> CREDENTIAL_CALL_NAMES =
      ImmutableSet.of(REQUEST_CREDENTIAL_CALL_NAME, "mock_function_call_for_required_user_auth");

  private static final ImmutableList<String> AUTH_CONFIG_ARG_KEYS =
      ImmutableList.of("authConfig", "auth_config");

  // Top-level keys of a serialized AuthConfig, in both spellings.
  private static final ImmutableSet<String> AUTH_CONFIG_KEYS =
      ImmutableSet.of(
          "authScheme",
          "auth_scheme",
          "rawAuthCredential",
          "raw_auth_credential",
          "exchangedAuthCredential",
          "exchanged_auth_credential");

  private EventConverter() {}

  /**
   * Returns the task ID from the event.
   *
   * <p>Task ID is stored in the event's custom metadata under {@code a2a:task_id}, where {@link
   * ResponseConverter} and ADK Python write it. An event with neither {@code a2a:task_id} nor
   * {@code a2a:context_id} is read from the legacy {@code adk_task_id} key.
   *
   * @param event The event to get the task ID from.
   * @return The task ID, or an empty string if not found.
   */
  public static String taskId(Event event) {
    return remoteId(event, AdkMetadataKey.TASK_ID.getType(), LEGACY_TASK_ID_KEY);
  }

  /**
   * Returns the context ID from the event.
   *
   * <p>Context ID is stored in the event's custom metadata under {@code a2a:context_id}, where
   * {@link ResponseConverter} and ADK Python write it. An event with neither {@code a2a:task_id}
   * nor {@code a2a:context_id} is read from the legacy {@code adk_context_id} key.
   *
   * @param event The event to get the context ID from.
   * @return The context ID, or an empty string if not found.
   */
  public static String contextId(Event event) {
    return remoteId(event, AdkMetadataKey.CONTEXT_ID.getType(), LEGACY_CONTEXT_ID_KEY);
  }

  /**
   * Returns the call event that the last event's first non-credential function response answers.
   *
   * @param events The list of events to find the user function call event from.
   * @return The user function call event, or null if not found.
   */
  public static @Nullable Event findUserFunctionCall(List<Event> events) {
    Event candidate = Iterables.getLast(events);
    if (!candidate.author().equals("user")) {
      return null;
    }
    FunctionResponse functionResponse =
        findUserFunctionResponse(candidate, credentialCallIds(events));
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

  /** Returns the first function response in {@code candidate} that carries no credential. */
  private static @Nullable FunctionResponse findUserFunctionResponse(
      Event candidate, ImmutableSet<String> credentialCallIds) {
    return withoutCredentialParts(candidate, credentialCallIds).stream()
        .flatMap(part -> part.functionResponse().stream())
        .findFirst()
        .orElse(null);
  }

  private static boolean isUserFunctionCall(Event event, String functionResponseId) {
    return event.functionCalls().stream()
        .anyMatch(call -> call.id().map(functionResponseId::equals).orElse(false));
  }

  /**
   * Returns the parts of the user's answer to send to the remote agent whose call it answers.
   * Credential parts are never sent, nor is text beside a function response, which an ADK runner
   * rejects.
   *
   * @param events The session events, ending with the user's answer; must not be empty.
   * @return The answer's parts to send, possibly none.
   */
  @Experimental
  public static ImmutableList<Part<?>> answerParts(List<Event> events) {
    Event answer = Iterables.getLast(events);
    ImmutableList<com.google.genai.types.Part> kept =
        withoutCredentialParts(answer, credentialCallIds(events));
    boolean hasResponse = kept.stream().anyMatch(part -> part.functionResponse().isPresent());
    boolean partial = answer.partial().orElse(false);
    return kept.stream()
        .filter(part -> !hasResponse || part.text().isEmpty())
        .map(part -> PartConverter.fromGenaiPart(part, partial))
        .collect(toImmutableList());
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
   * the session up to the point. Credential parts are left out.
   *
   * @param context The invocation context to get the parts from.
   * @return A list of A2A Parts.
   */
  public static ImmutableList<Part<?>> messagePartsFromContext(InvocationContext context) {
    ImmutableList<Event> events = context.session().immutableEvents();
    if (events.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableSet<String> credentialCallIds = credentialCallIds(events);
    int lastResponseIndex = -1;
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (event.author().equals(context.agent().name())) {
        lastResponseIndex = i;
        break;
      }
    }
    ImmutableList.Builder<Part<?>> partsBuilder = ImmutableList.builder();
    for (int i = lastResponseIndex + 1; i < events.size(); i++) {
      // Drop credentials before another agent's turn is flattened to text.
      Event event = withoutCredentials(events.get(i), credentialCallIds);
      Optional<Content> content;
      boolean partial;
      if (!event.author().equals("user") && !event.author().equals(context.agent().name())) {
        // Another agent's output is rephrased as a complete user message.
        content = presentAsUserMessage(event);
        partial = false;
      } else {
        content = event.content();
        partial = event.partial().orElse(false);
      }
      contentToParts(content, partial).forEach(partsBuilder::add);
    }
    return partsBuilder.build();
  }

  /**
   * Rephrases the content of an event authored by another agent as a user message, or returns empty
   * when the event has no parts to present.
   */
  private static Optional<Content> presentAsUserMessage(Event event) {
    ImmutableList<com.google.genai.types.Part> parts =
        event.content().flatMap(Content::parts).stream()
            .flatMap(Collection::stream)
            // convert only non-thought parts to user message parts, skip thought parts as they are
            // not meant to be shown to the user
            .filter(part -> !part.thought().orElse(false))
            .map(part -> PartConverter.remoteCallAsUserPart(event.author(), part))
            .collect(toImmutableList());
    if (parts.isEmpty()) {
      return Optional.empty();
    }
    com.google.genai.types.Part forContext =
        com.google.genai.types.Part.builder().text("For context:").build();
    return Optional.of(
        Content.builder()
            .parts(
                ImmutableList.<com.google.genai.types.Part>builder()
                    .add(forContext)
                    .addAll(parts)
                    .build())
            .build());
  }

  /**
   * Returns the ID under {@code key}, or under {@code legacyKey} if the event has neither new key.
   */
  private static String remoteId(Event event, String key, String legacyKey) {
    boolean hasNewKeys =
        findMetadata(event, AdkMetadataKey.TASK_ID.getType()).isPresent()
            || findMetadata(event, AdkMetadataKey.CONTEXT_ID.getType()).isPresent();
    return findMetadata(event, hasNewKeys ? key : legacyKey)
        .flatMap(CustomMetadata::stringValue)
        .orElse("");
  }

  private static Optional<CustomMetadata> findMetadata(Event event, String key) {
    return event.customMetadata().stream()
        .flatMap(List::stream)
        .filter(m -> m.key().map(k -> k.equals(key)).orElse(false))
        .findFirst();
  }

  /** Returns the IDs of the credential calls in {@code events}, with "" for a call without one. */
  private static ImmutableSet<String> credentialCallIds(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionCalls().stream())
        .filter(call -> CREDENTIAL_CALL_NAMES.contains(call.name().orElse("")))
        .map(call -> call.id().orElse(""))
        .collect(toImmutableSet());
  }

  private static Event withoutCredentials(Event event, ImmutableSet<String> credentialCallIds) {
    if (event.content().isEmpty()) {
      return event;
    }
    ImmutableList<com.google.genai.types.Part> kept =
        withoutCredentialParts(event, credentialCallIds);
    return event.toBuilder().content(event.content().get().toBuilder().parts(kept).build()).build();
  }

  private static ImmutableList<com.google.genai.types.Part> withoutCredentialParts(
      Event event, ImmutableSet<String> credentialCallIds) {
    return event.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
        .filter(part -> !carriesCredential(part, credentialCallIds))
        .collect(toImmutableList());
  }

  /**
   * Whether {@code part} carries a credential. A call does if it is named {@code
   * adk_request_credential} or its {@code authConfig} argument holds an AuthConfig. A response does
   * if it is named for a credential call, answers one of {@code credentialCallIds} (a response
   * without an ID matches a call without one), or holds an AuthConfig.
   */
  private static boolean carriesCredential(
      com.google.genai.types.Part part, ImmutableSet<String> credentialCallIds) {
    if (part.functionResponse().isPresent()) {
      FunctionResponse response = part.functionResponse().get();
      return CREDENTIAL_CALL_NAMES.contains(response.name().orElse(""))
          || credentialCallIds.contains(response.id().orElse(""))
          || isAuthConfig(response.response().orElse(null));
    }
    if (part.functionCall().isPresent()) {
      FunctionCall call = part.functionCall().get();
      Map<String, Object> args = call.args().orElse(ImmutableMap.of());
      return call.name().orElse("").equals(REQUEST_CREDENTIAL_CALL_NAME)
          || AUTH_CONFIG_ARG_KEYS.stream().anyMatch(key -> isAuthConfig(args.get(key)));
    }
    return false;
  }

  /** Whether {@code payload}, bare or wrapped as {@code {"result": ...}}, is an AuthConfig. */
  private static boolean isAuthConfig(@Nullable Object payload) {
    Object candidate = payload;
    if (payload instanceof Map<?, ?> wrapper
        && wrapper.size() == 1
        && wrapper.containsKey("result")) {
      candidate = wrapper.get("result");
    }
    return candidate instanceof Map<?, ?> map
        && AUTH_CONFIG_KEYS.stream().anyMatch(map::containsKey);
  }
}
