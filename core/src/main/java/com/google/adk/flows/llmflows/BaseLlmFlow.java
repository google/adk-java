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

import com.google.adk.Telemetry;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.exceptions.LlmCallsLimitExceededException;
import com.google.adk.flows.BaseFlow;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.flows.llmflows.ResponseProcessor.ResponseProcessingResult;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.observers.DisposableCompletableObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A basic flow that calls the LLM in a loop until a final response is generated. */
public abstract class BaseLlmFlow implements BaseFlow {
  private static final Logger logger = LoggerFactory.getLogger(BaseLlmFlow.class);

  protected final List<RequestProcessor> requestProcessors;
  protected final List<ResponseProcessor> responseProcessors;

  public BaseLlmFlow(
      List<RequestProcessor> requestProcessors, List<ResponseProcessor> responseProcessors) {
    this.requestProcessors = requestProcessors;
    this.responseProcessors = responseProcessors;
  }

  /**
   * Pre-processes the LLM request before sending it to the LLM. Executes all registered {@link
   * RequestProcessor}.
   */
  protected Single<RequestProcessingResult> preprocess(
      InvocationContext context, LlmRequest llmRequest) {

    List<Iterable<Event>> eventIterables = new ArrayList<>();
    LlmAgent agent = (LlmAgent) context.agent();

    Single<LlmRequest> processedRequestSingle =
        Flowable.fromIterable(requestProcessors)
            .reduce(
                llmRequest,
                (currentRequest, processor) ->
                    processor
                        .processRequest(context, currentRequest)
                        .doOnSuccess(
                            result -> {
                              if (result.events() != null) {
                                eventIterables.add(result.events());
                              }
                            })
                        .map(RequestProcessingResult::updatedRequest)
                        .blockingGet());

    return processedRequestSingle.flatMap(
        processedRequest -> {
          LlmRequest.Builder updatedRequestBuilder = processedRequest.toBuilder();

          Completable toolProcessingCompletable =
              Flowable.fromIterable(agent.tools())
                  .concatMapCompletable(
                      tool ->
                          tool.processLlmRequest(
                              updatedRequestBuilder, ToolContext.builder(context).build()));

          return toolProcessingCompletable.andThen(
              Single.fromCallable(
                  () -> {
                    Iterable<Event> combinedEvents = Iterables.concat(eventIterables);
                    return RequestProcessingResult.create(
                        updatedRequestBuilder.build(), combinedEvents);
                  }));
        });
  }

  /**
   * Post-processes the LLM response after receiving it from the LLM. Executes all registered {@link
   * ResponseProcessor} instances. Handles function calls if present in the response.
   */
  protected Single<ResponseProcessingResult> postprocess(
      InvocationContext context,
      Event baseEventForLlmResponse,
      LlmRequest llmRequest,
      LlmResponse llmResponse) {

    List<Iterable<Event>> eventIterables = new ArrayList<>();
    LlmResponse currentLlmResponse = llmResponse;
    for (ResponseProcessor processor : responseProcessors) {
      ResponseProcessingResult result =
          processor.processResponse(context, currentLlmResponse).blockingGet();
      if (result.events() != null) {
        eventIterables.add(result.events());
      }
      currentLlmResponse = result.updatedResponse();
    }
    LlmResponse updatedResponse = currentLlmResponse;

    if (updatedResponse.content().isEmpty()
        && updatedResponse.errorCode().isEmpty()
        && !updatedResponse.interrupted().orElse(false)
        && !updatedResponse.turnComplete().orElse(false)) {
      return Single.just(
          ResponseProcessingResult.create(
              updatedResponse, Iterables.concat(eventIterables), Optional.empty()));
    }

    logger.debug("Response after processors: {}", updatedResponse);
    Event modelResponseEvent =
        buildModelResponseEvent(baseEventForLlmResponse, llmRequest, updatedResponse);
    eventIterables.add(Collections.singleton(modelResponseEvent));
    logger.debug("Model response event: {}", modelResponseEvent.toJson());

    Maybe<Event> maybeFunctionCallEvent =
        modelResponseEvent.functionCalls().isEmpty()
            ? Maybe.empty()
            : Functions.handleFunctionCalls(context, modelResponseEvent, llmRequest.tools());

    return maybeFunctionCallEvent
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .map(
            functionCallEventOpt -> {
              Optional<String> transferToAgent = Optional.empty();
              if (functionCallEventOpt.isPresent()) {
                Event functionCallEvent = functionCallEventOpt.get();
                logger.debug("Function call event generated: {}", functionCallEvent);
                eventIterables.add(Collections.singleton(functionCallEvent));
                transferToAgent = functionCallEvent.actions().transferToAgent();
              }
              Iterable<Event> combinedEvents = Iterables.concat(eventIterables);
              return ResponseProcessingResult.create(
                  updatedResponse, combinedEvents, transferToAgent);
            });
  }

  /**
   * Calls the underlying Language Model (LLM) to generate a response based on the provided request.
   * This method orchestrates the LLM invocation, including:
   * <ul>
   * <li>Handling pre-model callbacks via {@code handleBeforeModelCallback}. If a callback
   * provides a response, it's used directly, bypassing the LLM call.</li>
   * <li>Selecting the appropriate LLM instance from the agent's resolved model or {@link LlmRegistry}.</li>
   * <li>Initiating the content generation from the LLM, potentially in streaming mode.</li>
   * <li>Integrating with telemetry for tracing the LLM call (e.g., using OpenTelemetry spans).</li>
   * <li>Handling post-model callbacks via {@code handleAfterModelCallback} after receiving the LLM response.</li>
   * </ul>
   * 
   * @param context The invocation context.
   * @param llmRequest The LLM request.
   * @param eventForCallbackUsage An Event object primarily for providing context (like actions) to
   *     callbacks. Callbacks should not rely on its ID if they create their own separate events.
   */
  private Flowable<LlmResponse> callLlm(
      InvocationContext context, LlmRequest llmRequest, Event eventForCallbackUsage) {
    LlmAgent agent = (LlmAgent) context.agent();

    return handleBeforeModelCallback(context, llmRequest, eventForCallbackUsage)
        .flatMapPublisher(
            beforeResponse -> {
              if (beforeResponse.isPresent()) {
                return Flowable.just(beforeResponse.get());
              }
              BaseLlm llm =
                  agent.resolvedModel().model().isPresent()
                      ? agent.resolvedModel().model().get()
                      : LlmRegistry.getLlm(agent.resolvedModel().modelName().get());
              return Flowable.defer(
                      () -> {
                        Span llmCallSpan =
                            Telemetry.getTracer().spanBuilder("call_llm").startSpan();

                        try (Scope scope = llmCallSpan.makeCurrent()) {
                          return llm.generateContent(
                                  llmRequest,
                                  context.runConfig().streamingMode() == StreamingMode.SSE)
                              .doOnNext(
                                  llmResp -> {
                                    try (Scope innerScope = llmCallSpan.makeCurrent()) {
                                      Telemetry.traceCallLlm(
                                          context, eventForCallbackUsage.id(), llmRequest, llmResp);
                                    }
                                  })
                              .doOnError(
                                  error -> {
                                    llmCallSpan.setStatus(StatusCode.ERROR, error.getMessage());
                                    llmCallSpan.recordException(error);
                                  })
                              .doFinally(llmCallSpan::end);
                        }
                      })
                  .concatMap(
                      llmResp ->
                          handleAfterModelCallback(context, llmResp, eventForCallbackUsage)
                              .toFlowable());
            });
  }

  /**
   * Handles registered {@link BeforeModelCallback}s before an LLM call is made.
   * This method iterates through all {@link BeforeModelCallback}s associated with the agent.
   * If any callback returns a non-empty {@link Optional} {@link LlmResponse},
   * that response is immediately returned, effectively short-circuiting the LLM call.
   *
   * @param context The {@link InvocationContext} of the current operation.
   * @param llmRequest The {@link LlmRequest} that is about to be sent to the LLM.
   * @param modelResponseEvent An {@link Event} object providing context (like actions) for the
   * callbacks. Callbacks should primarily use its actions and should not rely on its ID
   * if they create their own separate events, as this event's ID might represent a
   * higher-level operation.
   * @return A {@link Single} emitting an {@link Optional} {@link LlmResponse}.
   * If a callback provides a response, it's wrapped in {@link Optional#of(LlmResponse)}.
   * Otherwise, an {@link Optional#empty()} is emitted, indicating that the LLM call should proceed.
   */
  private Single<Optional<LlmResponse>> handleBeforeModelCallback(
      InvocationContext context, LlmRequest llmRequest, Event modelResponseEvent) {
    LlmAgent agent = (LlmAgent) context.agent();

    Optional<List<BeforeModelCallback>> callbacksOpt = agent.beforeModelCallback();
    if (callbacksOpt.isEmpty() || callbacksOpt.get().isEmpty()) {
      return Single.just(Optional.empty());
    }

    Event callbackEvent = modelResponseEvent.toBuilder().build();
    List<BeforeModelCallback> callbacks = callbacksOpt.get();

    return Flowable.fromIterable(callbacks)
        .concatMapSingle(
            callback -> {
              CallbackContext callbackContext =
                  new CallbackContext(context, callbackEvent.actions());
              return callback
                  .call(callbackContext, llmRequest)
                  .map(Optional::of)
                  .defaultIfEmpty(Optional.empty());
            })
        .filter(Optional::isPresent)
        .firstElement()
        .switchIfEmpty(Single.just(Optional.empty()));
  }

  /**
   * Handles registered {@link AfterModelCallback}s after an LLM response is received.
   * This method iterates through all {@link AfterModelCallback}s associated with the agent.
   * If any callback returns a non-empty {@link Optional} {@link LlmResponse},
   * that response replaces the original LLM response. If multiple callbacks return a response,
   * the first one that does so will be used.
   *
   * @param context The {@link InvocationContext} of the current operation.
   * @param llmResponse The {@link LlmResponse} received from the LLM.
   * @param modelResponseEvent An {@link Event} object providing context (like actions) for the
   * callbacks. The content of this event is updated with the {@code llmResponse.content()}
   * before being passed to callbacks.
   * @return A {@link Single} emitting the final {@link LlmResponse} after all callbacks have been processed.
   * This may be the original response or a modified one from a callback.
   */
  private Single<LlmResponse> handleAfterModelCallback(
      InvocationContext context, LlmResponse llmResponse, Event modelResponseEvent) {
    LlmAgent agent = (LlmAgent) context.agent();
    Optional<List<AfterModelCallback>> callbacksOpt = agent.afterModelCallback();

    if (callbacksOpt.isEmpty() || callbacksOpt.get().isEmpty()) {
      return Single.just(llmResponse);
    }

    Event callbackEvent = modelResponseEvent.toBuilder().content(llmResponse.content()).build();
    List<AfterModelCallback> callbacks = callbacksOpt.get();

    return Flowable.fromIterable(callbacks)
        .concatMapSingle(
            callback -> {
              CallbackContext callbackContext =
                  new CallbackContext(context, callbackEvent.actions());
              return callback
                  .call(callbackContext, llmResponse)
                  .map(Optional::of)
                  .defaultIfEmpty(Optional.empty());
            })
        .filter(Optional::isPresent)
        .firstElement()
        .map(Optional::get)
        .switchIfEmpty(Single.just(llmResponse));
  }

  /**
   * Executes a single step of the LLM flow, which typically involves pre-processing,
   * calling the LLM, and then post-processing the LLM's response.
   * This method handles the lifecycle of an LLM call within a flow, including
   * incrementing the LLM call count, handling `LlmCallsLimitExceededException`,
   * generating a unique event ID for the LLM response, and managing agent transfers.
   *
   * @param context The {@link InvocationContext} for the current execution step.
   * @return A {@link Flowable} emitting {@link Event} objects representing the
   * results of the current processing step, including pre-processing events,
   * and post-processing events, potentially leading to events from a transferred agent.
   * @throws LlmCallsLimitExceededException if the maximum number of allowed LLM calls is exceeded
   * during this step.
   * @throws IllegalStateException if a transfer agent is specified but not found.
   */
  private Flowable<Event> runOneStep(InvocationContext context) {
    LlmRequest initialLlmRequest = LlmRequest.builder().build();

    return preprocess(context, initialLlmRequest)
        .flatMapPublisher(
            preResult -> {
              LlmRequest llmRequestAfterPreprocess = preResult.updatedRequest();
              Iterable<Event> preEvents = preResult.events();

              logger.debug("Pre-processing result: {}", preResult);
              if (context.endInvocation()) {
                logger.debug("End invocation requested during preprocessing.");
                return Flowable.fromIterable(preEvents);
              }

              try {
                context.incrementLlmCallsCount();
              } catch (LlmCallsLimitExceededException e) {
                logger.error("LLM calls limit exceeded.", e);
                return Flowable.fromIterable(preEvents).concatWith(Flowable.error(e));
              }

              final Event mutableEventTemplate =
                  Event.builder()
                      .id(Event.generateEventId())
                      .invocationId(context.invocationId())
                      .author(context.agent().name())
                      .branch(context.branch())
                      .build();

              logger.debug("Starting LLM call with request: {}", llmRequestAfterPreprocess);
              Flowable<Event> restOfFlow =
                  callLlm(context, llmRequestAfterPreprocess, mutableEventTemplate)
                      .concatMap(
                          llmResponse -> {
                            logger.debug(
                                "Processing LlmResponse with Event ID: {}",
                                mutableEventTemplate.id());
                            logger.debug("LLM response for current step: {}", llmResponse);

                            Single<ResponseProcessingResult> postResultSingle =
                                postprocess(
                                    context,
                                    mutableEventTemplate,
                                    llmRequestAfterPreprocess,
                                    llmResponse);

                            return postResultSingle
                                .doOnSuccess(
                                    ignored -> {
                                      String oldId = mutableEventTemplate.id();
                                      mutableEventTemplate.setId(Event.generateEventId());
                                      logger.debug(
                                          "Updated mutableEventTemplate ID from {} to {} for next"
                                              + " LlmResponse",
                                          oldId,
                                          mutableEventTemplate.id());
                                    })
                                .toFlowable();
                          })
                      .concatMap(
                          postResult -> {
                            logger.debug("Post-processing result: {}", postResult);
                            Flowable<Event> postProcessedEvents =
                                Flowable.fromIterable(postResult.events());
                            if (postResult.transferToAgent().isPresent()) {
                              String agentToTransfer = postResult.transferToAgent().get();
                              logger.debug("Transferring to agent: {}", agentToTransfer);
                              BaseAgent rootAgent = context.agent().rootAgent();
                              BaseAgent nextAgent = rootAgent.findAgent(agentToTransfer);
                              if (nextAgent == null) {
                                String errorMsg =
                                    "Agent not found for transfer: " + agentToTransfer;
                                logger.error(errorMsg);
                                return postProcessedEvents.concatWith(
                                    Flowable.error(new IllegalStateException(errorMsg)));
                              }
                              return postProcessedEvents.concatWith(
                                  Flowable.defer(() -> nextAgent.runAsync(context)));
                            }
                            return postProcessedEvents;
                          });

              return restOfFlow.startWithIterable(preEvents);
            });
  }

  /**
   * Executes the LLM flow for the current invocation.
   * This method repeatedly calls {@link #runOneStep(InvocationContext)} until
   * the flow produces a final response or the event list is empty.
   * The events from each step are emitted as a {@link Flowable}.
   *
   * @param invocationContext The {@link InvocationContext} for the entire flow execution.
   * @return A {@link Flowable} emitting all {@link Event} objects generated throughout
   * the complete flow execution.
   */
  @Override
  public Flowable<Event> run(InvocationContext invocationContext) {
    Flowable<Event> currentStepEvents = runOneStep(invocationContext).cache();
    return currentStepEvents.concatWith(
        currentStepEvents
            .toList()
            .flatMapPublisher(
                eventList -> {
                  if (eventList.isEmpty() || Iterables.getLast(eventList).finalResponse()) {
                    logger.debug(
                        "Ending flow execution based on final response or empty event list.");
                    return Flowable.empty();
                  } else {
                    logger.debug("Continuing to next step of the flow.");
                    return Flowable.defer(() -> run(invocationContext));
                  }
                }));
  }

  /**
   * Executes the LLM flow in a live (streaming) mode.
   * This method sets up a connection to the LLM, sends the initial history,
   * and then continuously sends and receives data in real-time.
   * It handles pre-processing, sending content/blobs from the live request queue,
   * receiving LLM responses, and post-processing them.
   * It also manages agent transfers within the live flow.
   *
   * @param invocationContext The {@link InvocationContext} for the live flow execution.
   * @return A {@link Flowable} emitting {@link Event} objects as they are generated
   * during the live interaction with the LLM.
   */
  @Override
  public Flowable<Event> runLive(InvocationContext invocationContext) {
    LlmRequest llmRequest = LlmRequest.builder().build();

    return preprocess(invocationContext, llmRequest)
        .flatMapPublisher(
            preResult -> {
              LlmRequest llmRequestAfterPreprocess = preResult.updatedRequest();
              if (invocationContext.endInvocation()) {
                return Flowable.fromIterable(preResult.events());
              }

              String eventIdForSendData = Event.generateEventId();
              LlmAgent agent = (LlmAgent) invocationContext.agent();
              BaseLlm llm =
                  agent.resolvedModel().model().isPresent()
                      ? agent.resolvedModel().model().get()
                      : LlmRegistry.getLlm(agent.resolvedModel().modelName().get());
              BaseLlmConnection connection = llm.connect(llmRequestAfterPreprocess);
              Completable historySent =
                  llmRequestAfterPreprocess.contents().isEmpty()
                      ? Completable.complete()
                      : Completable.defer(
                          () -> {
                            Span sendDataSpan =
                                Telemetry.getTracer().spanBuilder("send_data").startSpan();
                            try (Scope scope = sendDataSpan.makeCurrent()) {
                              return connection
                                  .sendHistory(llmRequestAfterPreprocess.contents())
                                  .doOnComplete(
                                      () -> {
                                        try (Scope innerScope = sendDataSpan.makeCurrent()) {
                                          Telemetry.traceSendData(
                                              invocationContext,
                                              eventIdForSendData,
                                              llmRequestAfterPreprocess.contents());
                                        }
                                      })
                                  .doOnError(
                                      error -> {
                                        sendDataSpan.setStatus(
                                            StatusCode.ERROR, error.getMessage());
                                        sendDataSpan.recordException(error);
                                        try (Scope innerScope = sendDataSpan.makeCurrent()) {
                                          Telemetry.traceSendData(
                                              invocationContext,
                                              eventIdForSendData,
                                              llmRequestAfterPreprocess.contents());
                                        }
                                      })
                                  .doFinally(sendDataSpan::end);
                            }
                          });

              Flowable<LiveRequest> liveRequests = invocationContext.liveRequestQueue().get().get();
              Disposable sendTask =
                  historySent
                      .observeOn(agent.executor().map(Schedulers::from).orElse(Schedulers.io()))
                      .andThen(
                          liveRequests
                              .onBackpressureBuffer()
                              .concatMapCompletable(
                                  request -> {
                                    if (request.content().isPresent()) {
                                      return connection.sendContent(request.content().get());
                                    } else if (request.blob().isPresent()) {
                                      return connection.sendRealtime(request.blob().get());
                                    }
                                    return Completable.fromAction(connection::close);
                                  }))
                      .subscribeWith(
                          new DisposableCompletableObserver() {
                            @Override
                            public void onComplete() {
                              connection.close();
                            }

                            @Override
                            public void onError(Throwable e) {
                              connection.close(e);
                            }
                          });

              Event.Builder liveEventBuilderTemplate =
                  Event.builder()
                      .invocationId(invocationContext.invocationId())
                      .author(invocationContext.agent().name())
                      .branch(invocationContext.branch());

              Flowable<Event> receiveFlow =
                  connection
                      .receive()
                      .flatMapSingle(
                          llmResponse -> {
                            Event baseEventForThisLlmResponse =
                                liveEventBuilderTemplate.id(Event.generateEventId()).build();
                            return postprocess(
                                invocationContext,
                                baseEventForThisLlmResponse,
                                llmRequestAfterPreprocess,
                                llmResponse);
                          })
                      .flatMap(
                          postResult -> {
                            Flowable<Event> events = Flowable.fromIterable(postResult.events());
                            if (postResult.transferToAgent().isPresent()) {
                              BaseAgent rootAgent = invocationContext.agent().rootAgent();
                              BaseAgent nextAgent =
                                  rootAgent.findAgent(postResult.transferToAgent().get());
                              if (nextAgent == null) {
                                throw new IllegalStateException(
                                    "Agent not found: " + postResult.transferToAgent().get());
                              }
                              Flowable<Event> nextAgentEvents =
                                  nextAgent.runLive(invocationContext);
                              events = Flowable.concat(events, nextAgentEvents);
                            }
                            return events;
                          })
                      .doOnNext(
                          event -> {
                            ImmutableList<FunctionResponse> functionResponses =
                                event.functionResponses();
                            if (!functionResponses.isEmpty()) {
                              invocationContext
                                  .liveRequestQueue()
                                  .get()
                                  .content(event.content().get());
                            }
                            if (functionResponses.stream()
                                .anyMatch(
                                    functionResponse ->
                                        functionResponse
                                            .name()
                                            .orElse("")
                                            .equals("transferToAgent"))) {
                              sendTask.dispose();
                              connection.close();
                            }
                          });

              return receiveFlow.startWithIterable(preResult.events());
            });
  }

  /**
   * Builds an {@link Event} object from a base event, LLM request, and LLM response.
   * This method populates the event with content, partial status, error details,
   * and grounding metadata from the LLM response. It also handles the processing
   * of function calls, including populating client-side function call IDs and
   * identifying long-running tool IDs.
   *
   * @param baseEventForLlmResponse The base {@link Event} template to build upon,
   * containing common event properties like invocation ID, author, and branch.
   * @param llmRequest The original {@link LlmRequest} that led to the {@code llmResponse}.
   * @param llmResponse The {@link LlmResponse} received from the LLM.
   * @return A new {@link Event} instance fully populated with data from the LLM response
   * and processed function call information.
   */
  private Event buildModelResponseEvent(
      Event baseEventForLlmResponse, LlmRequest llmRequest, LlmResponse llmResponse) {
    Event.Builder eventBuilder =
        baseEventForLlmResponse.toBuilder()
            .content(llmResponse.content())
            .partial(llmResponse.partial())
            .errorCode(llmResponse.errorCode())
            .errorMessage(llmResponse.errorMessage())
            .interrupted(llmResponse.interrupted())
            .turnComplete(llmResponse.turnComplete())
            .groundingMetadata(llmResponse.groundingMetadata());

    Event event = eventBuilder.build();

    ImmutableList<FunctionCall> functionCalls = event.functionCalls();
    if (!functionCalls.isEmpty()) {
      Functions.populateClientFunctionCallId(event);
      Set<String> longRunningToolIds =
          Functions.getLongRunningFunctionCalls(functionCalls, llmRequest.tools());
      if (!longRunningToolIds.isEmpty()) {
        event.setLongRunningToolIds(Optional.of(longRunningToolIds));
      }
    }
    return event;
  }
}
