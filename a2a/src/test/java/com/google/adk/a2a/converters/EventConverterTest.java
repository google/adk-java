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

package com.google.adk.a2a.converters;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.spec.DataPart;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventConverterTest {
  // Snake case, as ADK Python serializes it; RemoteA2AAgentTest covers camel case.
  private static final ImmutableMap<String, Object> AUTH_CONFIG =
      ImmutableMap.of("auth_scheme", "oauth2", "raw_auth_credential", "secret");

  @Test
  public void testTaskId() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    CustomMetadata.builder().key("adk_task_id").stringValue("task-123").build()))
            .build();
    assertThat(EventConverter.taskId(e)).isEqualTo("task-123");
  }

  @Test
  public void taskIdAndContextId_readA2aKeysBeforeLegacyKeys() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    metadata("a2a:task_id", "task-1"),
                    metadata("a2a:context_id", "context-1"),
                    metadata("adk_task_id", "legacy-task"),
                    metadata("adk_context_id", "legacy-context")))
            .build();

    assertThat(EventConverter.taskId(e)).isEqualTo("task-1");
    assertThat(EventConverter.contextId(e)).isEqualTo("context-1");
  }

  @Test
  public void taskId_eventWithA2aContextIdOnly_ignoresLegacyTaskId() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    metadata("a2a:context_id", "context-1"),
                    metadata("adk_task_id", "legacy-task")))
            .build();

    assertThat(EventConverter.taskId(e)).isEmpty();
  }

  @Test
  public void contextId_eventWithA2aTaskIdOnly_ignoresLegacyContextId() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    metadata("a2a:task_id", "task-1"),
                    metadata("adk_context_id", "legacy-context")))
            .build();

    assertThat(EventConverter.contextId(e)).isEmpty();
  }

  @Test
  public void contextId_eventWithBlankA2aTaskId_ignoresLegacyContextId() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    metadata("a2a:task_id", ""), metadata("adk_context_id", "legacy-context")))
            .build();

    assertThat(EventConverter.contextId(e)).isEmpty();
  }

  @Test
  public void testTaskId_empty() {
    Event e = Event.builder().build();
    assertThat(EventConverter.taskId(e)).isEmpty();
  }

  @Test
  public void testContextId() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    CustomMetadata.builder()
                        .key("adk_context_id")
                        .stringValue("context-456")
                        .build()))
            .build();
    assertThat(EventConverter.contextId(e)).isEqualTo("context-456");
  }

  @Test
  public void testContextId_empty() {
    Event e = Event.builder().build();
    assertThat(EventConverter.contextId(e)).isEmpty();
  }

  @Test
  public void testFindUserFunctionCall_success() {
    Event agentEvent = Event.builder().author("agent").build();
    FunctionCall fc = FunctionCall.builder().name("my-func").id("fc-id").build();
    Event userEventWithCall =
        Event.builder()
            .author("user")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().functionCall(fc).build()))
                    .build())
            .build();

    FunctionResponse fr = FunctionResponse.builder().name("my-func").id("fc-id").build();
    Event userEventWithResponse =
        Event.builder()
            .author("user")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().functionResponse(fr).build()))
                    .build())
            .build();

    ImmutableList<Event> events =
        ImmutableList.of(userEventWithCall, agentEvent, userEventWithResponse);
    assertThat(EventConverter.findUserFunctionCall(events)).isEqualTo(userEventWithCall);
  }

  @Test
  public void testFindUserFunctionCall_noMatchingCall() {
    Event agentEvent = Event.builder().author("agent").build();
    FunctionCall fc = FunctionCall.builder().name("my-func").id("other-id").build();
    Event userEventWithCall =
        Event.builder()
            .author("user")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().functionCall(fc).build()))
                    .build())
            .build();

    FunctionResponse fr = FunctionResponse.builder().name("my-func").id("fc-id").build();
    Event userEventWithResponse =
        Event.builder()
            .author("user")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().functionResponse(fr).build()))
                    .build())
            .build();

    ImmutableList<Event> events =
        ImmutableList.of(userEventWithCall, agentEvent, userEventWithResponse);
    assertThat(EventConverter.findUserFunctionCall(events)).isNull();
  }

  @Test
  public void testFindUserFunctionCall_lastEventNotUser() {
    Event agentEvent = Event.builder().author("agent").build();
    FunctionCall fc = FunctionCall.builder().name("my-func").id("fc-id").build();
    Event userEventWithCall =
        Event.builder()
            .author("user")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().functionCall(fc).build()))
                    .build())
            .build();
    FunctionResponse fr = FunctionResponse.builder().name("my-func").id("fc-id").build();
    // Last event is not a user event, so should return null.
    Event agentEventWithResponse =
        Event.builder()
            .author("agent")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().functionResponse(fr).build()))
                    .build())
            .build();

    ImmutableList<Event> events =
        ImmutableList.of(userEventWithCall, agentEvent, agentEventWithResponse);

    assertThat(EventConverter.findUserFunctionCall(events)).isNull();
  }

  @Test
  public void testContentToParts() {
    Part textPart = Part.builder().text("hello").build();
    Content content = Content.builder().parts(ImmutableList.of(textPart)).build();
    ImmutableList<io.a2a.spec.Part<?>> list =
        EventConverter.contentToParts(Optional.of(content), false);
    assertThat(list).hasSize(1);
    assertThat(((TextPart) list.get(0)).getText()).isEqualTo("hello");
  }

  @Test
  public void testMessagePartsFromContext() {
    Session session =
        Session.builder("session1")
            .events(
                ImmutableList.of(
                    Event.builder()
                        .author("user")
                        .content(
                            Content.builder()
                                .parts(ImmutableList.of(Part.builder().text("hello").build()))
                                .build())
                        .build(),
                    Event.builder()
                        .author("test_agent")
                        .content(
                            Content.builder()
                                .parts(ImmutableList.of(Part.builder().text("hi").build()))
                                .build())
                        .customMetadata(ImmutableList.of(metadata("a2a:response", "{}")))
                        .build(),
                    Event.builder()
                        .author("other_agent")
                        .partial(true)
                        .content(
                            Content.builder()
                                .parts(ImmutableList.of(Part.builder().text("hey").build()))
                                .build())
                        .build()))
            .build();
    BaseAgent agent = new TestAgent();
    InvocationContext ctx =
        InvocationContext.builder()
            .session(session)
            .sessionService(new InMemorySessionService())
            .agent(agent)
            // An explicit invocation id keeps build() from drawing one; rephrasing another agent's
            // output must draw none.
            .invocationId("invocation-id")
            .uuidProvider(
                () -> {
                  throw new AssertionError("messagePartsFromContext must not draw ids");
                })
            .build();
    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.messagePartsFromContext(ctx);

    assertThat(parts).hasSize(2);
    assertThat(((TextPart) parts.get(0)).getText()).isEqualTo("For context:");
    assertThat(((TextPart) parts.get(1)).getText()).isEqualTo("[other_agent] said: hey");
    // Another agent's output is rephrased as a complete message even when its event was partial.
    assertThat(parts.get(1).getMetadata()).doesNotContainKey(A2AMetadataKey.PARTIAL.getType());
  }

  @Test
  public void messagePartsFromContext_afterLaterAgentEventWithoutResponse_sendsIt() {
    // An event this agent authored without a recorded response, such as a callback's.
    Session session =
        Session.builder("session1")
            .events(
                ImmutableList.of(
                    event("user", Part.builder().text("hello").build()),
                    event("test_agent", Part.builder().text("hi").build()).toBuilder()
                        .customMetadata(ImmutableList.of(metadata("a2a:response", "{}")))
                        .build(),
                    event("test_agent", Part.builder().text("noted").build()),
                    event("user", Part.builder().text("how are you?").build())))
            .build();
    InvocationContext ctx =
        InvocationContext.builder()
            .session(session)
            .sessionService(new InMemorySessionService())
            .agent(new TestAgent())
            .invocationId("invocation-id")
            .build();

    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.messagePartsFromContext(ctx);

    assertThat(parts.stream().map(part -> ((TextPart) part).getText()))
        .containsExactly("noted", "how are you?")
        .inOrder();
  }

  @Test
  public void answerParts_dropsResponseToCredentialCallById() {
    ImmutableList<Event> events =
        ImmutableList.of(
            event(
                "remote",
                functionCall("adk_request_credential", "cred-1", ImmutableMap.of()),
                functionCall("fn", "call-1", ImmutableMap.of())),
            event(
                "user",
                // Named differently, but answering the credential call's id.
                functionResponse("renamed", "cred-1", ImmutableMap.of("token", "t")),
                functionResponse("fn", "call-1", ImmutableMap.of("result", "ok"))));

    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.answerParts(events);

    assertThat(((DataPart) Iterables.getOnlyElement(parts)).getData())
        .containsEntry("id", "call-1");
  }

  @Test
  public void answerParts_dropsAnswerToMockAuthCall() {
    ImmutableList<Event> events =
        ImmutableList.of(
            event(
                "remote",
                functionCall(
                    "mock_function_call_for_required_user_auth", "auth-1", ImmutableMap.of()),
                functionCall("fn", "call-1", ImmutableMap.of())),
            event(
                "user",
                functionResponse(
                    "mock_function_call_for_required_user_auth",
                    "auth-1",
                    ImmutableMap.of("token", "t")),
                functionResponse("fn", "call-1", ImmutableMap.of("result", "ok"))));

    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.answerParts(events);

    assertThat(((DataPart) Iterables.getOnlyElement(parts)).getData())
        .containsEntry("id", "call-1");
  }

  @Test
  public void answerParts_dropsResponseWithoutIdToCredentialCallWithoutId() {
    ImmutableList<Event> events =
        ImmutableList.of(
            event(
                "remote",
                Part.builder()
                    .functionCall(FunctionCall.builder().name("adk_request_credential").build())
                    .build(),
                functionCall("fn", "call-1", ImmutableMap.of())),
            event(
                "user",
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .name("renamed")
                            .response(ImmutableMap.of("token", "t"))
                            .build())
                    .build(),
                functionResponse("fn", "call-1", ImmutableMap.of("result", "ok"))));

    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.answerParts(events);

    assertThat(((DataPart) Iterables.getOnlyElement(parts)).getData())
        .containsEntry("id", "call-1");
  }

  @Test
  public void findUserFunctionCall_pastEventWithoutParts_findsAnsweredCall() {
    Event call = event("remote", functionCall("fn", "call-1", ImmutableMap.of()));
    Event noParts = Event.builder().author("remote").content(Content.builder().build()).build();
    ImmutableList<Event> events =
        ImmutableList.of(
            call,
            noParts,
            event("user", functionResponse("fn", "call-1", ImmutableMap.of("result", "ok"))));

    assertThat(EventConverter.findUserFunctionCall(events)).isSameInstanceAs(call);
  }

  @Test
  public void answerParts_dropsCallWithAuthConfigArgument() {
    ImmutableList<Event> events =
        ImmutableList.of(
            event(
                "user",
                functionCall("ask", "call-2", ImmutableMap.of("authConfig", AUTH_CONFIG)),
                functionCall("ask", "call-3", ImmutableMap.of("auth_config", AUTH_CONFIG)),
                Part.builder().text("hi").build()));

    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.answerParts(events);

    assertThat(((TextPart) Iterables.getOnlyElement(parts)).getText()).isEqualTo("hi");
  }

  private static final class TestAgent extends BaseAgent {
    TestAgent() {
      super("test_agent", "test", ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }

  private static CustomMetadata metadata(String key, String value) {
    return CustomMetadata.builder().key(key).stringValue(value).build();
  }

  private static Event event(String author, Part... parts) {
    return Event.builder()
        .author(author)
        .content(Content.builder().parts(ImmutableList.copyOf(parts)).build())
        .build();
  }

  private static Part functionCall(String name, String id, Map<String, Object> args) {
    return Part.builder()
        .functionCall(FunctionCall.builder().name(name).id(id).args(args).build())
        .build();
  }

  private static Part functionResponse(String name, String id, Map<String, Object> response) {
    return Part.builder()
        .functionResponse(FunctionResponse.builder().name(name).id(id).response(response).build())
        .build();
  }
}
