package com.google.adk.a2a;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.atLeastOnce;
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
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class AgentExecutorTest {

  private TestAgent testAgent;

  @Before
  public void setUp() {
    testAgent = new TestAgent();
  }

  @Test
  public void createAgentExecutor_noAgent_succeeds() {
    var unused =
        new AgentExecutor.Builder()
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
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
              .build();
        });
  }

  @Test
  public void process_statefulAggregation_tracksArtifactIdAndAppendForAuthor() {
    Event partial1 =
        Event.builder()
            .partial(Optional.of(true))
            .author("agent_author")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk1").build()))
                    .build())
            .build();
    Event partial2 =
        Event.builder()
            .partial(Optional.of(true))
            .author("agent_author")
            .content(
                Content.builder()
                    .parts(ImmutableList.of(Part.builder().text("chunk2").build()))
                    .build())
            .build();
    Event finalEvent =
        Event.builder()
            .partial(Optional.of(false))
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
            .outputMode(AgentExecutor.OutputMode.ARTIFACT_PER_EVENT)
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

  private static final class TestAgent extends BaseAgent {
    private final Flowable<Event> eventsToEmit;

    TestAgent() {
      this(Flowable.empty());
    }

    TestAgent(Flowable<Event> eventsToEmit) {
      // BaseAgent constructor: name, description, examples, tools, model
      super("test_agent", "test", ImmutableList.of(), null, null);
      this.eventsToEmit = eventsToEmit;
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
