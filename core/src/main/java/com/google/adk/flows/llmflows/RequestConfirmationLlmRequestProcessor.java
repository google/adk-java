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
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolConfirmation;
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
  private static final ObjectMapper OBJECT_MAPPER = JsonBaseModel.getMapper();

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext invocationContext, LlmRequest llmRequest) {
    ImmutableList<Event> events = ImmutableList.copyOf(invocationContext.session().events());
    if (events.isEmpty()) {
      logger.info(
          "No events are present in the session. Skipping request confirmation processing.");
      return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }

    ImmutableMap<String, ToolConfirmation> responses = ImmutableMap.of();
    int confirmationEventIndex = -1;
    for (int i = events.size() - 1; i >= 0; i--) {
      Event event = events.get(i);
      if (!Objects.equals(event.author(), "user")) {
        continue;
      }
      if (event.functionResponses().isEmpty()) {
        continue;
      }
      responses =
          event.functionResponses().stream()
              .filter(functionResponse -> functionResponse.id().isPresent())
              .filter(
                  functionResponse ->
                      Objects.equals(
                          functionResponse.name().orElse(null),
                          REQUEST_CONFIRMATION_FUNCTION_CALL_NAME))
              .map(this::maybeCreateToolConfirmationEntry)
              .flatMap(Optional::stream)
              .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
      confirmationEventIndex = i;
      break;
    }

    // Make it final to enable access from lambda expressions.
    final ImmutableMap<String, ToolConfirmation> requestConfirmationFunctionResponses = responses;

    if (requestConfirmationFunctionResponses.isEmpty()) {
      logger.info("No request confirmation function responses found.");
      return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }

    for (int i = events.size() - 2; i >= 0; i--) {
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

      // Remove the tools that have already been confirmed.
      ImmutableSet<String> alreadyConfirmedIds =
          events.subList(confirmationEventIndex + 1, events.size()).stream()
              .flatMap(e -> e.functionResponses().stream())
              .map(FunctionResponse::id)
              .flatMap(Optional::stream)
              .collect(toImmutableSet());
      toolsToResumeWithConfirmation.keySet().removeAll(alreadyConfirmedIds);
      toolsToResumeWithArgs.keySet().removeAll(alreadyConfirmedIds);

      if (toolsToResumeWithConfirmation.isEmpty()) {
        continue;
      }

      return assembleEvent(
              invocationContext,
              toolsToResumeWithArgs.values(),
              ImmutableMap.copyOf(toolsToResumeWithConfirmation))
          .map(e -> RequestProcessingResult.create(llmRequest, ImmutableList.of(e)))
          .toSingle();
    }

    return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
  }

  private Optional<FunctionCall> getOriginalFunctionCall(FunctionCall functionCall) {
    if (!functionCall.args().orElse(ImmutableMap.of()).containsKey("originalFunctionCall")) {
      return Optional.empty();
    }
    try {
      FunctionCall originalFunctionCall =
          OBJECT_MAPPER.convertValue(
              functionCall.args().get().get("originalFunctionCall"), FunctionCall.class);
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
    ImmutableMap.Builder<String, BaseTool> toolsBuilder = ImmutableMap.builder();
    if (invocationContext.agent() instanceof LlmAgent llmAgent) {
      for (BaseTool tool : llmAgent.tools()) {
        toolsBuilder.put(tool.name(), tool);
      }
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

    return Functions.handleFunctionCalls(
        invocationContext, functionCallEvent, toolsBuilder.buildOrThrow(), toolConfirmations);
  }

  private Optional<Map.Entry<String, ToolConfirmation>> maybeCreateToolConfirmationEntry(
      FunctionResponse functionResponse) {
    Map<String, Object> responseMap = functionResponse.response().orElse(ImmutableMap.of());
    if (responseMap.size() != 1 || !responseMap.containsKey("response")) {
      return Optional.of(
          Map.entry(
              functionResponse.id().get(),
              OBJECT_MAPPER.convertValue(responseMap, ToolConfirmation.class)));
    }

    try {
      return Optional.of(
          Map.entry(
              functionResponse.id().get(),
              OBJECT_MAPPER.readValue(
                  (String) responseMap.get("response"), ToolConfirmation.class)));
    } catch (JsonProcessingException e) {
      logger.error("Failed to parse tool confirmation response", e);
    }

    return Optional.empty();
  }
}
