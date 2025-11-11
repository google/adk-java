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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolConfirmation;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handles tool confirmation information to build the LLM request. */
public class RequestConfirmationLlmRequestProcessor implements RequestProcessor {
  private static final Logger logger =
      LoggerFactory.getLogger(RequestConfirmationLlmRequestProcessor.class);
  private final ObjectMapper objectMapper;

  public RequestConfirmationLlmRequestProcessor() {
    objectMapper = new ObjectMapper().registerModule(new Jdk8Module());
  }

  @Override
  public Single<RequestProcessor.RequestProcessingResult> processRequest(
      InvocationContext invocationContext, LlmRequest llmRequest) {
    List<Event> events = invocationContext.session().events();
    if (events.isEmpty()) {
      logger.info(
          "No events are present in the session. Skipping request confirmation processing.");
      return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }

    ImmutableMap<String, ToolConfirmation> requestConfirmationFunctionResponses =
        filterRequestConfirmationFunctionResponses(events);
    if (requestConfirmationFunctionResponses.isEmpty()) {
      logger.info("No request confirmation function responses found.");
      return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
    }

    for (ImmutableList<FunctionCall> functionCalls :
        events.stream()
            .map(Event::functionCalls)
            .filter(fc -> !fc.isEmpty())
            .collect(toImmutableList())) {

      ImmutableMap<String, FunctionCall> toolsToResumeWithArgs =
          filterToolsToResumeWithArgs(functionCalls, requestConfirmationFunctionResponses);
      ImmutableMap<String, ToolConfirmation> toolsToResumeWithConfirmation =
          toolsToResumeWithArgs.keySet().stream()
              .filter(
                  id ->
                      events.stream()
                          .flatMap(e -> e.functionResponses().stream())
                          .anyMatch(fr -> Objects.equals(fr.id().orElse(null), id)))
              .collect(toImmutableMap(k -> k, requestConfirmationFunctionResponses::get));
      if (toolsToResumeWithConfirmation.isEmpty()) {
        logger.info("No tools to resume with confirmation.");
        continue;
      }

      return assembleEvent(
              invocationContext, toolsToResumeWithArgs.values(), toolsToResumeWithConfirmation)
          .map(event -> RequestProcessingResult.create(llmRequest, ImmutableList.of(event)))
          .toSingle();
    }

    return Single.just(RequestProcessingResult.create(llmRequest, ImmutableList.of()));
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

  private ImmutableMap<String, ToolConfirmation> filterRequestConfirmationFunctionResponses(
      List<Event> events) {
    return events.stream()
        .filter(event -> Objects.equals(event.author(), "user"))
        .flatMap(event -> event.functionResponses().stream())
        .filter(functionResponse -> functionResponse.id().isPresent())
        .filter(
            functionResponse ->
                Objects.equals(
                    functionResponse.name().orElse(null), REQUEST_CONFIRMATION_FUNCTION_CALL_NAME))
        .map(this::maybeCreateToolConfirmationEntry)
        .flatMap(Optional::stream)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private Optional<Map.Entry<String, ToolConfirmation>> maybeCreateToolConfirmationEntry(
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

  private ImmutableMap<String, FunctionCall> filterToolsToResumeWithArgs(
      ImmutableList<FunctionCall> functionCalls,
      Map<String, ToolConfirmation> requestConfirmationFunctionResponses) {
    return functionCalls.stream()
        .filter(fc -> fc.id().isPresent())
        .filter(fc -> requestConfirmationFunctionResponses.containsKey(fc.id().get()))
        .filter(
            fc -> Objects.equals(fc.name().orElse(null), REQUEST_CONFIRMATION_FUNCTION_CALL_NAME))
        .filter(fc -> fc.args().orElse(ImmutableMap.of()).containsKey("originalFunctionCall"))
        .collect(
            toImmutableMap(
                fc -> fc.id().get(),
                fc ->
                    objectMapper.convertValue(
                        fc.args().get().get("originalFunctionCall"), FunctionCall.class)));
  }
}
