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

import static com.google.adk.flows.llmflows.Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.models.LlmRequest;
import com.google.adk.telemetry.Tracing;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.context.Context;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles tool confirmation information to build the LLM request. */
public class RequestConfirmationLlmRequestProcessor implements RequestProcessor {
  private static final Logger logger =
      LoggerFactory.getLogger(RequestConfirmationLlmRequestProcessor.class);
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final String ORIGINAL_FUNCTION_CALL = "originalFunctionCall";

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext invocationContext, LlmRequest llmRequest) {
    ImmutableList<Event> events = ImmutableList.copyOf(invocationContext.session().events());
    if (events.isEmpty()) {
      logger.trace(
          "No events are present in the session. Skipping request confirmation processing.");
      return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }

    Optional<ConfirmationResult> confirmationResult = findMostRecentConfirmations(events);
    if (confirmationResult.isEmpty()) {
      logger.trace("No request confirmation function responses found.");
      return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }

    int finalConfirmationEventIndex = confirmationResult.get().eventIndex();
    ImmutableMap<String, ToolConfirmation> requestConfirmationFunctionResponses =
        confirmationResult.get().responses();
    String agentName = invocationContext.agent().name();
    ImmutableMap<String, AuthoredFunctionCall> functionCallsById =
        functionCallsById(events, agentName);
    ImmutableSet<String> confirmationRequestedIds = confirmationRequestedIds(events);
    // A tool has been confirmed, but it might already have been executed by a subsequent processor
    // or in a subsequent turn: such calls have a function response after the user confirmation
    // event. This is applied before the resumability check rather than after, because
    // findMostRecentConfirmations re-matches the same stale user event on every later LLM call, so
    // a settled confirmation would otherwise be re-examined - and re-logged - for the rest of the
    // session.
    //
    // Only responses this agent produced count. A peer event landing after the approval that
    // reuses the pending call's ID would otherwise convince this scan the tool had already run,
    // silently dropping the approval - and it short-circuits before the resumability check, so
    // that would not even leave a log line.
    ImmutableSet<String> alreadyResumedIds =
        events.subList(finalConfirmationEventIndex + 1, events.size()).stream()
            .filter(event -> Objects.equals(event.author(), agentName))
            .flatMap(event -> event.functionResponses().stream())
            .map(FunctionResponse::id)
            .flatMap(Optional::stream)
            .collect(toImmutableSet());

    // Search backwards from the event before confirmation for the corresponding
    // request_confirmation function calls emitted by the model.
    for (int i = finalConfirmationEventIndex - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (event.functionCalls().isEmpty()) {
        continue;
      }
      // Only this agent can ask this agent's user for confirmation. Function call parts also reach
      // the session from an A2A peer response - ResponseConverter turns one into a model-role event
      // authored by the local RemoteA2AAgent - and honouring a confirmation call from there would
      // let the peer choose which local tool runs.
      if (!Objects.equals(event.author(), agentName)) {
        continue;
      }

      Map<String, ToolConfirmation> toolsToResumeWithConfirmation = new HashMap<>();
      Map<String, FunctionCall> toolsToResumeWithArgs = new HashMap<>();

      event.functionCalls().stream()
          .filter(
              fc ->
                  fc.id().isPresent()
                      && requestConfirmationFunctionResponses.containsKey(fc.id().get()))
          .forEach(
              fc ->
                  getOriginalFunctionCall(fc)
                      .filter(ofc -> !alreadyResumedIds.contains(ofc.id().get()))
                      .filter(
                          ofc ->
                              isResumableFunctionCall(
                                  ofc, functionCallsById, confirmationRequestedIds, agentName))
                      .ifPresent(
                          ofc -> {
                            toolsToResumeWithConfirmation.put(
                                ofc.id().get(),
                                requestConfirmationFunctionResponses.get(fc.id().get()));
                            toolsToResumeWithArgs.put(ofc.id().get(), ofc);
                          }));

      // If all confirmed tools in this event have already been processed, continue
      // searching in older events.
      if (toolsToResumeWithConfirmation.isEmpty()) {
        continue;
      }

      // If we found tools that were confirmed but not yet executed, execute them now.
      return assembleEvent(
              invocationContext,
              toolsToResumeWithArgs.values(),
              ImmutableMap.copyOf(toolsToResumeWithConfirmation))
          .map(
              assembledEvent ->
                  RequestProcessingResult.create(llmRequest, ImmutableList.of(assembledEvent)))
          .toSingle()
          .onErrorReturn(
              e -> {
                logger.error("Error processing request confirmation", e);
                return RequestProcessingResult.create(llmRequest, ImmutableList.of());
              });
    }

    return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
  }

  private static Optional<ConfirmationResult> findMostRecentConfirmations(
      ImmutableList<Event> events) {
    // Search backwards for the most recent user event that contains request confirmation
    // function responses.
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (!Objects.equals(event.author(), "user") || event.functionResponses().isEmpty()) {
        continue;
      }

      ImmutableMap<String, ToolConfirmation> confirmationsInEvent =
          event.functionResponses().stream()
              .filter(functionResponse -> functionResponse.id().isPresent())
              .filter(
                  functionResponse ->
                      Objects.equals(
                          functionResponse.name().orElse(null),
                          REQUEST_CONFIRMATION_FUNCTION_CALL_NAME))
              .map(RequestConfirmationLlmRequestProcessor::maybeCreateToolConfirmationEntry)
              .flatMap(Optional::stream)
              .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
      if (!confirmationsInEvent.isEmpty()) {
        return Optional.of(new ConfirmationResult(confirmationsInEvent, i));
      }
    }
    return Optional.empty();
  }

  /**
   * Indexes the tool function calls in session history by ID, keeping the most recent one per ID.
   *
   * <p>Confirmation calls are excluded: a confirmation resumes a real tool call, never another
   * confirmation.
   *
   * <p>Collisions resolve last-wins, so a re-issue of an ID by {@code agentName} supersedes an
   * earlier one - except that a foreign author may never displace a call {@code agentName} emitted.
   * IDs are not globally unique and anyone can put an event in the session, so without that
   * precedence a peer could reuse the ID of a call this agent is waiting on, shadow it, and have
   * the author check in {@code isResumableFunctionCall} reject the <em>legitimate</em> confirmation
   * - turning that check into a way for a peer to veto any pending tool call.
   */
  private static ImmutableMap<String, AuthoredFunctionCall> functionCallsById(
      ImmutableList<Event> events, String agentName) {
    Map<String, AuthoredFunctionCall> byId = new LinkedHashMap<>();
    for (Event event : events) {
      for (FunctionCall functionCall : event.functionCalls()) {
        if (functionCall.id().isEmpty()
            || Objects.equals(
                functionCall.name().orElse(null), REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)) {
          continue;
        }
        String id = functionCall.id().get();
        AuthoredFunctionCall existing = byId.get(id);
        if (existing == null
            || Objects.equals(event.author(), agentName)
            || !Objects.equals(existing.author(), agentName)) {
          byId.put(id, new AuthoredFunctionCall(event.author(), functionCall));
        }
      }
    }
    return ImmutableMap.copyOf(byId);
  }

  /**
   * Collects the IDs of function calls that a tool actually asked the user to confirm.
   *
   * <p>Covers both ways a confirmation is requested: a tool calling {@link
   * com.google.adk.tools.ToolContext#requestConfirmation}, and a {@link
   * com.google.adk.tools.FunctionTool} created with {@code requireConfirmation}, which routes
   * through the same call. Accumulates over all events rather than keeping one event per ID:
   * re-executing a confirmed tool emits a second function response with the same ID and no
   * requested confirmations, which would otherwise shadow the original request.
   */
  private static ImmutableSet<String> confirmationRequestedIds(ImmutableList<Event> events) {
    ImmutableSet.Builder<String> ids = ImmutableSet.builder();
    for (Event event : events) {
      Map<String, ToolConfirmation> requested = event.actions().requestedToolConfirmations();
      if (requested.isEmpty()) {
        continue;
      }
      for (FunctionResponse functionResponse : event.functionResponses()) {
        functionResponse.id().filter(requested::containsKey).ifPresent(ids::add);
      }
    }
    return ids.build();
  }

  /**
   * Returns whether {@code originalFunctionCall} faithfully reproduces a tool call {@code
   * agentName} emitted and was genuinely awaiting confirmation.
   *
   * <p>The resumed call is read out of the {@code originalFunctionCall} argument of an {@code
   * adk_request_confirmation} call found in session history, and function call parts reach the
   * session from places other than the local model - notably an A2A peer response, which {@code
   * ResponseConverter} turns into a model-role event. Resuming such a call unchecked would let
   * whoever authored that event pick both the tool and its arguments, so only resume a call that
   * matches one this agent emitted, by ID, author, name and arguments, and that a tool actually
   * asked to have confirmed.
   */
  private static boolean isResumableFunctionCall(
      FunctionCall originalFunctionCall,
      ImmutableMap<String, AuthoredFunctionCall> functionCallsById,
      ImmutableSet<String> confirmationRequestedIds,
      String agentName) {
    String id = originalFunctionCall.id().get();
    AuthoredFunctionCall emitted = functionCallsById.get(id);
    if (emitted == null) {
      logger.warn(
          "Ignoring tool confirmation for function call ID {}: no such function call in the session"
              + " history.",
          id);
      return false;
    }
    if (!Objects.equals(emitted.author(), agentName)) {
      // Another agent emitted the call; leave it for that agent's own processor.
      logger.debug(
          "Skipping tool confirmation for function call ID {}: emitted by {}, not by {}.",
          id,
          emitted.author(),
          agentName);
      return false;
    }
    if (!Objects.equals(emitted.functionCall().name(), originalFunctionCall.name())) {
      logger.warn(
          "Ignoring tool confirmation for function call ID {}: tool name does not match the"
              + " function call this agent emitted.",
          id);
      return false;
    }
    if (!Objects.equals(
        emitted.functionCall().args().orElse(ImmutableMap.of()),
        originalFunctionCall.args().orElse(ImmutableMap.of()))) {
      logger.warn(
          "Ignoring tool confirmation for function call ID {}: arguments do not match the function"
              + " call this agent emitted.",
          id);
      return false;
    }
    if (!confirmationRequestedIds.contains(id)) {
      logger.warn(
          "Ignoring tool confirmation for function call ID {}: no tool requested confirmation for"
              + " it.",
          id);
      return false;
    }
    return true;
  }

  private Optional<FunctionCall> getOriginalFunctionCall(FunctionCall functionCall) {
    if (!functionCall.args().orElse(ImmutableMap.of()).containsKey(ORIGINAL_FUNCTION_CALL)) {
      return Optional.empty();
    }
    try {
      FunctionCall originalFunctionCall =
          objectMapper.convertValue(
              functionCall.args().get().get(ORIGINAL_FUNCTION_CALL), FunctionCall.class);
      if (originalFunctionCall.id().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(originalFunctionCall);
    } catch (IllegalArgumentException e) {
      logger.warn("Failed to convert originalFunctionCall argument.", e);
      return Optional.empty();
    }
  }

  private Maybe<Event> assembleEvent(
      InvocationContext invocationContext,
      Collection<FunctionCall> functionCalls,
      Map<String, ToolConfirmation> toolConfirmations) {
    Single<ImmutableMap<String, BaseTool>> toolsMapSingle;
    if (invocationContext.agent() instanceof LlmAgent llmAgent) {
      toolsMapSingle =
          llmAgent
              .tools()
              .map(
                  toolList ->
                      toolList.stream().collect(toImmutableMap(BaseTool::name, tool -> tool)));
    } else {
      toolsMapSingle = Single.just(ImmutableMap.of());
    }

    var functionCallEvent =
        Event.builder()
            .content(
                Content.builder()
                    .parts(
                        functionCalls.stream()
                            .map(fc -> Part.builder().functionCall(fc).build())
                            .collect(toImmutableList()))
                    .build())
            .build();

    Context parentContext = Context.current();
    return toolsMapSingle
        .flatMapMaybe(
            toolsMap ->
                Functions.handleFunctionCalls(
                    invocationContext, functionCallEvent, toolsMap, toolConfirmations))
        .compose(Tracing.withContext(parentContext));
  }

  private static Optional<Map.Entry<String, ToolConfirmation>> maybeCreateToolConfirmationEntry(
      FunctionResponse functionResponse) {
    Map<String, Object> responseMap = functionResponse.response().orElse(ImmutableMap.of());
    if (responseMap.size() != 1 || !responseMap.containsKey("response")) {
      return Optional.of(
          Map.entry(
              functionResponse.id().get(),
              objectMapper.convertValue(responseMap, ToolConfirmation.class)));
    }

    try {
      return Optional.of(
          Map.entry(
              functionResponse.id().get(),
              objectMapper.readValue(
                  (String) responseMap.get("response"), ToolConfirmation.class)));
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse tool confirmation response", e);
    }

    return Optional.empty();
  }

  private record ConfirmationResult(
      ImmutableMap<String, ToolConfirmation> responses, int eventIndex) {}

  /** A tool function call from session history, together with the author of its event. */
  private record AuthoredFunctionCall(String author, FunctionCall functionCall) {}
}
