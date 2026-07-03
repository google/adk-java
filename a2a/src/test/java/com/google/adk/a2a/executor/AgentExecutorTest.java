package com.google.adk.a2a.executor;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
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
import org.mockito.ArgumentCaptor;

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
              .artifactService(new InMemoryArtifactService())
              .agentExecutorConfig(AgentExecutorConfig.builder().build())
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
    Object lastEvent = Iterables.getLast(enqueuedEvents);
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

    // Filter for artifact events
    Optional<TaskArtifactUpdateEvent> artifactEvent =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskArtifactUpdateEvent)
            .map(e -> (TaskArtifactUpdateEvent) e)
            .findFirst();

    assertThat(artifactEvent).isEmpty();
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
                  .status(new TaskStatus(event.getStatus().state(), newMessage, null))
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

  @Test
  public void execute_runnerFails_registersFailedEvent() {
    testAgent.setEventsToEmit(Flowable.error(new RuntimeException("Runner error")));
    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    RequestContext ctx = createRequestContext();
    executor.execute(ctx, eventQueue);

    ImmutableList<TaskStatusUpdateEvent> finalEvents =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskStatusUpdateEvent)
            .map(e -> (TaskStatusUpdateEvent) e)
            // final events could be COMPLETED, FAILED, CANCELED, REJECTED or UNKNOWN
            // as per io.a2a.spec.TaskState
            .filter(TaskStatusUpdateEvent::isFinal)
            .collect(toImmutableList());

    assertThat(finalEvents).hasSize(1);

    TaskStatusUpdateEvent statusEvent = finalEvents.get(0);
    assertThat(statusEvent.getStatus().state()).isEqualTo(TaskState.FAILED);
    assertThat(statusEvent.getStatus().message().getParts().get(0)).isInstanceOf(TextPart.class);
    TextPart textPart = (TextPart) statusEvent.getStatus().message().getParts().get(0);
    assertThat(textPart.getText()).isEqualTo("Runner error");
  }

  @Test
  public void execute_runnerSucceeds_registerCompletedTaskFails_noFailedTaskRegistered() {
    testAgent.setEventsToEmit(Flowable.empty());

    // Configure eventQueue to throw exception when TaskStatusUpdateEvent is enqueued
    doAnswer(
            invocation -> {
              Object event = invocation.getArgument(0);
              if (event instanceof TaskStatusUpdateEvent statusUpdate) {
                if (statusUpdate.getStatus().state() == TaskState.COMPLETED) {
                  throw new RuntimeException("Enqueue failed");
                }
              }
              return null;
            })
        .when(eventQueue)
        .enqueueEvent(any());

    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    RequestContext ctx = createRequestContext();
    executor.execute(ctx, eventQueue);

    // Verify status events in the tracked enqueuedEvents
    ImmutableList<TaskStatusUpdateEvent> statusEvents =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskStatusUpdateEvent)
            .map(e -> (TaskStatusUpdateEvent) e)
            .filter(TaskStatusUpdateEvent::isFinal)
            .collect(toImmutableList());

    // There should be no final status events.
    assertThat(statusEvents).isEmpty();
  }

  private RequestContext createRequestContext() {
    Message message =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("trigger")))
            .build();

    RequestContext ctx = mock(RequestContext.class);
    when(ctx.getMessage()).thenReturn(message);
    when(ctx.getTaskId()).thenReturn("task-" + UUID.randomUUID());
    when(ctx.getContextId()).thenReturn("ctx-" + UUID.randomUUID());
    return ctx;
  }

  @Test
  public void process_statefulAggregation_tracksArtifactIdAndAppendForAuthor() {
    Event partial1 =
        Event.builder()
            .partial(true)
            .author("agent_author")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk1").build()))
                    .build())
            .build();
    Event partial2 =
        Event.builder()
            .partial(true)
            .author("agent_author")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk2").build()))
                    .build())
            .build();
    Event finalEvent =
        Event.builder()
            .partial(false)
            .author("agent_author")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk1chunk2").build()))
                    .build())
            .build();
    TestAgent agent = new TestAgent(Flowable.just(partial1, partial2, finalEvent));
    AgentExecutor executor =
        new AgentExecutor.Builder()
            .app(App.builder().name("test_app").rootAgent(agent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(
                AgentExecutorConfig.builder()
                    .outputMode(AgentExecutorConfig.OutputMode.ARTIFACT_PER_EVENT)
                    .build())
            .build();
    RequestContext requestContext = mock(RequestContext.class);
    Message message =
        new Message.Builder()
            .messageId("msg-id")
            .taskId("task-id")
            .contextId("context-id")
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("test")))
            .build();
    when(requestContext.getMessage()).thenReturn(message);
    when(requestContext.getTaskId()).thenReturn("task-id");
    when(requestContext.getContextId()).thenReturn("context-id");
    EventQueue eventQueue = mock(EventQueue.class);

    executor.execute(requestContext, eventQueue);

    ArgumentCaptor<io.a2a.spec.Event> eventCaptor =
        ArgumentCaptor.forClass(io.a2a.spec.Event.class);
    verify(eventQueue, atLeastOnce()).enqueueEvent(eventCaptor.capture());
    ImmutableList<TaskArtifactUpdateEvent> artifactEvents =
        eventCaptor.getAllValues().stream()
            .filter(e -> e instanceof TaskArtifactUpdateEvent)
            .map(e -> (TaskArtifactUpdateEvent) e)
            .collect(toImmutableList());
    TaskArtifactUpdateEvent ev1 = artifactEvents.get(0);
    TaskArtifactUpdateEvent ev2 = artifactEvents.get(1);
    TaskArtifactUpdateEvent ev3 = artifactEvents.get(2);
    String firstArtifactId = ev1.getArtifact().artifactId();
    // Event 1 (Partial)
    assertThat(artifactEvents).hasSize(3);
    assertThat(ev1.isAppend()).isTrue();
    assertThat(ev1.isLastChunk()).isFalse();
    // Event 2 (Partial)
    assertThat(ev2.isAppend()).isTrue();
    assertThat(ev2.isLastChunk()).isFalse();
    assertThat(ev2.getArtifact().artifactId()).isEqualTo(firstArtifactId);
    // Event 3 (Non-partial, final)
    assertThat(ev3.isAppend()).isFalse();
    assertThat(ev3.isLastChunk()).isTrue();
    assertThat(ev3.getArtifact().artifactId()).isEqualTo(firstArtifactId);
  }

  @Test
  public void execute_withDefaultArtifactPerRun_emitsMessageAndLastChunk() {
    Event partialEvent =
        Event.builder()
            .partial(true)
            .author("agent")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk1").build()))
                    .build())
            .build();
    Event finalEvent =
        Event.builder()
            .partial(false)
            .author("agent")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk1chunk2").build()))
                    .build())
            .build();

    testAgent.setEventsToEmit(Flowable.just(partialEvent, finalEvent));
    AgentExecutor executor =
        new AgentExecutor.Builder()
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(
                AgentExecutorConfig.builder()
                    .outputMode(AgentExecutorConfig.OutputMode.ARTIFACT_PER_RUN)
                    .build())
            .build();

    RequestContext requestContext = createRequestContext();
    executor.execute(requestContext, eventQueue);

    // Verify events were correctly formed.
    ImmutableList<TaskArtifactUpdateEvent> artifactEvents =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskArtifactUpdateEvent)
            .map(e -> (TaskArtifactUpdateEvent) e)
            .collect(toImmutableList());

    assertThat(artifactEvents).hasSize(2);
    // Partial event has lastChunk = false
    assertThat(artifactEvents.get(0).isLastChunk()).isFalse();
    // Final event has lastChunk = true
    assertThat(artifactEvents.get(1).isLastChunk()).isTrue();

    // First chunk appends=false, subsequent chunks append=true
    assertThat(artifactEvents.get(0).isAppend()).isFalse();
    assertThat(artifactEvents.get(1).isAppend()).isTrue();

    // Now verify the final TaskStatusUpdateEvent has a null message as expected
    Optional<TaskStatusUpdateEvent> statusEvent =
        enqueuedEvents.stream()
            .filter(e -> e instanceof TaskStatusUpdateEvent)
            .map(e -> (TaskStatusUpdateEvent) e)
            .filter(TaskStatusUpdateEvent::isFinal)
            .findFirst();

    assertThat(statusEvent).isPresent();
    Message finalMessage = statusEvent.get().getStatus().message();
    assertThat(finalMessage).isNull();
  }

  private static final class TestAgent extends BaseAgent {
    private Flowable<Event> eventsToEmit;

    TestAgent() {
      this(Flowable.empty());
    }

    TestAgent(Flowable<Event> eventsToEmit) {
      // BaseAgent constructor: name, description, examples, tools, model
      super("test_agent", "test", ImmutableList.of(), null, null);
      this.eventsToEmit = eventsToEmit;
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
