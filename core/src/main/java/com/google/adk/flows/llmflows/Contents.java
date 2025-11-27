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

package com.google.adk.flows.llmflows;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link RequestProcessor} that populates content in request for LLM flows. */
public final class Contents implements RequestProcessor {
  public Contents() {}

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext context, LlmRequest request) {
    if (!(context.agent() instanceof LlmAgent)) {
      return Single.just(
          RequestProcessor.RequestProcessingResult.create(request, context.session().events()));
    }
    LlmAgent llmAgent = (LlmAgent) context.agent();

    if (llmAgent.includeContents() == LlmAgent.IncludeContents.NONE) {
      return Single.just(
          RequestProcessor.RequestProcessingResult.create(
              request.toBuilder()
                  .contents(
                      getCurrentTurnContents(
                          context.branch(), context.session().events(), context.agent().name()))
                  .build(),
              ImmutableList.of()));
    }

    ImmutableList<Content> contents =
        getContents(context.branch(), context.session().events(), context.agent().name());

    return Single.just(
        RequestProcessor.RequestProcessingResult.create(
            request.toBuilder().contents(contents).build(), ImmutableList.of()));
  }

  /** Gets contents for the current turn only (no conversation history). */
  private ImmutableList<Content> getCurrentTurnContents(
      Optional<String> currentBranch, List<Event> events, String agentName) {
    // Find the latest event that starts the current turn and process from there.
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (event.author().equals("user") || isOtherAgentReply(agentName, event)) {
        return getContents(currentBranch, events.subList(i, events.size()), agentName);
      }
    }
    return ImmutableList.of();
  }

  private ImmutableList<Content> getContents(
      Optional<String> currentBranch, List<Event> events, String agentName) {
    List<Event> filteredEvents = new ArrayList<>();

    // Filter the events, leaving the contents and the function calls and responses from the current
    // agent.
    for (Event event : events) {
      // Skip events without content, or generated neither by user nor by model or has empty text.
      // E.g. events purely for mutating session states.
      if (event.content().isEmpty()) {
        continue;
      }
      var content = event.content().get();
      if (content.role().isEmpty()
          || content.role().get().isEmpty()
          || content.parts().isEmpty()
          || content.parts().get().isEmpty()
          || content.parts().get().get(0).text().map(String::isEmpty).orElse(false)) {
        continue;
      }

      if (!isEventBelongsToBranch(currentBranch, event)) {
        continue;
      }
      if (isRequestConfirmationEvent(event)) {
        continue;
      }

      // TODO: Skip auth events.

      if (isOtherAgentReply(agentName, event)) {
        filteredEvents.add(convertForeignEvent(event));
      } else {
        filteredEvents.add(event);
      }
    }

    List<Event> resultEvents = rearrangeEventsForLatestFunctionResponse(filteredEvents);
    resultEvents = rearrangeEventsForAsyncFunctionResponsesInHistory(resultEvents);

    return resultEvents.stream()
        .map(Event::content)
        .flatMap(Optional::stream)
        .collect(toImmutableList());
  }

  /** Whether the event is a reply from another agent. */
  private static boolean isOtherAgentReply(String agentName, Event event) {
    return !agentName.isEmpty()
        && !event.author().equals(agentName)
        && !event.author().equals("user");
  }

  /** Converts an {@code event} authored by another agent to a 'contextual-only' event. */
  private static Event convertForeignEvent(Event event) {
    if (event.content().isEmpty()
        || event.content().get().parts().isEmpty()
        || event.content().get().parts().get().isEmpty()) {
      return event;
    }

    List<Part> parts = new ArrayList<>();
    parts.add(Part.fromText("For context:"));

    String originalAuthor = event.author();

    for (Part part : event.content().get().parts().get()) {
      if (part.text().isPresent()
          && !part.text().get().isEmpty()
          && !part.thought().orElse(false)) {
        parts.add(Part.fromText(String.format("[%s] said: %s", originalAuthor, part.text().get())));
      } else if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        parts.add(
            Part.fromText(
                String.format(
                    "[%s] called tool `%s` with parameters: %s",
                    originalAuthor,
                    functionCall.name().orElse("unknown_tool"),
                    functionCall.args().map(Contents::convertMapToJson).orElse("{}"))));
      } else if (part.functionResponse().isPresent()) {
        FunctionResponse functionResponse = part.functionResponse().get();
        parts.add(
            Part.fromText(
                String.format(
                    "[%s] `%s` tool returned result: %s",
                    originalAuthor,
                    functionResponse.name().orElse("unknown_tool"),
                    functionResponse.response().map(Contents::convertMapToJson).orElse("{}"))));
      } else {
        parts.add(part);
      }
    }

    Content content = Content.builder().role("user").parts(parts).build();
    return event.toBuilder().author("user").content(content).build();
  }

  private static String convertMapToJson(Map<String, Object> struct) {
    try {
      return JsonBaseModel.getMapper().writeValueAsString(struct);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize the object to JSON.", e);
    }
  }

  private static boolean isEventBelongsToBranch(Optional<String> invocationBranchOpt, Event event) {
    Optional<String> eventBranchOpt = event.branch();

    if (invocationBranchOpt.isEmpty() || invocationBranchOpt.get().isEmpty()) {
      return true;
    }
    if (eventBranchOpt.isEmpty() || eventBranchOpt.get().isEmpty()) {
      return true;
    }
    return invocationBranchOpt.get().startsWith(eventBranchOpt.get());
  }

  /**
   * Rearranges the events for the latest function response. If the latest function response is for
   * an async function call, all events between the initial function call and the latest function
   * response will be removed.
   *
   * @param events The list of events.
   * @return A new list of events with the appropriate rearrangement.
   */
  private static List<Event> rearrangeEventsForLatestFunctionResponse(List<Event> events) {
    // TODO: b/412663475 - Handle parallel function calls within the same event. Currently, this
    // throws an error.
    if (events.isEmpty() || Iterables.getLast(events).functionResponses().isEmpty()) {
      // No need to process if the list is empty or the last event is not a function response
      return events;
    }

    Event latestEvent = Iterables.getLast(events);
    // Extract function response IDs from the latest event
    Set<String> functionResponseIds = new HashSet<>();
    latestEvent
        .content()
        .flatMap(Content::parts)
        .ifPresent(
            parts -> {
              for (Part part : parts) {
                part.functionResponse()
                    .flatMap(FunctionResponse::id)
                    .ifPresent(functionResponseIds::add);
              }
            });

    if (functionResponseIds.isEmpty()) {
      return events;
    }

    // Check if the second to last event contains the corresponding function call
    if (events.size() >= 2) {
      Event penultimateEvent = events.get(events.size() - 2);
      boolean matchFound =
          penultimateEvent
              .content()
              .flatMap(Content::parts)
              .map(
                  parts -> {
                    for (Part part : parts) {
                      if (part.functionCall()
                          .flatMap(FunctionCall::id)
                          .map(functionResponseIds::contains)
                          .orElse(false)) {
                        return true; // Found a matching function call ID
                      }
                    }
                    return false;
                  })
              .orElse(false);
      if (matchFound) {
        // The latest function response is already matched with the immediately preceding event
        return events;
      }
    }

    // Look for the corresponding function call event by iterating backwards
    int functionCallEventIndex = -1;
    for (int i = events.size() - 3; i >= 0; i--) { // Start from third-to-last
      Event event = events.get(i);
      Optional<List<Part>> partsOptional = event.content().flatMap(Content::parts);
      if (partsOptional.isPresent()) {
        List<Part> parts = partsOptional.get();
        for (Part part : parts) {
          Optional<String> callIdOpt = part.functionCall().flatMap(FunctionCall::id);
          if (callIdOpt.isPresent() && functionResponseIds.contains(callIdOpt.get())) {
            functionCallEventIndex = i;
            // Add all function call IDs from this event to the set
            parts.forEach(
                p ->
                    p.functionCall().flatMap(FunctionCall::id).ifPresent(functionResponseIds::add));
            break; // Found the matching event
          }
        }
      }
      if (functionCallEventIndex != -1) {
        break; // Exit outer loop once found
      }
    }

    if (functionCallEventIndex == -1) {
      if (!functionResponseIds.isEmpty()) {
        throw new IllegalStateException(
            "No function call event found for function response IDs: " + functionResponseIds);
      } else {
        return events; // No IDs to match, no rearrangement based on this logic.
      }
    }

    List<Event> resultEvents = new ArrayList<>(events.subList(0, functionCallEventIndex + 1));

    // Collect all function response events between the call and the latest response
    List<Event> functionResponseEventsToMerge = new ArrayList<>();
    for (int i = functionCallEventIndex + 1; i < events.size() - 1; i++) {
      Event intermediateEvent = events.get(i);
      boolean hasMatchingResponse =
          intermediateEvent
              .content()
              .flatMap(Content::parts)
              .map(
                  parts -> {
                    for (Part part : parts) {
                      if (part.functionResponse()
                          .flatMap(FunctionResponse::id)
                          .map(functionResponseIds::contains)
                          .orElse(false)) {
                        return true;
                      }
                    }
                    return false;
                  })
              .orElse(false);
      if (hasMatchingResponse) {
        functionResponseEventsToMerge.add(intermediateEvent);
      }
    }
    functionResponseEventsToMerge.add(latestEvent);

    if (!functionResponseEventsToMerge.isEmpty()) {
      resultEvents.add(mergeFunctionResponseEvents(functionResponseEventsToMerge));
    }

    return resultEvents;
  }

  private static List<Event> rearrangeEventsForAsyncFunctionResponsesInHistory(List<Event> events) {
    Map<String, Integer> functionCallIdToResponseEventIndex = new HashMap<>();
    for (int i = 0; i < events.size(); i++) {
      final int index = i;
      Event event = events.get(index);
      event
          .content()
          .flatMap(Content::parts)
          .ifPresent(
              parts -> {
                for (Part part : parts) {
                  part.functionResponse()
                      .ifPresent(
                          response ->
                              response
                                  .id()
                                  .ifPresent(
                                      functionCallId ->
                                          functionCallIdToResponseEventIndex.put(
                                              functionCallId, index)));
                }
              });
    }

    List<Event> resultEvents = new ArrayList<>();
    // Keep track of response events already added to avoid duplicates when merging
    Set<Integer> processedResponseIndices = new HashSet<>();
    List<Event> responseEventsBuffer = new ArrayList<>();

    for (int i = 0; i < events.size(); i++) {
      Event event = events.get(i);

      // Skip response events that will be processed via responseEventsBuffer
      if (processedResponseIndices.contains(i)) {
        continue;
      }

      Optional<List<Part>> partsOptional = event.content().flatMap(Content::parts);
      boolean hasFunctionCalls =
          partsOptional
              .map(parts -> parts.stream().anyMatch(p -> p.functionCall().isPresent()))
              .orElse(false);

      if (hasFunctionCalls) {
        Set<Integer> responseEventIndices = new HashSet<>();
        // Iterate through parts again to get function call IDs
        partsOptional
            .get()
            .forEach(
                part ->
                    part.functionCall()
                        .ifPresent(
                            call ->
                                call.id()
                                    .ifPresent(
                                        functionCallId -> {
                                          if (functionCallIdToResponseEventIndex.containsKey(
                                              functionCallId)) {
                                            responseEventIndices.add(
                                                functionCallIdToResponseEventIndex.get(
                                                    functionCallId));
                                          }
                                        })));

        resultEvents.add(event); // Add the function call event

        if (!responseEventIndices.isEmpty()) {
          List<Integer> sortedIndices = new ArrayList<>(responseEventIndices);
          Collections.sort(sortedIndices); // Process in chronological order

          for (int index : sortedIndices) {
            if (processedResponseIndices.add(index)) { // Add index and check if it was newly added
              responseEventsBuffer.add(events.get(index));
            }
          }
        }
      } else {
        if (!responseEventsBuffer.isEmpty()) {
          if (responseEventsBuffer.size() == 1) {
            resultEvents.add(responseEventsBuffer.get(0));
          } else {
            resultEvents.add(mergeFunctionResponseEvents(responseEventsBuffer));
          }
          responseEventsBuffer.clear();
        }
        resultEvents.add(event);
      }
    }

    if (!responseEventsBuffer.isEmpty()) {
      if (responseEventsBuffer.size() == 1) {
        resultEvents.add(responseEventsBuffer.get(0));
      } else {
        resultEvents.add(mergeFunctionResponseEvents(responseEventsBuffer));
      }
      responseEventsBuffer.clear();
    }

    return resultEvents;
  }

  /**
   * Merges a list of function response events into one event.
   *
   * <p>The key goal is to ensure: 1. functionCall and functionResponse are always of the same
   * number. 2. The functionCall and functionResponse are consecutively in the content.
   *
   * @param functionResponseEvents A list of function response events. NOTE: functionResponseEvents
   *     must fulfill these requirements: 1. The list is in increasing order of timestamp; 2. the
   *     first event is the initial function response event; 3. all later events should contain at
   *     least one function response part that related to the function call event. Caveat: This
   *     implementation doesn't support when a parallel function call event contains async function
   *     call of the same name.
   * @return A merged event, that is 1. All later function_response will replace function response
   *     part in the initial function response event. 2. All non-function response parts will be
   *     appended to the part list of the initial function response event.
   */
  private static Event mergeFunctionResponseEvents(List<Event> functionResponseEvents) {
    if (functionResponseEvents.isEmpty()) {
      throw new IllegalArgumentException("At least one functionResponse event is required.");
    }
    if (functionResponseEvents.size() == 1) {
      return functionResponseEvents.get(0);
    }

    Event baseEvent = functionResponseEvents.get(0);
    Content baseContent =
        baseEvent
            .content()
            .orElseThrow(() -> new IllegalArgumentException("Base event must have content."));
    List<Part> baseParts =
        baseContent
            .parts()
            .orElseThrow(() -> new IllegalArgumentException("Base event content must have parts."));

    if (baseParts.isEmpty()) {
      throw new IllegalArgumentException(
          "There should be at least one functionResponse part in the base event.");
    }
    List<Part> partsInMergedEvent = new ArrayList<>(baseParts);

    Map<String, Integer> partIndicesInMergedEvent = new HashMap<>();
    for (int i = 0; i < partsInMergedEvent.size(); i++) {
      final int index = i;
      Part part = partsInMergedEvent.get(i);
      if (part.functionResponse().isPresent()) {
        part.functionResponse()
            .get()
            .id()
            .ifPresent(functionCallId -> partIndicesInMergedEvent.put(functionCallId, index));
      }
    }

    for (Event event : functionResponseEvents.subList(1, functionResponseEvents.size())) {
      if (!hasContentWithNonEmptyParts(event)) {
        continue;
      }

      for (Part part : event.content().get().parts().get()) {
        if (part.functionResponse().isPresent()) {
          Optional<String> functionCallIdOpt = part.functionResponse().get().id();
          if (functionCallIdOpt.isPresent()) {
            String functionCallId = functionCallIdOpt.get();
            if (partIndicesInMergedEvent.containsKey(functionCallId)) {
              partsInMergedEvent.set(partIndicesInMergedEvent.get(functionCallId), part);
            } else {
              partsInMergedEvent.add(part);
              partIndicesInMergedEvent.put(functionCallId, partsInMergedEvent.size() - 1);
            }
          } else {
            partsInMergedEvent.add(part);
          }
        } else {
          partsInMergedEvent.add(part);
        }
      }
    }

    return baseEvent.toBuilder()
        .content(
            Optional.of(
                Content.builder().role(baseContent.role().get()).parts(partsInMergedEvent).build()))
        .build();
  }

  private static boolean hasContentWithNonEmptyParts(Event event) {
    return event
        .content() // Optional<Content>
        .flatMap(Content::parts) // Optional<List<Part>>
        .map(list -> !list.isEmpty()) // Optional<Boolean>
        .orElse(false);
  }

  /** Checks if the event is a request confirmation event. */
  private static boolean isRequestConfirmationEvent(Event event) {
    return event.content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        // return event.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
        .anyMatch(
            part ->
                part.functionCall()
                        .flatMap(FunctionCall::name)
                        .map(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME::equals)
                        .orElse(false)
                    || part.functionResponse()
                        .flatMap(FunctionResponse::name)
                        .map(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME::equals)
                        .orElse(false));
  }
}
