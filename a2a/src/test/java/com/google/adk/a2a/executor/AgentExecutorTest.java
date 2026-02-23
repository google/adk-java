package com.google.adk.a2a.executor;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgentExecutorTest {

  private EventQueue eventQueue;
  private List<Object> enqueuedEvents;
  private TestAgent testAgent;

  @Before
  public void setUp() {
    enqueuedEvents = new ArrayList<>();
    eventQueue = mock(EventQueue.class);
    doAnswer(
            invocation -> {
              enqueuedEvents.add(invocation.getArgument(0));
              return null;
            })
        .when(eventQueue)
        .enqueueEvent(any());
    testAgent = new TestAgent();
  }


  @Test
  public void createAgentExecutor_noAgent_succeeds() {
    var unused =
        new AgentExecutor.Builder()
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .build();
  }

  @Test
  public void createAgentExecutor_withAgentAndApp_throwsException() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          new AgentExecutor.Builder()
              .agent(testAgent)
              .app(App.builder().name("test_app").rootAgent(testAgent).build())
              .sessionService(new InMemorySessionService())
              .agentExecutorConfig(AgentExecutorConfig.builder().build())
              .artifactService(new InMemoryArtifactService())
              .build();
        });
  }

  @Test
  public void createAgentExecutor_withEmptyAgentAndApp_throwsException() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          new AgentExecutor.Builder()
              .sessionService(new InMemorySessionService())
              .artifactService(new InMemoryArtifactService())
              .agentExecutorConfig(AgentExecutorConfig.builder().build())
              .build();
        });
  }

  @Test
  public void createAgentExecutor_noAgentExecutorConfig_throwsException() {
    assertThrows(
        NullPointerException.class,
        () -> {
          new AgentExecutor.Builder()
              .app(App.builder().name("test_app").rootAgent(testAgent).build())
              .sessionService(new InMemorySessionService())
              .artifactService(new InMemoryArtifactService())
              .build();
        });
  }

  @Test
  public void execute_withBeforeExecuteCallback_cancelsExecutionOnError() {
    // If callback returns error, execution should stop/fail.
    Callbacks.BeforeExecuteCallback callback =
        ctx -> Single.error(new RuntimeException("Cancelled"));

    AgentExecutorConfig config =
        AgentExecutorConfig.builder().beforeExecuteCallback(callback).build();

    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(config)
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    RequestContext ctx = createRequestContext();
    executor.execute(ctx, eventQueue);

    // Verify error handling triggered cleanup and fail event
    // The executor catches the error and emits failed event.
    assertThat(enqueuedEvents).isNotEmpty();
    Object lastEvent = enqueuedEvents.get(enqueuedEvents.size() - 1);
    assertThat(lastEvent).isInstanceOf(TaskStatusUpdateEvent.class);
    TaskStatusUpdateEvent statusEvent = (TaskStatusUpdateEvent) lastEvent;
    assertThat(statusEvent.getStatus().state().toString()).isEqualTo("FAILED");
    assertThat(statusEvent.getStatus().message().getParts().get(0)).isInstanceOf(TextPart.class);
    TextPart textPart = (TextPart) statusEvent.getStatus().message().getParts().get(0);
    assertThat(textPart.getText()).contains("Cancelled");
  }

  @Test
  public void execute_withBeforeExecuteCallback_skipsExecutionIfTrue() {
    Callbacks.BeforeExecuteCallback callback = ctx -> Single.just(true);

    AgentExecutorConfig config =
        AgentExecutorConfig.builder().beforeExecuteCallback(callback).build();

    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(config)
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    RequestContext ctx = createRequestContext();
    executor.execute(ctx, eventQueue);
    for (Object event : enqueuedEvents) {
      if (event instanceof TaskStatusUpdateEvent) {
        TaskStatusUpdateEvent statusEvent = (TaskStatusUpdateEvent) event;
        System.out.println("statusEvent: " + statusEvent.getStatus().state().toString());
        // assertThat(statusEvent.getStatus().state().toString()).isEqualTo("CANCELLED");
      }
    }

    // Filter for artifact events
    Optional<TaskArtifactUpdateEvent> artifactEvent =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskArtifactUpdateEvent)
            .map(e -> (TaskArtifactUpdateEvent) e)
            .findFirst();

    assertThat(artifactEvent.isPresent()).isFalse();
  }

  @Test
  public void execute_withAfterEventCallback_modifiesEvent() {
    // Agent emits an event. Callback intercepts and modifies it.
    Part textPart = Part.builder().text("Hello world").build();
    Event agentEvent =
        Event.builder()
            .id("event-1")
            .author("agent")
            .content(Content.builder().role("model").parts(ImmutableList.of(textPart)).build())
            .build();
    testAgent.setEventsToEmit(Flowable.just(agentEvent));

    Callbacks.AfterEventCallback callback =
        (ctx, event, sourceEvent) -> {
          // Modify event by adding metadata
          return Maybe.just(
              new TaskArtifactUpdateEvent.Builder(event)
                  .metadata(ImmutableMap.of("modified", true))
                  .build());
        };

    AgentExecutorConfig config = AgentExecutorConfig.builder().afterEventCallback(callback).build();

    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(config)
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    RequestContext ctx = createRequestContext();
    executor.execute(ctx, eventQueue);

    // Filter for artifact events
    Optional<TaskArtifactUpdateEvent> artifactEvent =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskArtifactUpdateEvent)
            .map(e -> (TaskArtifactUpdateEvent) e)
            .findFirst();

    assertThat(artifactEvent).isPresent();
    assertThat(artifactEvent.get().getMetadata()).containsEntry("modified", true);
  }

  @Test
  public void execute_withAfterExecuteCallback_modifiesStatus() {
    testAgent.setEventsToEmit(Flowable.empty()); // Just complete

    Callbacks.AfterExecuteCallback callback =
        (ctx, event) -> {
          // Modify status to have different message
          Message newMessage =
              new Message.Builder()
                  .messageId(UUID.randomUUID().toString())
                  .role(Message.Role.AGENT)
                  .parts(ImmutableList.of(new TextPart("Modified completion")))
                  .build();

          return Maybe.just(
              new TaskStatusUpdateEvent.Builder(event)
                  .status(new io.a2a.spec.TaskStatus(event.getStatus().state(), newMessage, null))
                  .build());
        };

    AgentExecutorConfig config =
        AgentExecutorConfig.builder().afterExecuteCallback(callback).build();

    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(config)
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    RequestContext ctx = createRequestContext();
    executor.execute(ctx, eventQueue);

    // Verify status event
    Optional<TaskStatusUpdateEvent> statusEvent =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskStatusUpdateEvent)
            .map(e -> (TaskStatusUpdateEvent) e)
            .filter(TaskStatusUpdateEvent::isFinal)
            .findFirst();

    assertThat(statusEvent).isPresent();
    assertThat(statusEvent.get().getStatus().message().getParts().get(0))
        .isInstanceOf(TextPart.class);
    TextPart textPart = (TextPart) statusEvent.get().getStatus().message().getParts().get(0);
    assertThat(textPart.getText()).isEqualTo("Modified completion");
  }

  private RequestContext createRequestContext() {
    Message message =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("trigger")))
            .build();

    // Mock RequestContext properly or use a builder if available.
    // RequestContext probably has builder.
    // Assuming simple mock for now or constructor.
    // Let's check AgentExecutor usage: ctx.getMessage(), ctx.getTaskId(), ctx.getContextId().
    RequestContext ctx = mock(RequestContext.class);
    when(ctx.getMessage()).thenReturn(message);
    when(ctx.getTaskId()).thenReturn("task-" + UUID.randomUUID());
    when(ctx.getContextId()).thenReturn("ctx-" + UUID.randomUUID());
    return ctx;
  }

  private static final class TestAgent extends BaseAgent {
    private Flowable<Event> eventsToEmit = Flowable.empty();

    TestAgent() {
      // BaseAgent constructor: name, description, examples, tools, model
      super("test_agent", "test", ImmutableList.of(), null, null);
    }

    void setEventsToEmit(Flowable<Event> events) {
      this.eventsToEmit = events;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return eventsToEmit;
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return eventsToEmit;
    }
  }
}
