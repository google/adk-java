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
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.OnToolErrorCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig.ToolExecutionMode;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.telemetry.Tracing;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for handling function calls. */
public final class Functions {
  /** The function call name for the request confirmation function. */
  public static final String REQUEST_CONFIRMATION_FUNCTION_CALL_NAME = "adk_request_confirmation";

  /** Session state key for storing the security policy outcomes for tool calls. */
  public static final String TOOL_CALL_SECURITY_STATES = "adk_tool_call_security_states";

  private static final String AF_FUNCTION_CALL_ID_PREFIX = "adk-";
  private static final Logger logger = LoggerFactory.getLogger(Functions.class);

  /** Generates a unique ID for a function call. */
  public static String generateClientFunctionCallId() {
    return AF_FUNCTION_CALL_ID_PREFIX + UUID.randomUUID();
  }

  /**
   * Populates missing function call IDs in the provided event's content.
   *
   * <p>If the event contains function calls without an ID, this method generates a unique
   * client-side ID for each and updates the event content.
   *
   * @param modelResponseEvent The event potentially containing function calls.
   */
  public static void populateClientFunctionCallId(Event modelResponseEvent) {
    Optional<Content> originalContentOptional = modelResponseEvent.content();
    if (originalContentOptional.isEmpty()) {
      return;
    }
    Content originalContent = originalContentOptional.get();
    List<Part> originalParts = originalContent.parts().orElse(ImmutableList.of());
    if (originalParts.stream().noneMatch(part -> part.functionCall().isPresent())) {
      return; // No function calls to process
    }

    List<Part> newParts = new ArrayList<>();
    boolean modified = false;
    for (Part part : originalParts) {
      if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        if (functionCall.id().isEmpty() || functionCall.id().get().isEmpty()) {
          FunctionCall updatedFunctionCall =
              functionCall.toBuilder().id(generateClientFunctionCallId()).build();
          newParts.add(part.toBuilder().functionCall(updatedFunctionCall).build());
          modified = true;
        } else {
          newParts.add(part); // Keep original part if ID exists
        }
      } else {
        newParts.add(part); // Keep non-function call parts
      }
    }

    if (modified) {
      String role =
          originalContent
              .role()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Content role is missing in event: " + modelResponseEvent.id()));
      Content newContent = Content.builder().role(role).parts(newParts).build();
      modelResponseEvent.setContent(Optional.of(newContent));
    }
  }

  // TODO - b/413761119 add the remaining methods for function call id.

  /** Handles standard, non-streaming function calls. */
  public static Maybe<Event> handleFunctionCalls(
      InvocationContext invocationContext, Event functionCallEvent, Map<String, BaseTool> tools) {
    return handleFunctionCalls(invocationContext, functionCallEvent, tools, ImmutableMap.of());
  }

  /** Handles standard, non-streaming function calls with tool confirmations. */
  public static Maybe<Event> handleFunctionCalls(
      InvocationContext invocationContext,
      Event functionCallEvent,
      Map<String, BaseTool> tools,
      Map<String, ToolConfirmation> toolConfirmations) {
    ImmutableList<FunctionCall> functionCalls = functionCallEvent.functionCalls();

    for (FunctionCall functionCall : functionCalls) {
      if (!tools.containsKey(functionCall.name().get())) {
        throw new VerifyException("Tool not found: " + functionCall.name().get());
      }
    }

    Context parentContext = Context.current();
    Function<FunctionCall, Maybe<Event>> functionCallMapper =
        getFunctionCallMapper(invocationContext, tools, toolConfirmations, false, parentContext);

    Observable<Event> functionResponseEventsObservable;
    if (invocationContext.runConfig().toolExecutionMode() == ToolExecutionMode.SEQUENTIAL) {
      functionResponseEventsObservable =
          Observable.fromIterable(functionCalls).concatMapMaybe(functionCallMapper);
    } else {
      functionResponseEventsObservable =
          Observable.fromIterable(functionCalls)
              .concatMapEager(call -> functionCallMapper.apply(call).toObservable());
    }
    return functionResponseEventsObservable
        .toList()
        .flatMapMaybe(
            events -> {
              if (events.isEmpty()) {
                return Maybe.empty();
              }
              Optional<Event> maybeMergedEvent =
                  Functions.mergeParallelFunctionResponseEvents(events);
              if (maybeMergedEvent.isEmpty()) {
                return Maybe.empty();
              }
              var mergedEvent = maybeMergedEvent.get();

              if (events.size() > 1) {
                return Maybe.just(mergedEvent)
                    .doOnSuccess(event -> Tracing.traceToolResponse(event.id(), event))
                    .compose(Tracing.trace("tool_response", parentContext));
              }
              return Maybe.just(mergedEvent);
            });
  }

  /**
   * Handles function calls in a live/streaming context, supporting background execution and stream
   * termination.
   */
  public static Maybe<Event> handleFunctionCallsLive(
      InvocationContext invocationContext, Event functionCallEvent, Map<String, BaseTool> tools) {
    return handleFunctionCallsLive(invocationContext, functionCallEvent, tools, ImmutableMap.of());
  }

  /**
   * Handles function calls in a live/streaming context with tool confirmations, supporting
   * background execution and stream termination.
   */
  public static Maybe<Event> handleFunctionCallsLive(
      InvocationContext invocationContext,
      Event functionCallEvent,
      Map<String, BaseTool> tools,
      Map<String, ToolConfirmation> toolConfirmations) {
    ImmutableList<FunctionCall> functionCalls = functionCallEvent.functionCalls();

    for (FunctionCall functionCall : functionCalls) {
      if (!tools.containsKey(functionCall.name().get())) {
        throw new VerifyException("Tool not found: " + functionCall.name().get());
      }
    }

    Context parentContext = Context.current();
    Function<FunctionCall, Maybe<Event>> functionCallMapper =
        getFunctionCallMapper(invocationContext, tools, toolConfirmations, true, parentContext);

    Observable<Event> responseEventsObservable;
    if (invocationContext.runConfig().toolExecutionMode() == ToolExecutionMode.SEQUENTIAL) {
      responseEventsObservable =
          Observable.fromIterable(functionCalls).concatMapMaybe(functionCallMapper);
    } else {
      responseEventsObservable =
          Observable.fromIterable(functionCalls)
              .concatMapEager(call -> functionCallMapper.apply(call).toObservable());
    }

    return responseEventsObservable
        .toList()
        .flatMapMaybe(
            events -> {
              if (events.isEmpty()) {
                return Maybe.empty();
              }
              return Maybe.just(Functions.mergeParallelFunctionResponseEvents(events).orElse(null));
            });
  }

  private static Function<FunctionCall, Maybe<Event>> getFunctionCallMapper(
      InvocationContext invocationContext,
      Map<String, BaseTool> tools,
      Map<String, ToolConfirmation> toolConfirmations,
      boolean isLive,
      Context parentContext) {
    return functionCall ->
        Maybe.defer(
            () -> {
              try (Scope scope = parentContext.makeCurrent()) {
                BaseTool tool = tools.get(functionCall.name().get());
                ToolContext toolContext =
                    ToolContext.builder(invocationContext)
                        .functionCallId(functionCall.id().orElse(""))
                        .toolConfirmation(
                            functionCall.id().map(toolConfirmations::get).orElse(null))
                        .build();

                Map<String, Object> functionArgs = functionCall.args().orElse(new HashMap<>());

                Maybe<Map<String, Object>> maybeFunctionResult =
                    maybeInvokeBeforeToolCall(invocationContext, tool, functionArgs, toolContext)
                        .switchIfEmpty(
                            Maybe.defer(
                                () -> {
                                  try (Scope innerScope = parentContext.makeCurrent()) {
                                    return isLive
                                        ? processFunctionLive(
                                            invocationContext,
                                            tool,
                                            toolContext,
                                            functionCall,
                                            functionArgs,
                                            parentContext)
                                        : callTool(tool, functionArgs, toolContext, parentContext);
                                  }
                                }));

                return postProcessFunctionResult(
                    maybeFunctionResult,
                    invocationContext,
                    tool,
                    functionArgs,
                    toolContext,
                    isLive,
                    parentContext);
              }
            });
  }

  /**
   * Processes a single function call in a live context. Manages starting, stopping, and running
   * tools.
   */
  private static Maybe<Map<String, Object>> processFunctionLive(
      InvocationContext invocationContext,
      BaseTool tool,
      ToolContext toolContext,
      FunctionCall functionCall,
      Map<String, Object> args,
      Context parentContext) {
    // Case 1: Handle a call to stopStreaming
    if (functionCall.name().get().equals("stopStreaming") && args.containsKey("functionName")) {
      String functionNameToStop = (String) args.get("functionName");
      ActiveStreamingTool activeTool =
          invocationContext.activeStreamingTools().get(functionNameToStop);
      if (activeTool != null) {
        // Dispose the running task if it exists and is not disposed
        if (activeTool.task() != null && !activeTool.task().isDisposed()) {
          activeTool.task().dispose();
        }
        // Close the associated output stream if it exists
        if (activeTool.stream() != null) {
          activeTool.stream().close();
        }
        invocationContext.activeStreamingTools().remove(functionNameToStop);
        logger.info("Successfully stopped streaming function {}", functionNameToStop);
        return Maybe.just(
            ImmutableMap.of(
                "status", "Successfully stopped streaming function " + functionNameToStop));
      } else {
        logger.warn("No active streaming function named {} found to stop", functionNameToStop);
        return Maybe.just(
            ImmutableMap.of("status", "No active streaming function named " + functionNameToStop));
      }
    }

    // Case 2: Handle a streaming-capable tool (FunctionTool with Flowable return type)
    if (tool instanceof FunctionTool functionTool) {
      if (functionTool.isStreaming()) {
        try {
          Flowable<Map<String, Object>> toolOutputStream =
              functionTool.callLive(args, toolContext, invocationContext);

          // Subscribe to the tool's output to process results in the background.
          Disposable subscription =
              toolOutputStream.subscribe(
                  result -> {
                    String resultText = "Function " + tool.name() + " returned: " + result;
                    Content updateContent =
                        Content.builder().role("user").parts(Part.fromText(resultText)).build();
                    invocationContext.liveRequestQueue().get().content(updateContent);
                  },
                  error -> logger.error("Error in streaming tool " + tool.name(), error.getCause()),
                  () -> {
                    logger.info("Streaming tool {} completed.", tool.name());
                    invocationContext.activeStreamingTools().remove(tool.name());
                  });

          ActiveStreamingTool activeTool =
              invocationContext
                  .activeStreamingTools()
                  .computeIfAbsent(tool.name(), unused -> new ActiveStreamingTool(subscription));
          activeTool.task(subscription);
          invocationContext.activeStreamingTools().put(tool.name(), activeTool);

          return Maybe.just(
              ImmutableMap.of(
                  "status", "The function is running asynchronously and the results are pending."));

        } catch (Exception e) {
          logger.error("Failed to start streaming tool: " + tool.name(), e);
          return Maybe.error(e);
        }
      }
    }

    // Case 3: Fallback for regular, non-streaming tools
    return callTool(tool, args, toolContext, parentContext);
  }

  public static Set<String> getLongRunningFunctionCalls(
      List<FunctionCall> functionCalls, Map<String, BaseTool> tools) {
    Set<String> longRunningFunctionCalls = new HashSet<>();
    for (FunctionCall functionCall : functionCalls) {
      if (!tools.containsKey(functionCall.name().get())) {
        continue;
      }
      BaseTool tool = tools.get(functionCall.name().get());
      if (tool != null && tool.longRunning()) {
        longRunningFunctionCalls.add(functionCall.id().orElse(""));
      }
    }
    return longRunningFunctionCalls;
  }

  private static Maybe<Event> postProcessFunctionResult(
      Maybe<Map<String, Object>> maybeFunctionResult,
      InvocationContext invocationContext,
      BaseTool tool,
      Map<String, Object> functionArgs,
      ToolContext toolContext,
      boolean isLive,
      Context parentContext) {
    return maybeFunctionResult
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .onErrorResumeNext(
            t -> {
              Maybe<Map<String, Object>> errorCallbackResult =
                  handleOnToolErrorCallback(invocationContext, tool, functionArgs, toolContext, t);
              Maybe<Optional<Map<String, Object>>> mappedResult;
              if (isLive) {
                // In live mode, handle null results from the error callback gracefully.
                mappedResult = errorCallbackResult.map(Optional::ofNullable);
              } else {
                // In non-live mode, a null result from the error callback will cause an NPE
                // when wrapped with Optional.of(), potentially matching prior behavior.
                mappedResult = errorCallbackResult.map(Optional::of);
              }
              return mappedResult.switchIfEmpty(Single.error(t));
            })
        .flatMapMaybe(
            optionalInitialResult -> {
              try (Scope scope = parentContext.makeCurrent()) {
                Map<String, Object> initialFunctionResult = optionalInitialResult.orElse(null);

                return maybeInvokeAfterToolCall(
                        invocationContext, tool, functionArgs, toolContext, initialFunctionResult)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.ofNullable(initialFunctionResult))
                    .flatMapMaybe(
                        finalOptionalResult -> {
                          Map<String, Object> finalFunctionResult =
                              finalOptionalResult.orElse(null);
                          if (tool.longRunning() && finalFunctionResult == null) {
                            return Maybe.empty();
                          }
                          return Maybe.fromCallable(
                                  () ->
                                      buildResponseEvent(
                                          tool,
                                          finalFunctionResult,
                                          toolContext,
                                          invocationContext))
                              .compose(
                                  Tracing.trace(
                                      "tool_response [" + tool.name() + "]", parentContext))
                              .doOnSuccess(event -> Tracing.traceToolResponse(event.id(), event));
                        });
              }
            });
  }

  private static Optional<Event> mergeParallelFunctionResponseEvents(
      List<Event> functionResponseEvents) {
    if (functionResponseEvents.isEmpty()) {
      return Optional.empty();
    }
    if (functionResponseEvents.size() == 1) {
      return Optional.of(functionResponseEvents.get(0));
    }
    // Use the first event as the base for common attributes
    Event baseEvent = functionResponseEvents.get(0);

    List<Part> mergedParts = new ArrayList<>();
    for (Event event : functionResponseEvents) {
      event.content().flatMap(Content::parts).ifPresent(mergedParts::addAll);
    }

    // Merge actions from all events
    // TODO: validate that pending actions are not cleared away
    EventActions.Builder mergedActionsBuilder = EventActions.builder();
    for (Event event : functionResponseEvents) {
      mergedActionsBuilder.merge(event.actions());
    }

    return Optional.of(
        Event.builder()
            .id(Event.generateEventId())
            .invocationId(baseEvent.invocationId())
            .author(baseEvent.author())
            .branch(baseEvent.branch())
            .content(Optional.of(Content.builder().role("user").parts(mergedParts).build()))
            .actions(mergedActionsBuilder.build())
            .timestamp(baseEvent.timestamp())
            .build());
  }

  private static Maybe<Map<String, Object>> maybeInvokeBeforeToolCall(
      InvocationContext invocationContext,
      BaseTool tool,
      Map<String, Object> functionArgs,
      ToolContext toolContext) {
    if (invocationContext.agent() instanceof LlmAgent) {
      LlmAgent agent = (LlmAgent) invocationContext.agent();

      HashMap<String, Object> mutableFunctionArgs = new HashMap<>(functionArgs);

      Maybe<Map<String, Object>> pluginResult =
          invocationContext
              .pluginManager()
              .beforeToolCallback(tool, mutableFunctionArgs, toolContext);

      List<? extends BeforeToolCallback> callbacks = agent.canonicalBeforeToolCallbacks();
      if (callbacks.isEmpty()) {
        return pluginResult;
      }

      Maybe<Map<String, Object>> callbackResult =
          Maybe.defer(
              () ->
                  Flowable.fromIterable(callbacks)
                      .concatMapMaybe(
                          callback ->
                              callback.call(
                                  invocationContext, tool, mutableFunctionArgs, toolContext))
                      .firstElement());

      return pluginResult.switchIfEmpty(callbackResult);
    }
    return Maybe.empty();
  }

  /**
   * Invokes {@link OnToolErrorCallback}s when a tool call fails. If any returns a response, it's
   * used instead of the error.
   *
   * @return A {@link Maybe} with the override result.
   */
  private static Maybe<Map<String, Object>> handleOnToolErrorCallback(
      InvocationContext invocationContext,
      BaseTool tool,
      Map<String, Object> functionArgs,
      ToolContext toolContext,
      Throwable throwable) {
    Exception ex = throwable instanceof Exception exception ? exception : new Exception(throwable);

    Maybe<Map<String, Object>> pluginResult =
        invocationContext
            .pluginManager()
            .onToolErrorCallback(tool, functionArgs, toolContext, throwable);

    if (invocationContext.agent() instanceof LlmAgent) {
      LlmAgent agent = (LlmAgent) invocationContext.agent();

      List<? extends OnToolErrorCallback> callbacks = agent.canonicalOnToolErrorCallbacks();
      if (callbacks.isEmpty()) {
        return pluginResult;
      }

      Maybe<Map<String, Object>> callbackResult =
          Maybe.defer(
              () ->
                  Flowable.fromIterable(callbacks)
                      .concatMapMaybe(
                          callback ->
                              callback.call(invocationContext, tool, functionArgs, toolContext, ex))
                      .firstElement());

      return pluginResult.switchIfEmpty(callbackResult);
    }
    return pluginResult;
  }

  private static Maybe<Map<String, Object>> maybeInvokeAfterToolCall(
      InvocationContext invocationContext,
      BaseTool tool,
      Map<String, Object> functionArgs,
      ToolContext toolContext,
      Map<String, Object> functionResult) {
    if (invocationContext.agent() instanceof LlmAgent) {
      LlmAgent agent = (LlmAgent) invocationContext.agent();

      Maybe<Map<String, Object>> pluginResult =
          invocationContext
              .pluginManager()
              .afterToolCallback(tool, functionArgs, toolContext, functionResult);

      List<? extends AfterToolCallback> callbacks = agent.canonicalAfterToolCallbacks();
      if (callbacks.isEmpty()) {
        return pluginResult;
      }

      Maybe<Map<String, Object>> callbackResult =
          Maybe.defer(
              () ->
                  Flowable.fromIterable(callbacks)
                      .concatMapMaybe(
                          callback ->
                              callback.call(
                                  invocationContext,
                                  tool,
                                  functionArgs,
                                  toolContext,
                                  functionResult))
                      .firstElement());

      return pluginResult.switchIfEmpty(callbackResult);
    }
    return Maybe.empty();
  }

  private static Maybe<Map<String, Object>> callTool(
      BaseTool tool, Map<String, Object> args, ToolContext toolContext, Context parentContext) {
    return tool.runAsync(args, toolContext)
        .toMaybe()
        .doOnSubscribe(
            d ->
                Tracing.traceToolCall(
                    tool.name(), tool.description(), tool.getClass().getSimpleName(), args))
        .doOnError(t -> Span.current().recordException(t))
        .compose(Tracing.trace("tool_call [" + tool.name() + "]", parentContext))
        .onErrorResumeNext(
            e ->
                Maybe.error(
                    e instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new RuntimeException("Failed to call tool: " + tool.name(), e)));
  }

  private static Event buildResponseEvent(
      BaseTool tool,
      Map<String, Object> response,
      ToolContext toolContext,
      InvocationContext invocationContext) {
    // use an empty placeholder response if tool response is null.
    Map<String, Object> finalResponse = response != null ? response : new HashMap<>();

    Part partFunctionResponse =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .id(toolContext.functionCallId().orElse(""))
                    .name(tool.name())
                    .response(finalResponse)
                    .build())
            .build();

    return Event.builder()
        .id(Event.generateEventId())
        .invocationId(invocationContext.invocationId())
        .author(invocationContext.agent().name())
        .branch(invocationContext.branch())
        .content(Content.builder().role("user").parts(partFunctionResponse).build())
        .actions(toolContext.eventActions())
        .build();
  }

  /**
   * Generates a request confirmation event from a function response event.
   *
   * @param invocationContext The invocation context.
   * @param functionCallEvent The event containing the original function call.
   * @param functionResponseEvent The event containing the function response.
   * @return An optional event containing the request confirmation function call.
   */
  public static Optional<Event> generateRequestConfirmationEvent(
      InvocationContext invocationContext, Event functionCallEvent, Event functionResponseEvent) {
    if (functionResponseEvent.actions().requestedToolConfirmations().isEmpty()) {
      return Optional.empty();
    }

    List<Part> parts = new ArrayList<>();
    Set<String> longRunningToolIds = new HashSet<>();
    ImmutableMap<String, FunctionCall> functionCallsById =
        functionCallEvent.functionCalls().stream()
            .filter(fc -> fc.id().isPresent())
            .collect(toImmutableMap(fc -> fc.id().get(), fc -> fc));

    for (Map.Entry<String, ToolConfirmation> entry :
        functionResponseEvent.actions().requestedToolConfirmations().entrySet().stream()
            .filter(fc -> functionCallsById.containsKey(fc.getKey()))
            .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue))
            .entrySet()) {

      FunctionCall requestConfirmationFunctionCall =
          FunctionCall.builder()
              .name(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
              .args(
                  ImmutableMap.of(
                      "originalFunctionCall",
                      functionCallsById.get(entry.getKey()),
                      "toolConfirmation",
                      entry.getValue()))
              .id(generateClientFunctionCallId())
              .build();

      longRunningToolIds.add(requestConfirmationFunctionCall.id().get());
      parts.add(Part.builder().functionCall(requestConfirmationFunctionCall).build());
    }

    if (parts.isEmpty()) {
      return Optional.empty();
    }

    var contentBuilder = Content.builder().parts(parts);
    functionResponseEvent.content().flatMap(Content::role).ifPresent(contentBuilder::role);

    return Optional.of(
        Event.builder()
            .invocationId(invocationContext.invocationId())
            .author(invocationContext.agent().name())
            .branch(invocationContext.branch())
            .content(contentBuilder.build())
            .longRunningToolIds(longRunningToolIds)
            .build());
  }

  /**
   * Gets the ask user confirmation function calls from the event.
   *
   * @param event The event to extract function calls from.
   * @return A list of function calls for asking user confirmation.
   */
  public static ImmutableList<FunctionCall> getAskUserConfirmationFunctionCalls(Event event) {
    return event.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
        .flatMap(part -> part.functionCall().stream())
        .filter(Functions::isRequestConfirmationFunctionCall)
        .collect(toImmutableList());
  }

  private static boolean isRequestConfirmationFunctionCall(FunctionCall functionCall) {
    return functionCall
        .name()
        .map(name -> name.equals(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME))
        .orElse(false);
  }

  private Functions() {}
}
