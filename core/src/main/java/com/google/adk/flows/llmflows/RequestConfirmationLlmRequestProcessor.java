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
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Collection;
import java.util.HashMap;
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

    // Search backwards from the event before confirmation for the corresponding
    // request_confirmation function calls emitted by the model.
    for (int i = finalConfirmationEventIndex - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (event.functionCalls().isEmpty()) {
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
                      .ifPresent(
                          ofc -> {
                            toolsToResumeWithConfirmation.put(
                                ofc.id().get(),
                                requestConfirmationFunctionResponses.get(fc.id().get()));
                            toolsToResumeWithArgs.put(ofc.id().get(), ofc);
                          }));

      if (toolsToResumeWithConfirmation.isEmpty()) {
        continue;
      }

      // If a tool has been confirmed, it might have been executed by a subsequent
      // processor, or in a subsequent turn. We identify tools that have already been
      // executed by checking for function responses with matching IDs in events that
      // occurred *after* the user confirmation event.
      ImmutableSet<String> alreadyConfirmedIds =
          events.subList(finalConfirmationEventIndex + 1, events.size()).stream()
              .flatMap(e -> e.functionResponses().stream())
              .map(FunctionResponse::id)
              .flatMap(Optional::stream)
              .collect(toImmutableSet());
      toolsToResumeWithConfirmation.keySet().removeAll(alreadyConfirmedIds);
      toolsToResumeWithArgs.keySet().removeAll(alreadyConfirmedIds);

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

    return toolsMapSingle.flatMapMaybe(
        toolsMap ->
            Functions.handleFunctionCalls(
                invocationContext, functionCallEvent, toolsMap, toolConfirmations));
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
}
