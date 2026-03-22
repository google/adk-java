package com.google.adk.a2a.converters;

import static com.google.common.truth.Truth.assertThat;
import static java.util.stream.Collectors.joining;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
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
  public void taskToEvent_withGroundingMetadata_returnsEvent() {
    GroundingMetadata groundingMetadata =
        GroundingMetadata.builder().webSearchQueries("test-query").build();
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task =
        testTask()
            .status(status)
            .artifacts(null)
            .metadata(
                ImmutableMap.of(
                    A2AMetadataKey.GROUNDING_METADATA.getType(), groundingMetadata.toJson()))
            .build();
    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Status message");
    assertThat(event.groundingMetadata()).hasValue(groundingMetadata);
  }

  @Test
  public void taskToEvent_withCustomMetadata_returnsEvent() {
    ImmutableList<CustomMetadata> customMetadataList =
        ImmutableList.of(
            CustomMetadata.builder().key("test-key").stringValue("test-value").build());
    String customMetadataJson =
        customMetadataList.stream().map(CustomMetadata::toJson).collect(joining(",", "[", "]"));
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task =
        testTask()
            .status(status)
            .artifacts(null)
            .metadata(ImmutableMap.of(A2AMetadataKey.CUSTOM_METADATA.getType(), customMetadataJson))
            .build();
    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Status message");
    assertThat(event.customMetadata().get())
        .containsExactly(
            CustomMetadata.builder().key("a2a:task_id").stringValue("task-1").build(),
            CustomMetadata.builder().key("a2a:context_id").stringValue("context-1").build(),
            CustomMetadata.builder().key("test-key").stringValue("test-value").build())
        .inOrder();
  }

  @Test
  public void messageToEvent_withMissingTaskId_returnsEvent() {
    Message a2aMessage =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .taskId("task-1")
            .parts(ImmutableList.of(new TextPart("test-message")))
            .build();
    Event event = ResponseConverter.messageToEvent(a2aMessage, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.customMetadata()).isEmpty();
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
  public void taskToEvent_withInputRequired_parsesLongRunningToolIds() {
    ImmutableMap<String, Object> data =
        ImmutableMap.of("name", "myTool", "id", "call_123", "args", ImmutableMap.of());
    ImmutableMap<String, Object> metadata =
        ImmutableMap.of(
            A2AMetadataKey.TYPE.getType(),
            "function_call",
            A2AMetadataKey.IS_LONG_RUNNING.getType(),
            true);
    DataPart dataPart = new DataPart(data, metadata);
    ImmutableMap<String, Object> statusData =
        ImmutableMap.of("name", "messageTools", "id", "msg_123", "args", ImmutableMap.of());
    ImmutableMap<String, Object> statusMetadata =
        ImmutableMap.of(
            A2AMetadataKey.TYPE.getType(),
            "function_call",
            A2AMetadataKey.IS_LONG_RUNNING.getType(),
            true);
    DataPart statusDataPart = new DataPart(statusData, statusMetadata);
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(statusDataPart))
            .build();
    TaskStatus status = new TaskStatus(TaskState.INPUT_REQUIRED, statusMessage, null);

    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(dataPart)).build();
    Task task = testTask().status(status).artifacts(ImmutableList.of(artifact)).build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.longRunningToolIds().get()).containsExactly("call_123", "msg_123");
  }

  @Test
  public void taskToEvent_withFailedState_setsErrorCode() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Task failed")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.FAILED, statusMessage, null);
    Task task = testTask().status(status).artifacts(ImmutableList.of()).build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.errorMessage()).hasValue("Task failed");
  }

  @Test
  public void taskToEvent_withFinalEvent_returnsEmptyEvent() {
    TaskStatus status = new TaskStatus(TaskState.COMPLETED);
    Task task = testTask().status(status).artifacts(ImmutableList.of()).build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.invocationId()).isEqualTo(invocationContext.invocationId());
    assertThat(event.turnComplete()).hasValue(true);
    assertThat(event.content().flatMap(Content::parts).orElse(ImmutableList.of())).isEmpty();
  }

  @Test
  public void taskToEvent_withEmptyParts_returnsEmptyEvent() {
    TaskStatus status = new TaskStatus(TaskState.SUBMITTED);
    Task task = testTask().status(status).artifacts(ImmutableList.of()).build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.invocationId()).isEqualTo(invocationContext.invocationId());
    assertThat(event.content()).isPresent();
    assertThat(event.content().get().parts().orElse(ImmutableList.of())).isEmpty();
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
  public void
      clientEventToEvent_withTaskArtifactUpdateEvent_withLastChunkFalse_returnsHandlingPartialEvent() {
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
            .append(false)
            .contextId("context-1")
            .artifact(artifact)
            .taskId("task-id-1")
            .build();
    TaskUpdateEvent event = new TaskUpdateEvent(task, updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.partial().orElse(false)).isTrue();
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

  @Test
  public void taskToEvent_withInvalidMetadata_throwsException() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task =
        testTask()
            .status(status)
            .artifacts(null)
            .metadata(
                ImmutableMap.of(A2AMetadataKey.GROUNDING_METADATA.getType(), "{ invalid json ]"))
            .build();

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> ResponseConverter.taskToEvent(task, invocationContext));
    assertThat(exception).hasMessageThat().contains("Failed to parse metadata");
    assertThat(exception).hasMessageThat().contains("GroundingMetadata");
  }

  @Test
  public void taskToEvent_withErrorCode_returnsEvent() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task =
        testTask()
            .status(status)
            .artifacts(null)
            .metadata(ImmutableMap.of(A2AMetadataKey.ERROR_CODE.getType(), "\"STOP\""))
            .build();
    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.errorCode()).hasValue(new FinishReason(FinishReason.Known.STOP));
  }

  @Test
  public void taskToEvent_withUsageMetadata_returnsEvent() {
    GenerateContentResponseUsageMetadata usageMetadata =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Status message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.WORKING, statusMessage, null);
    Task task =
        testTask()
            .status(status)
            .artifacts(null)
            .metadata(
                ImmutableMap.of(A2AMetadataKey.USAGE_METADATA.getType(), usageMetadata.toJson()))
            .build();
    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.usageMetadata()).hasValue(usageMetadata);
  }

  @Test
  public void clientEventToEvent_withTaskArtifactUpdateEventAndPartialTrue_returnsEmpty() {
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
            .lastChunk(true)
            .metadata(ImmutableMap.of(A2AMetadataKey.PARTIAL.getType(), true))
            .contextId("context-1")
            .artifact(artifact)
            .taskId("task-id-1")
            .build();
    TaskUpdateEvent event = new TaskUpdateEvent(task, updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isEmpty();
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
