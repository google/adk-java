package com.google.adk.a2a.converters;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ResponseConverterTest {

  private InvocationContext invocationContext;
  private Session session;

  @Before
  public void setUp() {
    session =
        Session.builder("session-1")
            .appName("demo")
            .userId("user")
            .events(ImmutableList.of())
            .build();
    invocationContext =
        InvocationContext.builder()
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .pluginManager(new PluginManager())
            .invocationId("invocation-1")
            .agent(new TestAgent())
            .session(session)
            .runConfig(RunConfig.builder().build())
            .endInvocation(false)
            .build();
  }

  private Task.Builder testTask() {
    return new Task.Builder().id("task-1").contextId("context-1");
  }

  private static TaskStatusUpdateEvent.Builder testTaskStatusUpdateEvent() {
    return new TaskStatusUpdateEvent.Builder().taskId("task-1").contextId("context-1");
  }

  @Test
  public void eventsToMessage_withNullEvents_returnsEmptyAgentMessage() {
    Message message = ResponseConverter.eventsToMessage(null, "context-1", "task-1");
    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getRole()).isEqualTo(Message.Role.AGENT);
    assertThat(message.getParts()).hasSize(1);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEmpty();
  }

  @Test
  public void eventsToMessage_withEmptyEvents_returnsEmptyAgentMessage() {
    Message message = ResponseConverter.eventsToMessage(ImmutableList.of(), "context-1", "task-1");
    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getRole()).isEqualTo(Message.Role.AGENT);
    assertThat(message.getParts()).hasSize(1);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEmpty();
  }

  @Test
  public void eventsToMessage_withSingleEvent_returnsMessage() {
    Event event =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("user")
            .content(
                Content.builder()
                    .role("user")
                    .parts(ImmutableList.of(Part.builder().text("Hello").build()))
                    .build())
            .build();

    Message message =
        ResponseConverter.eventsToMessage(ImmutableList.of(event), "context-1", "task-1");

    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getRole()).isEqualTo(Message.Role.USER);
    assertThat(message.getParts()).hasSize(1);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEqualTo("Hello");
  }

  @Test
  public void eventsToMessage_withMultipleEvents_returnsAggregatedMessage() {
    Event event1 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("agent")
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().text("Hello ").build()))
                    .build())
            .build();
    Event event2 =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .author("agent")
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().text("World").build()))
                    .build())
            .build();

    Message message =
        ResponseConverter.eventsToMessage(ImmutableList.of(event1, event2), "context-1", "task-1");

    assertThat(message.getMessageId()).isEqualTo("task-1");
    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getRole()).isEqualTo(Message.Role.AGENT);
    assertThat(message.getParts()).hasSize(2);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEqualTo("Hello ");
    assertThat(((TextPart) message.getParts().get(1)).getText()).isEqualTo("World");
  }

  @Test
  public void eventToMessage_convertsUserEvent() {
    Event event =
        Event.builder()
            .id("event-1")
            .author("user")
            .content(
                Content.builder()
                    .role("user")
                    .parts(ImmutableList.of(Part.builder().text("Test").build()))
                    .build())
            .build();

    Message message = ResponseConverter.eventToMessage(event, "context-1");

    assertThat(message.getMessageId()).isEqualTo("event-1");
    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getRole()).isEqualTo(Message.Role.USER);
    assertThat(message.getParts()).hasSize(1);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEqualTo("Test");
  }

  @Test
  public void eventToMessage_convertsAgentEvent() {
    Event event =
        Event.builder()
            .id("event-1")
            .author("agent")
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().text("Test").build()))
                    .build())
            .build();

    Message message = ResponseConverter.eventToMessage(event, "context-1");

    assertThat(message.getMessageId()).isEqualTo("event-1");
    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getRole()).isEqualTo(Message.Role.AGENT);
    assertThat(message.getParts()).hasSize(1);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEqualTo("Test");
  }

  @Test
  public void clientEventToEvent_withMessageEvent_returnsEvent() {
    Message a2aMessage =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("Hello")))
            .build();
    MessageEvent messageEvent = new MessageEvent(a2aMessage);

    Optional<Event> optionalEvent =
        ResponseConverter.clientEventToEvent(messageEvent, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event event = optionalEvent.get();
    assertThat(event.id()).isNotEmpty();
    assertThat(event.author()).isEqualTo(invocationContext.agent().name());
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Hello");
  }

  @Test
  public void messageToEvent_convertsMessage() {
    Message a2aMessage =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("test-message")))
            .build();

    Event event = ResponseConverter.messageToEvent(a2aMessage, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.author()).isEqualTo("test_agent");
    assertThat(event.content()).isPresent();
    Content content = event.content().get();
    assertThat(content.role()).hasValue("model");
    assertThat(content.parts().get()).hasSize(1);
    assertThat(content.parts().get().get(0).text()).hasValue("test-message");
  }

  @Test
  public void taskToEvent_withArtifacts_returnsEventFromLastArtifact() {
    io.a2a.spec.Part<?> a2aPart = new TextPart("Artifact content");
    com.google.genai.types.Part expected =
        com.google.genai.types.Part.builder().text("Artifact content").build();
    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(a2aPart)).build();
    Task task =
        testTask()
            .status(new TaskStatus(TaskState.COMPLETED))
            .artifacts(ImmutableList.of(artifact))
            .build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.content().get().parts().get().get(0)).isEqualTo(expected);
  }

  @Test
  public void taskToEvent_withStatusMessage_returnsEvent() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task = testTask().status(status).artifacts(null).build();
    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Status message");
  }

  @Test
  public void taskToEvent_withNoMessage_returnsEmptyEvent() {
    TaskStatus status = new TaskStatus(TaskState.WORKING, null, null);
    Task task = testTask().status(status).build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.invocationId()).isEqualTo(invocationContext.invocationId());
  }

  @Test
  public void clientEventToEvent_withTaskUpdateEventAndThought_returnsThoughtEvent() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("thought-1")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task = testTask().status(status).build();
    TaskStatusUpdateEvent updateEvent =
        new TaskStatusUpdateEvent("task-id-1", status, "context-1", false, null);
    TaskUpdateEvent event = new TaskUpdateEvent(task, updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.content().get().parts().get().get(0).text()).hasValue("thought-1");
    assertThat(resultEvent.content().get().parts().get().get(0).thought().get()).isTrue();
  }

  @Test
  public void clientEventToEvent_withTaskArtifactUpdateEvent_withLastChunkTrue_returnsTaskEvent() {
    io.a2a.spec.Part<?> a2aPart = new TextPart("Artifact content");
    com.google.genai.types.Part expected =
        com.google.genai.types.Part.builder().text("Artifact content").build();
    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(a2aPart)).build();
    Task task =
        testTask()
            .status(new TaskStatus(TaskState.COMPLETED))
            .artifacts(ImmutableList.of(artifact))
            .build();
    TaskArtifactUpdateEvent updateEvent =
        new TaskArtifactUpdateEvent.Builder()
            .lastChunk(true)
            .contextId("context-1")
            .artifact(artifact)
            .taskId("task-id-1")
            .build();
    TaskUpdateEvent event = new TaskUpdateEvent(task, updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.content().get().parts().get().get(0)).isEqualTo(expected);
  }

  @Test
  public void clientEventToEvent_withTaskArtifactUpdateEvent_withLastChunkFalse_returnsNull() {
    io.a2a.spec.Part<?> a2aPart = new TextPart("Artifact content");
    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(a2aPart)).build();
    Task task =
        testTask()
            .status(new TaskStatus(TaskState.COMPLETED))
            .artifacts(ImmutableList.of(artifact))
            .build();
    TaskArtifactUpdateEvent updateEvent =
        new TaskArtifactUpdateEvent.Builder()
            .lastChunk(false)
            .contextId("context-1")
            .artifact(artifact)
            .taskId("task-id-1")
            .build();
    TaskUpdateEvent event = new TaskUpdateEvent(task, updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isEmpty();
  }

  @Test
  public void clientEventToEvent_withFinalTaskStatusUpdateEvent_withMessage_returnsEvent() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Final status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.COMPLETED, statusMessage, null);
    TaskStatusUpdateEvent updateEvent =
        testTaskStatusUpdateEvent().isFinal(true).status(status).build();

    TaskUpdateEvent event = new TaskUpdateEvent(testTask().status(status).build(), updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.content().get().parts().get().get(0).text())
        .hasValue("Final status message");
    assertThat(resultEvent.content().get().parts().get().get(0).thought()).hasValue(false);
    assertThat(resultEvent.partial().orElse(false)).isFalse();
    assertThat(resultEvent.turnComplete()).hasValue(true);
  }

  @Test
  public void clientEventToEvent_withFinalTaskStatusUpdateEvent_withoutMessage_returnsEvent() {
    TaskStatus status = new TaskStatus(TaskState.COMPLETED, null, null);
    TaskStatusUpdateEvent updateEvent =
        new TaskStatusUpdateEvent("task-id-1", status, "context-1", true, null);
    TaskUpdateEvent event = new TaskUpdateEvent(testTask().status(status).build(), updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.turnComplete()).hasValue(true);
  }

  @Test
  public void clientEventToEvent_withNonFinalTaskStatusUpdateEvent_withoutMessage_returnsEmpty() {
    TaskStatus status = new TaskStatus(TaskState.WORKING, null, null);
    TaskStatusUpdateEvent updateEvent =
        new TaskStatusUpdateEvent("task-id-1", status, "context-1", false, null);
    TaskUpdateEvent event = new TaskUpdateEvent(testTask().status(status).build(), updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isEmpty();
  }

  @Test
  public void clientEventToEvent_withFailedTaskStatusUpdateEvent_returnsErrorEvent() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Task failed")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.FAILED, statusMessage, null);
    TaskStatusUpdateEvent updateEvent =
        new TaskStatusUpdateEvent("task-id-1", status, "context-1", true, null);
    TaskUpdateEvent event = new TaskUpdateEvent(testTask().status(status).build(), updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.errorMessage()).hasValue("Task failed");
    assertThat(resultEvent.turnComplete()).hasValue(true);
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
