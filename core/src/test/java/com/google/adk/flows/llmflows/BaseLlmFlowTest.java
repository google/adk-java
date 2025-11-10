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
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.flows.llmflows.ResponseProcessor.ResponseProcessingResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Single.just(
            ResponseProcessingResult.create(
                response, ImmutableList.of(), /* transferToAgent= */ Optional.empty()));
  }

  private static ResponseProcessor createResponseProcessor(
      Function<LlmResponse, LlmResponse> responseUpdater) {
    return (context, response) ->
        Single.just(
            ResponseProcessingResult.create(
                responseUpdater.apply(response),
                ImmutableList.of(),
                /* transferToAgent= */ Optional.empty()));
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
}
