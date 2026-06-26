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

import static com.google.adk.testing.TestUtils.assertEqualIgnoringFunctionIds;
import static com.google.adk.testing.TestUtils.createGenerateContentResponseUsageMetadata;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.flows.llmflows.ResponseProcessor.ResponseProcessingResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BaseLlmFlow}. */
@RunWith(JUnit4.class)
public final class BaseLlmFlowTest {

  @Test
  public void run_singleTextResponse_returnsSingleEvent() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    Event event = getOnlyElement(events);
    assertThat(event.content()).hasValue(content);
    assertThat(event.avgLogprobs()).isEmpty();
    assertThat(event.finishReason()).isEmpty();
    assertThat(event.usageMetadata()).isEmpty();
  }

  @Test
  public void run_singleTextResponse_withMetadata_returnsSingleEventWithMetadata() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    LlmResponse llmResponse =
        LlmResponse.builder()
            .content(content)
            .avgLogprobs(-0.123)
            .finishReason(new FinishReason(FinishReason.Known.STOP))
            .usageMetadata(
                GenerateContentResponseUsageMetadata.builder()
                    .promptTokenCount(10)
                    .candidatesTokenCount(20)
                    .build())
            .build();
    TestLlm testLlm = createTestLlm(llmResponse);
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    Event event = getOnlyElement(events);
    assertThat(event.content()).hasValue(content);
    assertThat(event.avgLogprobs()).hasValue(-0.123);
    assertThat(event.finishReason()).hasValue(new FinishReason(FinishReason.Known.STOP));
    assertThat(event.usageMetadata())
        .hasValue(
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(10)
                .candidatesTokenCount(20)
                .build());
  }

  @Test
  public void run_withFunctionCall_returnsCorrectEvents() {
    Content firstContent =
        Content.fromParts(
            Part.fromText("LLM response with function call"),
            Part.fromFunctionCall("my_function", ImmutableMap.of("arg1", "value1")));
    Content secondContent =
        Content.fromParts(Part.fromText("LLM response after function response"));
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(firstContent)),
            Flowable.just(createLlmResponse(secondContent)));
    ImmutableMap<String, Object> testResponse =
        ImmutableMap.<String, Object>of("response", "response for my_function");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .tools(ImmutableList.of(new TestTool("my_function", testResponse)))
                .build());
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertEqualIgnoringFunctionIds(events.get(0).content().get(), firstContent);
    assertEqualIgnoringFunctionIds(
        events.get(1).content().get(),
        Content.fromParts(Part.fromFunctionResponse("my_function", testResponse)));
    assertThat(events.get(2).content()).hasValue(secondContent);
  }

  @Test
  public void run_withFunctionCallsAndMaxSteps_stopsAfterMaxSteps() {
    Content contentWithFunctionCall =
        Content.fromParts(
            Part.fromText("LLM response with function call"),
            Part.fromFunctionCall("my_function", ImmutableMap.of("arg1", "value1")));
    Content unreachableContent = Content.fromParts(Part.fromText("This should never be returned."));
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(contentWithFunctionCall)),
            Flowable.just(createLlmResponse(contentWithFunctionCall)),
            Flowable.just(createLlmResponse(unreachableContent)));
    ImmutableMap<String, Object> testResponse =
        ImmutableMap.<String, Object>of("response", "response for my_function");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .tools(ImmutableList.of(new TestTool("my_function", testResponse)))
                .build());
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            /* requestProcessors= */ ImmutableList.of(),
            /* responseProcessors= */ ImmutableList.of(),
            /* maxSteps= */ Optional.of(2));

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(4);
    assertEqualIgnoringFunctionIds(events.get(0).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(
        events.get(1).content().get(),
        Content.fromParts(Part.fromFunctionResponse("my_function", testResponse)));
    assertEqualIgnoringFunctionIds(events.get(2).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(
        events.get(3).content().get(),
        Content.fromParts(Part.fromFunctionResponse("my_function", testResponse)));
  }

  @Test
  public void run_withLongRunningFunctionCall_returnsCorrectEventsWithLongRunningToolIds() {
    Content firstContent =
        Content.fromParts(
            Part.fromText("LLM response with function call"),
            Part.fromFunctionCall("my_function", ImmutableMap.of("arg1", "value1")));
    Content secondContent =
        Content.fromParts(Part.fromText("LLM response after function response"));
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(firstContent)),
            Flowable.just(createLlmResponse(secondContent)));
    ImmutableMap<String, Object> testResponse =
        ImmutableMap.<String, Object>of("response", "response for my_function");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .tools(ImmutableList.of(new TestLongRunningTool("my_function", testResponse)))
                .build());
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertEqualIgnoringFunctionIds(events.get(0).content().get(), firstContent);
    assertThat(events.get(0).longRunningToolIds().get())
        .contains(events.get(0).functionCalls().get(0).id().get());
    assertEqualIgnoringFunctionIds(
        events.get(1).content().get(),
        Content.fromParts(Part.fromFunctionResponse("my_function", testResponse)));
    assertThat(events.get(2).content()).hasValue(secondContent);
  }

  @Test
  public void run_withPartialFunctionCall_doesNotExecuteTool() {
    Content partialContent =
        Content.fromParts(Part.fromFunctionCall("my_function", ImmutableMap.of("arg1", "value1")));
    LlmResponse partialResponse =
        LlmResponse.builder().content(partialContent).partial(true).build();
    TestLlm testLlm = createTestLlm(partialResponse);
    ImmutableMap<String, Object> testResponse =
        ImmutableMap.<String, Object>of("response", "response for my_function");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .tools(ImmutableList.of(new TestTool("my_function", testResponse)))
                .build());
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            /* requestProcessors= */ ImmutableList.of(),
            /* responseProcessors= */ ImmutableList.of(),
            /* maxSteps= */ Optional.of(1));

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).partial()).hasValue(true);
    assertThat(events.get(0).functionCalls()).hasSize(1);
  }

  // End-to-end: when the Gemini aggregator emits a partial event and a final aggregated event for
  // the same function call, both must share the same function-call ID so consumers can correlate
  // them. Mirrors ADK Python's progressive SSE contract. Simulates the post-aggregator stream (FC
  // ID pre-populated, same Part reused across both events).
  @Test
  public void run_streamingFunctionCallWithPrePopulatedId_partialAndFinalShareFunctionCallId() {
    // The aggregator (Gemini.processRawResponses) pre-populates the function call ID. Both the
    // partial event and the final aggregated event reference the same Part with the same ID.
    Part fcPartWithId =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .id("adk-fixed-id-for-test")
                    .name("my_function")
                    .args(ImmutableMap.of("arg1", "value1"))
                    .build())
            .build();
    Content fcContent = Content.builder().role("model").parts(fcPartWithId).build();
    LlmResponse partialResponse = LlmResponse.builder().content(fcContent).partial(true).build();
    LlmResponse aggregatedResponse =
        LlmResponse.builder().content(fcContent).partial(false).build();
    Content secondContent =
        Content.fromParts(Part.fromText("LLM response after function response"));
    TestLlm testLlm =
        createTestLlm(
            // First LLM call: SSE-style stream with partial + aggregated FC events.
            Flowable.just(partialResponse, aggregatedResponse),
            // Second LLM call: final text response after the tool executes.
            Flowable.just(createLlmResponse(secondContent)));
    ImmutableMap<String, Object> testResponse =
        ImmutableMap.<String, Object>of("response", "response for my_function");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .tools(ImmutableList.of(new TestTool("my_function", testResponse)))
                .build());
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    Event partialFcEvent = null;
    Event aggregatedFcEvent = null;
    int totalFunctionResponses = 0;
    for (Event e : events) {
      if (!e.functionCalls().isEmpty()) {
        if (e.partial().orElse(false)) {
          partialFcEvent = e;
        } else {
          aggregatedFcEvent = e;
        }
      }
      totalFunctionResponses += e.functionResponses().size();
    }

    // Tool executes exactly once (only the non-partial event triggers execution).
    assertThat(totalFunctionResponses).isEqualTo(1);

    // Both events carry the function call (this matches ADK Python's progressive SSE behavior).
    assertThat(partialFcEvent).isNotNull();
    assertThat(aggregatedFcEvent).isNotNull();
    assertThat(partialFcEvent.functionCalls()).hasSize(1);
    assertThat(aggregatedFcEvent.functionCalls()).hasSize(1);

    // The FC IDs must match so consumers can correlate/dedupe.
    String partialId = partialFcEvent.functionCalls().get(0).id().orElseThrow();
    String aggregatedId = aggregatedFcEvent.functionCalls().get(0).id().orElseThrow();
    assertThat(partialId).isEqualTo(aggregatedId);
    assertThat(partialId).isEqualTo("adk-fixed-id-for-test");
  }

  @Test
  public void run_withRequestProcessor_doesNotModifyRequest() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    RequestProcessor requestProcessor = createRequestProcessor();
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            ImmutableList.of(requestProcessor), /* responseProcessors= */ ImmutableList.of());

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(content);
  }

  @Test
  public void run_withRequestProcessor_modifiesRequest() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    RequestProcessor requestProcessor =
        createRequestProcessor(
            request ->
                request.toBuilder()
                    .appendInstructions(ImmutableList.of("instruction from request processor"))
                    .build());
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            ImmutableList.of(requestProcessor), /* responseProcessors= */ ImmutableList.of());

    List<Event> unused = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(testLlm.getLastRequest().config().orElseThrow().systemInstruction().orElseThrow())
        .isEqualTo(Content.fromParts(Part.fromText("instruction from request processor")));
  }

  @Test
  public void run_withResponseProcessor_doesNotModifyResponse() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    ResponseProcessor responseProcessor = createResponseProcessor();
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            /* requestProcessors= */ ImmutableList.of(), ImmutableList.of(responseProcessor));

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(content);
  }

  @Test
  public void run_withResponseProcessor_modifiesResponse() {
    Content originalContent = Content.fromParts(Part.fromText("Original LLM response"));
    Content newContent = Content.fromParts(Part.fromText("Modified response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(originalContent));
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    ResponseProcessor responseProcessor =
        createResponseProcessor(response -> LlmResponse.builder().content(newContent).build());
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            /* requestProcessors= */ ImmutableList.of(), ImmutableList.of(responseProcessor));

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(newContent);
  }

  @Test
  public void run_withTools_toolsAreAddedToRequest() {
    Content firstContent =
        Content.fromParts(
            Part.fromText("LLM response with function call"),
            Part.fromFunctionCall("my_function", ImmutableMap.of("arg1", "value1")));
    Content secondContent =
        Content.fromParts(Part.fromText("LLM response after function response"));
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(firstContent)),
            Flowable.just(createLlmResponse(secondContent)));
    TestTool testTool = new TestTool("my_function", ImmutableMap.<String, Object>of());
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm).tools(ImmutableList.of(testTool)).build());
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> unused = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(testLlm.getLastRequest().tools()).containsEntry("my_function", testTool);
  }

  @Test
  public void run_withRequestProcessorsAndTools_modifiesRequestInOrder() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .tools(ImmutableList.of(new TestTool("my_function", ImmutableMap.of())))
                .build());
    RequestProcessor requestProcessor1 =
        createRequestProcessor(
            request ->
                request.toBuilder().appendInstructions(ImmutableList.of("instruction1")).build());
    RequestProcessor requestProcessor2 =
        createRequestProcessor(
            request ->
                request.toBuilder().appendInstructions(ImmutableList.of("instruction2")).build());
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            ImmutableList.of(requestProcessor1, requestProcessor2),
            /* responseProcessors= */ ImmutableList.of());

    List<Event> unused = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(testLlm.getLastRequest().tools()).containsKey("my_function");
    assertThat(testLlm.getLastRequest().config().orElseThrow().systemInstruction().orElseThrow())
        .isEqualTo(Content.fromParts(Part.fromText("instruction1\n\ninstruction2")));
  }

  @Test
  public void run_requestProcessorsEmitEventsDirectly() {
    Event eventFromProcessor1 =
        Event.builder()
            .id("event1")
            .invocationId("invId")
            .author("user")
            .content(Content.fromParts(Part.fromText("event1")))
            .build();
    RequestProcessor processor1 =
        (unusedCtx, request) ->
            Single.just(
                RequestProcessingResult.create(request, ImmutableList.of(eventFromProcessor1)));
    RequestProcessor processor2 =
        (context, request) -> {
          boolean sawEvent1 =
              context.session().events().stream()
                  .anyMatch(e -> e.id().equals(eventFromProcessor1.id()));

          Event resultEvent =
              Event.builder()
                  .id("event2")
                  .invocationId("invId")
                  .author("user")
                  .content(
                      Content.fromParts(
                          Part.fromText(sawEvent1 ? "event1 was seen" : "event1 was not seen")))
                  .build();

          return Single.just(
              RequestProcessingResult.create(request, ImmutableList.of(resultEvent)));
        };
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            ImmutableList.of(processor1, processor2), /* responseProcessors= */ ImmutableList.of());
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgent(
                createTestLlm(
                    createLlmResponse(Content.fromParts(Part.fromText("llm response"))))));

    List<Event> events =
        baseLlmFlow
            .run(invocationContext)
            .doOnNext(event -> invocationContext.session().events().add(event))
            .toList()
            .blockingGet();

    assertThat(events.stream().map(Event::stringifyContent))
        .containsExactly("event1", "event1 was seen", "llm response")
        .inOrder();
  }

  @Test
  public void run_requestProcessorsAreCalledExactlyOnce() {
    AtomicInteger processor1CallCount = new AtomicInteger();
    AtomicInteger processor2CallCount = new AtomicInteger();

    RequestProcessor processor1 =
        (unusedCtx, request) -> {
          processor1CallCount.incrementAndGet();
          return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
        };
    RequestProcessor processor2 =
        (unusedCtx, request) -> {
          processor2CallCount.incrementAndGet();
          return Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
        };
    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(
            ImmutableList.of(processor1, processor2), /* responseProcessors= */ ImmutableList.of());
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgent(
                createTestLlm(
                    createLlmResponse(Content.fromParts(Part.fromText("llm response"))))));

    List<Event> unused = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(processor1CallCount.get()).isEqualTo(1);
    assertThat(processor2CallCount.get()).isEqualTo(1);
  }

  @Test
  public void run_sharingcallbackContextDataBetweenCallbacks() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));

    Callbacks.BeforeModelCallback beforeCallback =
        (ctx, req) -> {
          ctx.invocationContext().callbackContextData().put("key", "value_from_before");
          return Maybe.empty();
        };

    Callbacks.AfterModelCallback afterCallback =
        (ctx, resp) -> {
          String value = (String) ctx.invocationContext().callbackContextData().get("key");
          LlmResponse modifiedResp =
              resp.toBuilder().content(Content.fromParts(Part.fromText("Saw: " + value))).build();
          return Maybe.just(modifiedResp);
        };

    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .beforeModelCallback(beforeCallback)
                .afterModelCallback(afterCallback)
                .build());

    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).stringifyContent()).isEqualTo("Saw: value_from_before");
  }

  @Test
  public void run_sharingcallbackContextDataAcrossContextCopies() {
    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));

    Callbacks.BeforeModelCallback beforeCallback =
        (ctx, req) -> {
          ctx.invocationContext().callbackContextData().put("key", "value_from_before");
          return Maybe.empty();
        };

    Callbacks.AfterModelCallback afterCallback =
        (ctx, resp) -> {
          String value = (String) ctx.invocationContext().callbackContextData().get("key");
          LlmResponse modifiedResp =
              resp.toBuilder().content(Content.fromParts(Part.fromText("Saw: " + value))).build();
          return Maybe.just(modifiedResp);
        };

    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .beforeModelCallback(beforeCallback)
                .afterModelCallback(afterCallback)
                .build());

    BaseLlmFlow baseLlmFlow =
        new BaseLlmFlow(ImmutableList.of(), ImmutableList.of()) {
          @Override
          public Flowable<Event> run(InvocationContext context) {
            // Force a context copy
            InvocationContext copiedContext = context.toBuilder().build();
            return super.run(copiedContext);
          }
        };

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).stringifyContent()).isEqualTo("Saw: value_from_before");
  }

  private static BaseLlmFlow createBaseLlmFlowWithoutProcessors() {
    return createBaseLlmFlow(ImmutableList.of(), ImmutableList.of());
  }

  private static BaseLlmFlow createBaseLlmFlow(
      List<RequestProcessor> requestProcessors, List<ResponseProcessor> responseProcessors) {
    return createBaseLlmFlow(
        requestProcessors, responseProcessors, /* maxSteps= */ Optional.empty());
  }

  private static BaseLlmFlow createBaseLlmFlow(
      List<RequestProcessor> requestProcessors,
      List<ResponseProcessor> responseProcessors,
      Optional<Integer> maxSteps) {
    return new BaseLlmFlow(requestProcessors, responseProcessors, maxSteps) {};
  }

  private static RequestProcessor createRequestProcessor() {
    return (context, request) ->
        Single.just(RequestProcessingResult.create(request, ImmutableList.of()));
  }

  private static RequestProcessor createRequestProcessor(
      Function<LlmRequest, LlmRequest> requestUpdater) {
    return (context, request) ->
        Single.just(
            RequestProcessingResult.create(requestUpdater.apply(request), ImmutableList.of()));
  }

  private static ResponseProcessor createResponseProcessor() {
    return (context, response) ->
        Single.just(ResponseProcessingResult.create(response, ImmutableList.of()));
  }

  private static ResponseProcessor createResponseProcessor(
      Function<LlmResponse, LlmResponse> responseUpdater) {
    return (context, response) ->
        Single.just(
            ResponseProcessingResult.create(responseUpdater.apply(response), ImmutableList.of()));
  }

  private static class TestTool extends BaseTool {
    private final Map<String, Object> response;

    TestTool(String name, Map<String, Object> response) {
      super(name, "tool description for " + name);
      this.response = response;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.just(response);
    }
  }

  private static class TestLongRunningTool extends BaseTool {
    private final Map<String, Object> response;

    TestLongRunningTool(String name, Map<String, Object> response) {
      super(name, "tool description for " + name, /* isLongRunning= */ true);
      this.response = response;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.just(response);
    }
  }

  @Test
  public void run_contextPropagation() {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.current().with(testKey, "test-value");

    Content content = Content.fromParts(Part.fromText("LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(content));

    RequestProcessor requestProcessor =
        (ctx, request) -> {
          return Single.just(RequestProcessingResult.create(request, ImmutableList.of()))
              .subscribeOn(Schedulers.computation());
        };

    ResponseProcessor responseProcessor =
        (ctx, response) -> {
          return Single.just(ResponseProcessingResult.create(response, ImmutableList.of()))
              .subscribeOn(Schedulers.computation());
        };

    Callbacks.BeforeModelCallback beforeCallback =
        (ctx, req) -> {
          return Maybe.<LlmResponse>empty().subscribeOn(Schedulers.computation());
        };

    Callbacks.AfterModelCallback afterCallback =
        (ctx, resp) -> {
          return Maybe.just(resp).subscribeOn(Schedulers.computation());
        };

    Callbacks.OnModelErrorCallback onErrorCallback =
        (ctx, req, err) -> {
          return Maybe.just(
                  LlmResponse.builder().content(Content.fromParts(Part.fromText("error"))).build())
              .subscribeOn(Schedulers.computation());
        };

    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm)
                .beforeModelCallback(beforeCallback)
                .afterModelCallback(afterCallback)
                .onModelErrorCallback(onErrorCallback)
                .build());

    BaseLlmFlow baseLlmFlow =
        createBaseLlmFlow(ImmutableList.of(requestProcessor), ImmutableList.of(responseProcessor));

    List<Event> events;
    try (Scope scope = testContext.makeCurrent()) {
      events =
          baseLlmFlow
              .run(invocationContext)
              .doOnNext(
                  event -> {
                    assertThat(Context.current().get(testKey)).isEqualTo("test-value");
                  })
              .toList()
              .blockingGet();
    }

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(content);
  }

  @Test
  public void postprocess_onlyInputTranscription_returnsEvent() {
    Transcription inputTranscription =
        Transcription.builder().text("user said hello").finished(true).build();
    LlmResponse llmResponse = LlmResponse.builder().inputTranscription(inputTranscription).build();
    InvocationContext invocationContext =
        createInvocationContext(createTestAgent(createTestLlm(llmResponse)));
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();
    Event baseEvent =
        Event.builder()
            .invocationId(invocationContext.invocationId())
            .author(invocationContext.agent().name())
            .build();

    List<Event> events =
        baseLlmFlow
            .postprocess(
                invocationContext,
                baseEvent,
                LlmRequest.builder().build(),
                llmResponse,
                Context.current())
            .toList()
            .blockingGet();

    assertThat(events).hasSize(1);
    Event event = getOnlyElement(events);
    assertThat(event.inputTranscription()).hasValue(inputTranscription);
    assertThat(event.outputTranscription()).isEmpty();
  }

  @Test
  public void postprocess_onlyOutputTranscription_returnsEvent() {
    Transcription outputTranscription =
        Transcription.builder().text("model replied hi").finished(false).build();
    LlmResponse llmResponse =
        LlmResponse.builder().outputTranscription(outputTranscription).build();
    InvocationContext invocationContext =
        createInvocationContext(createTestAgent(createTestLlm(llmResponse)));
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();
    Event baseEvent =
        Event.builder()
            .invocationId(invocationContext.invocationId())
            .author(invocationContext.agent().name())
            .build();

    List<Event> events =
        baseLlmFlow
            .postprocess(
                invocationContext,
                baseEvent,
                LlmRequest.builder().build(),
                llmResponse,
                Context.current())
            .toList()
            .blockingGet();

    assertThat(events).hasSize(1);
    Event event = getOnlyElement(events);
    assertThat(event.outputTranscription()).hasValue(outputTranscription);
    assertThat(event.inputTranscription()).isEmpty();
  }

  @Test
  public void run_responseWithTranscriptions_propagatesTranscriptionsToEvent() {
    Transcription inputTranscription =
        Transcription.builder().text("user said hello").finished(true).build();
    Transcription outputTranscription =
        Transcription.builder().text("model replied hi").finished(true).build();
    Content content = Content.fromParts(Part.fromText("model replied hi"));
    LlmResponse llmResponse =
        LlmResponse.builder()
            .content(content)
            .inputTranscription(inputTranscription)
            .outputTranscription(outputTranscription)
            .build();
    TestLlm testLlm = createTestLlm(llmResponse);
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();

    List<Event> events = baseLlmFlow.run(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    Event event = getOnlyElement(events);
    assertThat(event.inputTranscription()).hasValue(inputTranscription);
    assertThat(event.outputTranscription()).hasValue(outputTranscription);
  }

  @Test
  public void postprocess_noResponseProcessors_onlyUsageMetadata_returnsEvent() {
    GenerateContentResponseUsageMetadata usageMetadata =
        createGenerateContentResponseUsageMetadata().build();
    LlmResponse llmResponse = LlmResponse.builder().usageMetadata(usageMetadata).build();
    InvocationContext invocationContext =
        createInvocationContext(createTestAgent(createTestLlm(llmResponse)));
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();
    Event baseEvent =
        Event.builder()
            .invocationId(invocationContext.invocationId())
            .author(invocationContext.agent().name())
            .build();

    List<Event> events =
        baseLlmFlow
            .postprocess(
                invocationContext,
                baseEvent,
                LlmRequest.builder().build(),
                llmResponse,
                Context.current())
            .toList()
            .blockingGet();

    assertThat(events).hasSize(1);
    Event event = getOnlyElement(events);
    assertThat(event.content()).isEmpty();
    assertThat(event.usageMetadata()).hasValue(usageMetadata);
    assertThat(event.author()).isEqualTo(invocationContext.agent().name());
    assertThat(event.invocationId()).isEqualTo(invocationContext.invocationId());
  }

  @Test
  public void getRequestProcessorFromTools_sequentiallyAppliesToolProcessors() {
    BaseTool tool1 =
        new BaseTool("tool1", "test tool 1") {
          @Override
          public Completable processLlmRequest(
              LlmRequest.Builder builder, ToolContext toolContext) {
            return Completable.fromAction(
                () -> builder.appendInstructions(ImmutableList.of("instruction1")));
          }
        };
    BaseTool tool2 =
        new BaseTool("tool2", "test tool 2") {
          @Override
          public Completable processLlmRequest(
              LlmRequest.Builder builder, ToolContext toolContext) {
            return Completable.fromAction(
                () -> builder.appendInstructions(ImmutableList.of("instruction2")));
          }
        };

    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .tools(tool1, tool2)
            .build();

    InvocationContext invocationContext = createInvocationContext(agent);
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();
    RequestProcessor requestProcessor = baseLlmFlow.getRequestProcessorFromTools(agent);

    LlmRequest processedRequest =
        requestProcessor
            .processRequest(invocationContext, LlmRequest.builder().build())
            .map(RequestProcessingResult::updatedRequest)
            .blockingGet();

    assertThat(processedRequest.getSystemInstructions())
        .containsExactly("instruction1\n\ninstruction2");
  }

  @Test
  public void getRequestProcessorFromTools_appliesToolsetAndItsToolsProcessors() {
    BaseTool tool1 =
        new BaseTool("tool1", "test tool 1") {
          @Override
          public Completable processLlmRequest(
              LlmRequest.Builder builder, ToolContext toolContext) {
            return Completable.fromAction(
                () -> builder.appendInstructions(ImmutableList.of("tool-instruction")));
          }
        };

    BaseToolset toolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
            return Flowable.just(tool1);
          }

          @Override
          public Completable processLlmRequest(
              LlmRequest.Builder builder, ToolContext toolContext) {
            return Completable.fromAction(
                () -> builder.appendInstructions(ImmutableList.of("toolset-instruction")));
          }

          @Override
          public void close() {}
        };

    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build())).tools(toolset).build();

    InvocationContext invocationContext = createInvocationContext(agent);
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();
    RequestProcessor requestProcessor = baseLlmFlow.getRequestProcessorFromTools(agent);

    LlmRequest processedRequest =
        requestProcessor
            .processRequest(invocationContext, LlmRequest.builder().build())
            .map(RequestProcessingResult::updatedRequest)
            .blockingGet();

    assertThat(processedRequest.getSystemInstructions())
        .containsExactly("toolset-instruction\n\ntool-instruction");
  }

  @Test
  public void getRequestProcessorFromTools_throwsOnUnsupportedType() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .tools("unsupported-tool-type-string")
            .build();

    InvocationContext invocationContext = createInvocationContext(agent);
    BaseLlmFlow baseLlmFlow = createBaseLlmFlowWithoutProcessors();
    RequestProcessor requestProcessor = baseLlmFlow.getRequestProcessorFromTools(agent);

    LlmRequest request = LlmRequest.builder().build();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> requestProcessor.processRequest(invocationContext, request));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Object in tools list is not of a supported type: java.lang.String");
  }
}
