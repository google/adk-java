package com.google.adk.a2a.converters;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventConverterTest {

  @Test
  public void testTaskId() {
    Event e =
        Event.builder()
            .customMetadata(
                ImmutableList.of(
                    CustomMetadata.builder()
                        .key(EventConverter.ADK_TASK_ID_KEY)
                        .stringValue("task-123")
                        .build()))
            .build();
    assertThat(EventConverter.taskId(e)).isEqualTo("task-123");
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
                        .key(EventConverter.ADK_CONTEXT_ID_KEY)
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
                        .build(),
                    Event.builder()
                        .author("other_agent")
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
            .build();
    ImmutableList<io.a2a.spec.Part<?>> parts = EventConverter.messagePartsFromContext(ctx);

    assertThat(parts).hasSize(2);
    assertThat(((TextPart) parts.get(0)).getText()).isEqualTo("For context:");
    assertThat(((TextPart) parts.get(1)).getText()).isEqualTo("[other_agent] said: hey");
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
}
