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

package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.createEvent;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CallbacksTest {
  @Test
  public void testRun_withBeforeAgentCallback() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    Content beforeAgentContent = Content.fromParts(Part.fromText("before agent content"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallback(context -> Maybe.just(beforeAgentContent))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(beforeAgentContent);
  }

  @Test
  public void testRun_withAfterAgentCallback() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    Content afterAgentContent = Content.fromParts(Part.fromText("after agent content"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .afterAgentCallback(context -> Maybe.just(afterAgentContent))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(2);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(1).content()).hasValue(afterAgentContent);
  }

  @Test
  public void testRun_withBeforeAgentCallback_returnsNothing() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallback(
                callbackContext ->
                    // No state modification, no content returned
                    Maybe.empty())
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    // Verify only one event is returned (model response)
    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).actions().stateDelta()).isEmpty();
    assertThat(finalState).isEmpty();
  }

  @Test
  public void testRun_withBeforeAgentCallback_returnsContent() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    Content beforeAgentContent = Content.fromParts(Part.fromText("before agent content"));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallback(
                callbackContext -> {
                  Object unused = callbackContext.state().put("before_key", "before_value");
                  return Maybe.just(beforeAgentContent);
                })
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    // Verify only one event is returned (content from beforeAgentCallback)
    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(beforeAgentContent);
    assertThat(events.get(0).actions().stateDelta()).containsExactly("before_key", "before_value");
    assertThat(finalState).containsEntry("before_key", "before_value");
  }

  @Test
  public void testRun_withBeforeAgentCallback_modifiesStateOnly() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallback(
                callbackContext -> {
                  Object unused = callbackContext.state().put("before_key", "before_value");
                  // Return empty to signal no immediate content response
                  return Maybe.empty();
                })
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    // Verify two events are returned (state delta + model response)
    assertThat(events).hasSize(2);
    // Verify the first event (state delta)
    assertThat(events.get(0).content().flatMap(Content::parts)).isEmpty(); // No content
    assertThat(events.get(0).actions().stateDelta()).containsExactly("before_key", "before_value");
    // Verify the second event (model response)
    assertThat(events.get(1).content()).hasValue(modelContent);
    assertThat(events.get(1).actions().stateDelta()).isEmpty();
    assertThat(finalState).containsEntry("before_key", "before_value");
  }

  @Test
  public void testRun_agentCallback_modifyStateAndOverrideResponse() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallback(
                callbackContext -> {
                  Object unused = callbackContext.state().put("before_key", "before_value");
                  return Maybe.empty();
                })
            .afterAgentCallback(
                callbackContext -> {
                  Object unused = callbackContext.state().put("after_key", "after_value");
                  return Maybe.just(Content.fromParts(Part.fromText("after agent content")));
                })
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    assertThat(events).hasSize(3);
    assertThat(events.get(0).content().flatMap(Content::parts)).isEmpty();
    assertThat(events.get(0).actions().stateDelta()).containsExactly("before_key", "before_value");
    assertThat(events.get(1).content()).hasValue(modelContent);
    assertThat(events.get(1).actions().stateDelta()).isEmpty();
    assertThat(events.get(2).content().get().parts().get().get(0).text())
        .hasValue("after agent content");
    assertThat(events.get(2).actions().stateDelta()).containsExactly("after_key", "after_value");
    assertThat(finalState).containsEntry("before_key", "before_value");
    assertThat(finalState).containsEntry("after_key", "after_value");
  }

  @Test
  public void testRun_withAsyncCallbacks() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallback(
                callbackContext ->
                    Maybe.<Content>empty().delay(10, MILLISECONDS, Schedulers.computation()))
            .afterAgentCallback(
                callbackContext ->
                    Maybe.just(Content.fromParts(Part.fromText("async after agent content")))
                        .delay(10, MILLISECONDS, Schedulers.computation()))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(2);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).actions().stateDelta()).isEmpty();
    assertThat(events.get(1).content().get().parts().get().get(0).text())
        .hasValue("async after agent content");
    assertThat(events.get(1).actions().stateDelta()).isEmpty();
  }

  @Test
  public void testRun_withSyncCallbacks() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeAgentCallbackSync(callbackContext -> Optional.empty())
            .afterAgentCallbackSync(
                callbackContext ->
                    Optional.of(Content.fromParts(Part.fromText("sync after agent content"))))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(2);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).actions().stateDelta()).isEmpty();
    assertThat(events.get(1).content().get().parts().get().get(0).text())
        .hasValue("sync after agent content");
    assertThat(events.get(1).actions().stateDelta()).isEmpty();
  }

  @Test
  public void testRun_withMultipleBeforeAgentCallbacks_firstReturnsContent() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    Content beforeAgentContent1 = Content.fromParts(Part.fromText("before agent content 1"));
    Content beforeAgentContent2 = Content.fromParts(Part.fromText("before agent content 2"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    Callbacks.BeforeAgentCallbackSync cb1 =
        callbackContext -> {
          var unused = callbackContext.state().put("key1", "value1");
          return Optional.of(beforeAgentContent1);
        };
    Callbacks.BeforeAgentCallback cb2 =
        callbackContext -> {
          var unused = callbackContext.state().put("key2", "value2");
          return Maybe.just(beforeAgentContent2);
        };

    LlmAgent agent =
        createTestAgentBuilder(testLlm).beforeAgentCallback(ImmutableList.of(cb1, cb2)).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    assertThat(events).hasSize(1);
    Event event1 = events.get(0);
    assertThat(event1.content()).hasValue(beforeAgentContent1);
    assertThat(event1.actions().stateDelta()).containsExactly("key1", "value1");

    assertThat(finalState).containsExactly("key1", "value1");
    assertThat(testLlm.getRequests()).isEmpty();
  }

  @Test
  public void testRun_withMultipleBeforeAgentCallbacks_allModifyState_noneReturnContent() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    Callbacks.BeforeAgentCallbackSync cb1 =
        callbackContext -> {
          var unused = callbackContext.state().put("key1", "value1");
          return Optional.empty();
        };
    Callbacks.BeforeAgentCallback cb2 =
        callbackContext -> {
          var unused = callbackContext.state().put("key2", "value2");
          return Maybe.empty();
        };

    LlmAgent agent =
        createTestAgentBuilder(testLlm).beforeAgentCallback(ImmutableList.of(cb1, cb2)).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    assertThat(events).hasSize(2);

    Event event1 = events.get(0);
    assertThat(event1.content().flatMap(Content::parts)).isEmpty();
    assertThat(event1.actions().stateDelta()).containsExactly("key1", "value1", "key2", "value2");

    Event event2 = events.get(1);
    assertThat(event2.content()).hasValue(modelContent);
    assertThat(event2.actions().stateDelta()).isEmpty();

    assertThat(finalState).containsExactly("key1", "value1", "key2", "value2");
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  @Test
  public void testRun_withMultipleAfterAgentCallbacks_firstReturnsContent() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    Content afterAgentContent1 = Content.fromParts(Part.fromText("after agent content 1"));
    Content afterAgentContent2 = Content.fromParts(Part.fromText("after agent content 2"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    Callbacks.AfterAgentCallbackSync cb1 =
        callbackContext -> {
          var unused = callbackContext.state().put("key1", "value1");
          return Optional.of(afterAgentContent1);
        };
    Callbacks.AfterAgentCallback cb2 =
        callbackContext -> {
          var unused = callbackContext.state().put("key2", "value2");
          return Maybe.just(afterAgentContent2);
        };

    LlmAgent agent =
        createTestAgentBuilder(testLlm).afterAgentCallback(ImmutableList.of(cb1, cb2)).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    assertThat(events).hasSize(2);

    Event event1 = events.get(0);
    assertThat(event1.content()).hasValue(modelContent);
    assertThat(event1.actions().stateDelta()).isEmpty();

    Event event2 = events.get(1);
    assertThat(event2.content()).hasValue(afterAgentContent1);
    assertThat(event2.actions().stateDelta()).containsExactly("key1", "value1");

    assertThat(finalState).containsExactly("key1", "value1");
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  @Test
  public void testRun_withMultipleAfterAgentCallbacks_allModifyState_noneReturnContent() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));

    Callbacks.AfterAgentCallbackSync cb1 =
        callbackContext -> {
          var unused = callbackContext.state().put("key1", "value1");
          return Optional.empty();
        };
    Callbacks.AfterAgentCallback cb2 =
        callbackContext -> {
          var unused = callbackContext.state().put("key2", "value2");
          return Maybe.empty();
        };

    LlmAgent agent =
        createTestAgentBuilder(testLlm).afterAgentCallback(ImmutableList.of(cb1, cb2)).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();
    Map<String, Object> finalState = invocationContext.session().state();

    assertThat(events).hasSize(2);

    Event event1 = events.get(0);
    assertThat(event1.content()).hasValue(modelContent);
    assertThat(event1.actions().stateDelta()).isEmpty();

    Event event2 = events.get(1);
    assertThat(event2.content().flatMap(Content::parts)).isEmpty();
    assertThat(event2.actions().stateDelta()).containsExactly("key1", "value1", "key2", "value2");

    assertThat(finalState).containsExactly("key1", "value1", "key2", "value2");
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  @Test
  public void testRun_withBeforeModelCallback_returnsResponseFromCallback() {
    Content realContent = Content.fromParts(Part.fromText("Real LLM response"));
    Content callbackContent = Content.fromParts(Part.fromText("Callback response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(realContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeModelCallback(
                (context, request) ->
                    Maybe.just(LlmResponse.builder().content(callbackContent).build()))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(testLlm.getRequests()).isEmpty();
    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(callbackContent);
  }

  @Test
  public void testRun_withBeforeModelCallback_usesModifiedRequestFromCallback() {
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.builder().build()));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeModelCallback(
                (context, requestBuilder) -> {
                  requestBuilder.contents(
                      ImmutableList.of(Content.fromParts(Part.fromText("Modified request"))));
                  return Maybe.empty();
                })
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> unused = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(testLlm.getRequests()).hasSize(1);
    assertThat(testLlm.getRequests().get(0).contents())
        .containsExactly(Content.fromParts(Part.fromText("Modified request")));
  }

  @Test
  public void testRun_withAfterModelCallback_returnsResponseFromCallback() {
    Part textPartFromModel = Part.fromText("Real LLM response");
    Part textPartFromCallback = Part.fromText("Callback response");
    TestLlm testLlm = createTestLlm(createLlmResponse(Content.fromParts(textPartFromModel)));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .afterModelCallback(
                (context, response) ->
                    Maybe.just(addPartToResponse(response, textPartFromCallback)))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content())
        .hasValue(
            Content.builder()
                .parts(ImmutableList.of(textPartFromModel, textPartFromCallback))
                .build());
  }

  @Test
  public void testRun_withModelCallbacks_receivesCorrectContext() {
    Content realContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(realContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeModelCallback(
                (callbackContext, request) -> {
                  assertThat(callbackContext.invocationId()).isNotEmpty();
                  assertThat(callbackContext.agentName()).isEqualTo("test agent");
                  return Maybe.empty();
                })
            .afterModelCallback(
                (callbackContext, response) -> {
                  assertThat(callbackContext.invocationId()).isNotEmpty();
                  assertThat(callbackContext.agentName()).isEqualTo("test agent");
                  assertThat(response.content()).hasValue(realContent);
                  return Maybe.empty();
                })
            .build();

    InvocationContext invocationContext = createInvocationContext(agent);
    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(realContent);
  }

  @Test
  public void testRun_withChainedModelCallbacks_mixOfSyncAndAsync_returnsBeforeCallbackResponse() {
    Content originalLlmResponseContent = Content.fromParts(Part.fromText("Original LLM response"));

    Content contentFromSecondBeforeCallback =
        Content.fromParts(Part.fromText("Response from second beforeModelCallback"));
    Content contentFromSecondAfterCallback =
        Content.fromParts(Part.fromText("Response from second afterModelCallback"));

    TestLlm testLlm = createTestLlm(createLlmResponse(originalLlmResponseContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeModelCallback(
                ImmutableList.of(
                    (Callbacks.BeforeModelCallbackSync) (context, request) -> Optional.empty(),
                    (Callbacks.BeforeModelCallback)
                        (context, request) ->
                            Maybe.just(
                                LlmResponse.builder()
                                    .content(contentFromSecondBeforeCallback)
                                    .build())))
            .afterModelCallback(
                ImmutableList.of(
                    (Callbacks.AfterModelCallbackSync) (context, response) -> Optional.empty(),
                    (Callbacks.AfterModelCallback)
                        (context, response) ->
                            Maybe.just(
                                LlmResponse.builder()
                                    .content(contentFromSecondAfterCallback)
                                    .build())))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);
    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(testLlm.getRequests()).isEmpty();
    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(contentFromSecondBeforeCallback);
  }

  @Test
  public void testRun_withChainedModelCallbacks_mixOfSyncAndAsync_returnsAfterCallbackResponse() {
    Content originalLlmResponseContent = Content.fromParts(Part.fromText("Original LLM response"));

    Content contentFromSecondAfterCallback =
        Content.fromParts(Part.fromText("Response from second afterModelCallback"));

    TestLlm testLlm = createTestLlm(createLlmResponse(originalLlmResponseContent));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .beforeModelCallback(
                ImmutableList.of(
                    (Callbacks.BeforeModelCallbackSync) (context, request) -> Optional.empty(),
                    (Callbacks.BeforeModelCallback) (context, request) -> Maybe.empty()))
            .afterModelCallback(
                ImmutableList.of(
                    (Callbacks.AfterModelCallbackSync) (context, response) -> Optional.empty(),
                    (Callbacks.AfterModelCallback)
                        (context, response) ->
                            Maybe.just(
                                LlmResponse.builder()
                                    .content(contentFromSecondAfterCallback)
                                    .build())))
            .build();
    InvocationContext invocationContext = createInvocationContext(agent);
    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(testLlm.getRequests()).isNotEmpty();
    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(contentFromSecondAfterCallback);
  }

  private static LlmResponse addPartToResponse(LlmResponse response, Part part) {
    return LlmResponse.builder()
        .content(
            Content.builder()
                .parts(
                    ImmutableList.<Part>builder()
                        .addAll(
                            response.content().flatMap(Content::parts).orElse(ImmutableList.of()))
                        .add(part)
                        .build())
                .build())
        .build();
  }

  @Test
  public void handleFunctionCalls_withBeforeToolCallback_returnsBeforeToolCallbackResult() {
    ImmutableMap<String, Object> beforeToolCallbackResult =
        ImmutableMap.<String, Object>of("before_tool_callback_result", "value");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .beforeToolCallback(
                    (invocationContext1, tool, args, toolContext) ->
                        Maybe.just(beforeToolCallbackResult))
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext,
                event,
                ImmutableMap.of("echo_tool", new TestUtils.FailingEchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(beforeToolCallbackResult)
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withBeforeToolCallbackThatReturnsNull_returnsToolResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .beforeToolCallback((invocationContext1, tool, args, toolContext) -> Maybe.empty())
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", ImmutableMap.of("key", "value")))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withBeforeToolCallbackSync_returnsBeforeToolCallbackResult() {
    ImmutableMap<String, Object> beforeToolCallbackResult =
        ImmutableMap.<String, Object>of("before_tool_callback_result", "value");
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .beforeToolCallbackSync(
                    (invocationContext1, tool, args, toolContext) ->
                        Optional.of(beforeToolCallbackResult))
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext,
                event,
                ImmutableMap.of("echo_tool", new TestUtils.FailingEchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(beforeToolCallbackResult)
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withBeforeToolCallbackSyncThatReturnsNull_returnsToolResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .beforeToolCallbackSync(
                    (invocationContext1, tool, args, toolContext) -> Optional.empty())
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", ImmutableMap.of("key", "value")))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withAfterToolCallback_returnsAfterToolCallbackResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .afterToolCallback(
                    (invocationContext1, tool, args, toolContext, response) ->
                        Maybe.just(
                            ImmutableMap.<String, Object>of(
                                "after_tool_callback_result", response)))
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(
                            ImmutableMap.of(
                                "after_tool_callback_result",
                                ImmutableMap.of("result", ImmutableMap.of("key", "value"))))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withAfterToolCallbackThatReturnsNull_returnsToolResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .afterToolCallback(
                    (invocationContext1, tool, args, toolContext, response) -> Maybe.empty())
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", ImmutableMap.of("key", "value")))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withAfterToolCallbackSync_returnsAfterToolCallbackResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .afterToolCallbackSync(
                    (invocationContext1, tool, args, toolContext, response) ->
                        Optional.of(
                            ImmutableMap.<String, Object>of(
                                "after_tool_callback_result", response)))
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(
                            ImmutableMap.of(
                                "after_tool_callback_result",
                                ImmutableMap.of("result", ImmutableMap.of("key", "value"))))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withAfterToolCallbackSyncThatReturnsNull_returnsToolResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .afterToolCallbackSync(
                    (invocationContext1, tool, args, toolContext, response) -> Optional.empty())
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, event, ImmutableMap.of("echo_tool", new TestUtils.EchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(ImmutableMap.of("result", ImmutableMap.of("key", "value")))
                        .build())
                .build());
  }

  @Test
  public void
      handleFunctionCalls_withBeforeAndAfterToolCallback_returnsAfterToolCallbackResultAppliedToBeforeToolCallbackResult() {
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .beforeToolCallback(
                    (invocationContext1, tool, args, toolContext) ->
                        Maybe.just(
                            ImmutableMap.<String, Object>of(
                                "before_tool_callback_result", "value")))
                .afterToolCallback(
                    (invocationContext1, tool, args, toolContext, response) ->
                        Maybe.just(
                            ImmutableMap.<String, Object>of(
                                "after_tool_callback_result", response)))
                .build());
    Event event =
        createEvent("event").toBuilder()
            .content(
                Content.fromParts(
                    Part.fromText("..."),
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .id("function_call_id")
                                .name("echo_tool")
                                .args(ImmutableMap.of("key", "value"))
                                .build())
                        .build()))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext,
                event,
                ImmutableMap.of("echo_tool", new TestUtils.FailingEchoTool()))
            .blockingGet();

    assertThat(functionResponseEvent).isNotNull();
    assertThat(functionResponseEvent.content().get().parts().get())
        .containsExactly(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("function_call_id")
                        .name("echo_tool")
                        .response(
                            ImmutableMap.of(
                                "after_tool_callback_result",
                                ImmutableMap.of("before_tool_callback_result", "value")))
                        .build())
                .build());
  }

  @Test
  public void handleFunctionCalls_withChainedToolCallbacks_overridesResultAndPassesContext() {
    ImmutableMap<String, Object> originalToolInputArgs =
        ImmutableMap.of("input_key", "input_value");
    ImmutableMap<String, Object> stateAddedByBc2 =
        ImmutableMap.of("bc2_state_key", "bc2_state_value");
    ImmutableMap<String, Object> responseFromAc2 =
        ImmutableMap.of("ac2_response_key", "ac2_response_value");

    Callbacks.BeforeToolCallbackSync bc1 =
        (invCtx, toolName, args, currentToolCtx) -> Optional.empty();

    Callbacks.BeforeToolCallbackSync bc2 =
        (invCtx, toolName, args, currentToolCtx) -> {
          currentToolCtx.build().state().putAll(stateAddedByBc2);
          return Optional.empty();
        };

    TestUtils.EchoTool echoTool = new TestUtils.EchoTool();

    Callbacks.AfterToolCallbackSync ac1 =
        (invCtx, toolName, args, currentToolCtx, responseFromTool) -> Optional.empty();

    Callbacks.AfterToolCallbackSync ac2 =
        (invCtx, toolName, args, currentToolCtx, responseFromTool) -> Optional.of(responseFromAc2);

    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .beforeToolCallback(ImmutableList.of(bc1, bc2))
                .afterToolCallback(ImmutableList.of(ac1, ac2))
                .build());

    Event eventWithFunctionCall =
        createEvent("event").toBuilder()
            .content(createFunctionCallContent("fc_id_minimal", "echo_tool", originalToolInputArgs))
            .build();

    Event functionResponseEvent =
        Functions.handleFunctionCalls(
                invocationContext, eventWithFunctionCall, ImmutableMap.of("echo_tool", echoTool))
            .blockingGet();

    assertThat(getFunctionResponse(functionResponseEvent)).isEqualTo(responseFromAc2);
    assertThat(invocationContext.session().state()).containsExactlyEntriesIn(stateAddedByBc2);
  }

  @Test
  public void agentRunAsync_withToolCallbacks_inspectsArgsAndReturnsResponse() {
    TestUtils.EchoTool echoTool = new TestUtils.EchoTool();
    String toolName = echoTool.declaration().get().name().get();
    ImmutableMap<String, Object> functionArgs = ImmutableMap.of("message", "hello");

    Content llmFunctionCallContent =
        Content.builder()
            .role("model")
            .parts(ImmutableList.of(Part.fromFunctionCall(toolName, functionArgs)))
            .build();
    Content llmTextContent =
        Content.builder().role("model").parts(ImmutableList.of(Part.fromText("hi there"))).build();
    TestLlm testLlm =
        createTestLlm(createLlmResponse(llmFunctionCallContent), createLlmResponse(llmTextContent));

    ImmutableMap<String, Object> responseFromAfterToolCallback =
        ImmutableMap.of("final_wrapper", "wrapped_value_from_after_callback");

    Callbacks.BeforeToolCallback beforeToolCb =
        (invCtx, tName, args, toolCtx) -> {
          assertThat(args).isEqualTo(functionArgs);
          return Maybe.empty();
        };

    Callbacks.AfterToolCallback afterToolCb =
        (invCtx, tName, args, toolCtx, toolResponse) -> {
          assertThat(args).isEqualTo(functionArgs);
          assertThat(toolResponse).isEqualTo(ImmutableMap.of("result", functionArgs));
          return Maybe.just(responseFromAfterToolCallback);
        };

    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .tools(ImmutableList.of(echoTool))
            .beforeToolCallback(beforeToolCb)
            .afterToolCallback(afterToolCb)
            .build();

    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(events).hasSize(3);

    var functionCall = getFunctionCall(events.get(0));
    assertThat(functionCall.args().get()).isEqualTo(functionArgs);
    assertThat(functionCall.name()).hasValue(toolName);

    var functionResponse = getFunctionResponse(events.get(1));
    assertThat(functionResponse).isEqualTo(responseFromAfterToolCallback);

    assertThat(events.get(2).content()).hasValue(llmTextContent);
  }

  private static Content createFunctionCallContent(
      String functionCallId, String toolName, Map<String, Object> args) {
    return Content.builder()
        .role("model")
        .parts(
            ImmutableList.of(
                Part.builder()
                    .functionCall(
                        FunctionCall.builder().name(toolName).id(functionCallId).args(args).build())
                    .build()))
        .build();
  }

  private static Map<String, Object> getFunctionResponse(Event functionResponseEvent) {
    return functionResponseEvent
        .content()
        .get()
        .parts()
        .get()
        .get(0)
        .functionResponse()
        .get()
        .response()
        .get();
  }

  private static FunctionCall getFunctionCall(Event functionCallEvent) {
    return functionCallEvent.content().get().parts().get().get(0).functionCall().get();
  }
}
