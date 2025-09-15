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
import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.flows.BaseFlow;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.flows.llmflows.ResponseProcessor.ResponseProcessingResult;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmCallsLimitExceededException;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
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

  // Warning: This is local, in-process state that won't be preserved if the runtime is restarted.
  // "Max steps" is experimental and may evolve in the future (e.g., to support persistence).
  protected int stepsCompleted = 0;
  protected final int maxSteps;

  public BaseLlmFlow(
      List<RequestProcessor> requestProcessors, List<ResponseProcessor> responseProcessors) {
    this(requestProcessors, responseProcessors, /* maxSteps= */ Optional.empty());
  }

  public BaseLlmFlow(
      List<RequestProcessor> requestProcessors,
      List<ResponseProcessor> responseProcessors,
      Optional<Integer> maxSteps) {
    this.requestProcessors = requestProcessors;
    this.responseProcessors = responseProcessors;
    this.maxSteps = maxSteps.orElse(Integer.MAX_VALUE);
  }

  /**
   * Pre-processes the LLM request before sending it to the LLM. Executes all registered {@link
   * RequestProcessor}.
   */
  protected Single<RequestProcessingResult> preprocess(
      InvocationContext context, LlmRequest llmRequest) {

    List<Iterable<Event>> eventIterables = new ArrayList<>();
    LlmAgent agent = (LlmAgent) context.agent();

    Single<LlmRequest> currentLlmRequest = Single.just(llmRequest);
    for (RequestProcessor processor : requestProcessors) {
      currentLlmRequest =
          currentLlmRequest
              .flatMap(request -> processor.processRequest(context, request))
              .doOnSuccess(
                  result -> {
                    if (result.events() != null) {
                      eventIterables.add(result.events());
                    }
                  })
              .map(RequestProcessingResult::updatedRequest);
    }

    return currentLlmRequest.flatMap(
        processedRequest -> {
          LlmRequest.Builder updatedRequestBuilder = processedRequest.toBuilder();

          return agent
              .canonicalTools(new ReadonlyContext(context))
              .concatMapCompletable(
                  tool ->
                      tool.processLlmRequest(
                          updatedRequestBuilder, ToolContext.builder(context).build()))
              .andThen(
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
    Single<LlmResponse> currentLlmResponse = Single.just(llmResponse);
    for (ResponseProcessor processor : responseProcessors) {
      currentLlmResponse =
          currentLlmResponse
              .flatMap(response -> processor.processResponse(context, response))
              .doOnSuccess(
                  result -> {
                    if (result.events() != null) {
                      eventIterables.add(result.events());
                    }
                  })
              .map(ResponseProcessingResult::updatedResponse);
    }

    return currentLlmResponse.flatMap(
        updatedResponse -> {
          if (updatedResponse.content().isEmpty()
              && updatedResponse.errorCode().isEmpty()
              && !updatedResponse.interrupted().orElse(false)
              && !updatedResponse.turnComplete().orElse(false)) {
            return Single.just(
                ResponseProcessingResult.create(
                    updatedResponse, Iterables.concat(eventIterables), Optional.empty()));
          }

          Event modelResponseEvent =
              buildModelResponseEvent(baseEventForLlmResponse, llmRequest, updatedResponse);
          eventIterables.add(Collections.singleton(modelResponseEvent));

          Maybe<Event> maybeFunctionCallEvent;
          if (modelResponseEvent.functionCalls().isEmpty()) {
            maybeFunctionCallEvent = Maybe.empty();
          } else if (context.runConfig().streamingMode() == StreamingMode.BIDI) {
            maybeFunctionCallEvent =
                Functions.handleFunctionCallsLive(context, modelResponseEvent, llmRequest.tools());
          } else {
            maybeFunctionCallEvent =
                Functions.handleFunctionCalls(context, modelResponseEvent, llmRequest.tools());
          }
          return maybeFunctionCallEvent
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty())
              .map(
                  functionCallEventOpt -> {
                    Optional<String> transferToAgent = Optional.empty();
                    if (functionCallEventOpt.isPresent()) {
                      Event functionCallEvent = functionCallEventOpt.get();
                      eventIterables.add(Collections.singleton(functionCallEvent));
                      transferToAgent = functionCallEvent.actions().transferToAgent();
                    }
                    Iterable<Event> combinedEvents = Iterables.concat(eventIterables);
                    return ResponseProcessingResult.create(
                        updatedResponse, combinedEvents, transferToAgent);
                  });
        });
  }

  /**
   * Sends a request to the LLM and returns its response.
   *
   * @param context The invocation context.
   * @param llmRequest The LLM request.
   * @param eventForCallbackUsage An Event object primarily for providing context (like actions) to
   *     callbacks. Callbacks should not rely on its ID if they create their own separate events.
   */
  private Flowable<LlmResponse> callLlm(
      InvocationContext context, LlmRequest llmRequest, Event eventForCallbackUsage) {
    LlmAgent agent = (LlmAgent) context.agent();

    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();

    return handleBeforeModelCallback(context, llmRequestBuilder, eventForCallbackUsage)
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
                                  llmRequestBuilder.build(),
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
   * Invokes {@link BeforeModelCallback}s. If any returns a response, it's used instead of calling
   * the LLM.
   *
   * @return A {@link Single} with the callback result or {@link Optional#empty()}.
   */
  private Single<Optional<LlmResponse>> handleBeforeModelCallback(
      InvocationContext context, LlmRequest.Builder llmRequestBuilder, Event modelResponseEvent) {
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
                  .call(callbackContext, llmRequestBuilder)
                  .map(Optional::of)
                  .defaultIfEmpty(Optional.empty());
            })
        .filter(Optional::isPresent)
        .firstElement()
        .switchIfEmpty(Single.just(Optional.empty()));
  }

  /**
   * Invokes {@link AfterModelCallback}s after an LLM response. If any returns a response, it
   * replaces the original.
   *
   * @return A {@link Single} with the final {@link LlmResponse}.
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
   * Executes a single iteration of the LLM flow: preprocessing → LLM call → postprocessing.
   *
   * <p>Handles early termination, LLM call limits, and agent transfer if needed.
   *
   * @return A {@link Flowable} of {@link Event} objects from this step.
   * @throws LlmCallsLimitExceededException if the agent exceeds allowed LLM invocations.
   * @throws IllegalStateException if a transfer agent is specified but not found.
   */
  private Flowable<Event> runOneStep(InvocationContext context) {
    LlmRequest initialLlmRequest = LlmRequest.builder().build();

    return preprocess(context, initialLlmRequest)
        .flatMapPublisher(
            preResult -> {
              LlmRequest llmRequestAfterPreprocess = preResult.updatedRequest();
              Iterable<Event> preEvents = preResult.events();

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
              // Explicitly set the event timestamp to 0 so the postprocessing logic would generate
              // events with fresh timestamp.
              mutableEventTemplate.setTimestamp(0L);

              Flowable<Event> restOfFlow =
                  callLlm(context, llmRequestAfterPreprocess, mutableEventTemplate)
                      .concatMap(
                          llmResponse -> {
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
   * Executes the full LLM flow by repeatedly calling {@link #runOneStep} until a final response is
   * produced.
   *
   * @return A {@link Flowable} of all {@link Event}s generated during the flow.
   */
  @Override
  public Flowable<Event> run(InvocationContext invocationContext) {
    Flowable<Event> currentStepEvents = runOneStep(invocationContext).cache();
    if (++stepsCompleted >= maxSteps) {
      logger.debug("Ending flow execution because max steps reached.");
      return currentStepEvents;
    }

    return currentStepEvents.concatWith(
        currentStepEvents
            .toList()
            .flatMapPublisher(
                eventList -> {
                  if (eventList.isEmpty()
                      || Iterables.getLast(eventList).finalResponse()
                      || Iterables.getLast(eventList).actions().endInvocation().orElse(false)) {
                    logger.debug(
                        "Ending flow execution based on final response, endInvocation action or"
                            + " empty event list.");
                    return Flowable.empty();
                  } else {
                    logger.debug("Continuing to next step of the flow.");
                    return Flowable.defer(() -> run(invocationContext));
                  }
                }));
  }

  /**
   * Executes the LLM flow in streaming mode.
   *
   * <p>Handles sending history and live requests to the LLM, receiving responses, processing them,
   * and managing agent transfers.
   *
   * @return A {@link Flowable} of {@link Event}s streamed in real-time.
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

              Flowable<LiveRequest> liveRequests =
                  invocationContext
                      .liveRequestQueue()
                      .get()
                      .get()
                      .doOnNext(
                          request -> {
                            if (!invocationContext.activeStreamingTools().isEmpty()) {
                              for (ActiveStreamingTool activeStreamingTool :
                                  invocationContext.activeStreamingTools().values()) {
                                if (activeStreamingTool.stream() != null) {
                                  activeStreamingTool.stream().send(request);
                                }
                              }
                            }
                          });
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
                                                .equals("transferToAgent"))
                                || event.actions().endInvocation().orElse(false)) {
                              sendTask.dispose();
                              connection.close();
                            }
                          });

              return receiveFlow
                  .takeWhile(event -> !event.actions().endInvocation().orElse(false))
                  .startWithIterable(preResult.events());
            });
  }

  /**
   * Builds an {@link Event} from LLM response, request, and base event data.
   *
   * <p>Populates the event with LLM output and tool function call metadata.
   *
   * @return A fully constructed {@link Event} representing the LLM response.
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

    if (!event.functionCalls().isEmpty()) {
      Functions.populateClientFunctionCallId(event);
      Set<String> longRunningToolIds =
          Functions.getLongRunningFunctionCalls(event.functionCalls(), llmRequest.tools());
      if (!longRunningToolIds.isEmpty()) {
        event.setLongRunningToolIds(Optional.of(longRunningToolIds));
      }
    }
    return event;
  }
}
