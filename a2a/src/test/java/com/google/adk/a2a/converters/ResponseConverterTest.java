/*
 * Copyright 2026 Google LLC
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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

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
import java.util.List;
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
  public void taskToEvent_withMalformedMetadata_dropsFieldsAndConverts() {
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
                    A2AMetadataKey.GROUNDING_METADATA.getType(), "not-valid-json",
                    A2AMetadataKey.USAGE_METADATA.getType(), "not-valid-json",
                    A2AMetadataKey.CUSTOM_METADATA.getType(), "not-valid-json",
                    A2AMetadataKey.ERROR_CODE.getType(), "not-valid-json"))
            .build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);

    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Status message");
    assertThat(event.groundingMetadata()).isEmpty();
    assertThat(event.usageMetadata()).isEmpty();
    assertThat(event.errorCode()).isEmpty();
    assertThat(event.customMetadata().get())
        .containsExactly(
            CustomMetadata.builder().key("a2a:task_id").stringValue("task-1").build(),
            CustomMetadata.builder().key("a2a:context_id").stringValue("context-1").build());
  }

  @Test
  public void taskToEvent_withUnrecognizedMetadataField_dropsField() {
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
                    // A nested object takes the convertValue branch rather than readValue. The
                    // genai builders reject unknown fields, so snake_case fails to convert.
                    A2AMetadataKey.GROUNDING_METADATA.getType(),
                    ImmutableMap.of("web_search_queries", ImmutableList.of("test-query"))))
            .build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);

    assertThat(event.groundingMetadata()).isEmpty();
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Status message");
  }

  @Test
  public void taskToEvent_withOneMalformedMetadataField_keepsTheValidFields() {
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
                    A2AMetadataKey.GROUNDING_METADATA.getType(),
                    groundingMetadata.toJson(),
                    A2AMetadataKey.USAGE_METADATA.getType(),
                    "not-valid-json"))
            .build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);

    assertThat(event.groundingMetadata()).hasValue(groundingMetadata);
    assertThat(event.usageMetadata()).isEmpty();
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
  public void taskToEvent_withAuthRequired_parsesLongRunningToolIds() {
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
    TaskStatus status = new TaskStatus(TaskState.AUTH_REQUIRED, statusMessage, null);

    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(dataPart)).build();
    Task task = testTask().status(status).artifacts(ImmutableList.of(artifact)).build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);
    assertThat(event).isNotNull();
    assertThat(event.longRunningToolIds().get()).containsExactly("call_123", "msg_123");
    assertThat(event.turnComplete()).hasValue(true);
  }

  @Test
  public void taskToEvent_withDataPartWithoutMetadata_fallsBackToInlineJson() {
    DataPart dataPart =
        new DataPart(
            ImmutableMap.of("name", "myTool", "id", "call_123", "args", ImmutableMap.of()));
    DataPart statusDataPart =
        new DataPart(
            ImmutableMap.of("name", "messageTool", "id", "msg_123", "args", ImmutableMap.of()));
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

    assertThat(event.longRunningToolIds().get()).isEmpty();
    List<com.google.genai.types.Part> parts = event.content().get().parts().get();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).functionCall()).isEmpty();
    assertThat(inlineJson(parts.get(0))).contains("call_123");
    assertThat(parts.get(1).functionCall()).isEmpty();
    assertThat(inlineJson(parts.get(1))).contains("msg_123");
  }

  @Test
  public void artifactToEvent_withDataPartWithoutMetadata_fallsBackToInlineJson() {
    DataPart dataPart =
        new DataPart(
            ImmutableMap.of("name", "myTool", "id", "call_123", "args", ImmutableMap.of()));
    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(dataPart)).build();

    Event event = ResponseConverter.artifactToEvent(artifact, invocationContext);

    assertThat(event.longRunningToolIds().get()).isEmpty();
    List<com.google.genai.types.Part> parts = event.content().get().parts().get();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).functionCall()).isEmpty();
    assertThat(inlineJson(parts.get(0))).contains("call_123");
  }

  /**
   * {@return the wrapped JSON payload of a part that {@link PartConverter} carried through as
   * generic data}
   *
   * <p>A DataPart with no {@code adk_type} metadata is not converted into a function call, even
   * when its data is shaped like one; it is serialized into an inline JSON blob instead.
   */
  private static String inlineJson(com.google.genai.types.Part part) {
    assertThat(part.inlineData()).isPresent();
    assertThat(part.inlineData().get().mimeType()).hasValue("text/plain");
    return new String(part.inlineData().get().data().get(), UTF_8);
  }

  @Test
  public void taskToEvent_withMixedMetadataParts_keepsLongRunningId() {
    DataPart noMetadataPart =
        new DataPart(
            ImmutableMap.of("name", "plainTool", "id", "call_plain", "args", ImmutableMap.of()));
    DataPart longRunningPart =
        new DataPart(
            ImmutableMap.of("name", "lrTool", "id", "call_lr", "args", ImmutableMap.of()),
            ImmutableMap.of(
                A2AMetadataKey.TYPE.getType(),
                "function_call",
                A2AMetadataKey.IS_LONG_RUNNING.getType(),
                true));
    Artifact artifact =
        new Artifact.Builder()
            .artifactId("artifact-1")
            .parts(ImmutableList.of(noMetadataPart, longRunningPart))
            .build();
    Task task =
        testTask()
            .status(new TaskStatus(TaskState.INPUT_REQUIRED, null, null))
            .artifacts(ImmutableList.of(artifact))
            .build();

    Event event = ResponseConverter.taskToEvent(task, invocationContext);

    assertThat(event.longRunningToolIds().get()).containsExactly("call_lr");
    assertThat(event.content().get().parts().get()).hasSize(2);
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
  public void
      clientEventToEvent_withAuthRequiredTaskStatusUpdateEvent_evenIfNonFinal_returnsTurnComplete() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Auth required message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.AUTH_REQUIRED, statusMessage, null);
    TaskStatusUpdateEvent updateEvent =
        testTaskStatusUpdateEvent().isFinal(false).status(status).build();

    TaskUpdateEvent event = new TaskUpdateEvent(testTask().status(status).build(), updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.content().get().parts().get().get(0).text())
        .hasValue("Auth required message");
    assertThat(resultEvent.partial().orElse(false)).isFalse();
    assertThat(resultEvent.turnComplete()).hasValue(true);
  }

  @Test
  public void
      clientEventToEvent_withInputRequiredTaskStatusUpdateEvent_evenIfNonFinal_returnsTurnComplete() {
    Message statusMessage =
        new Message.Builder()
            .role(Message.Role.AGENT)
            .parts(ImmutableList.of(new TextPart("Input required message")))
            .build();
    TaskStatus status = new TaskStatus(TaskState.INPUT_REQUIRED, statusMessage, null);
    TaskStatusUpdateEvent updateEvent =
        testTaskStatusUpdateEvent().isFinal(false).status(status).build();

    TaskUpdateEvent event = new TaskUpdateEvent(testTask().status(status).build(), updateEvent);

    Optional<Event> optionalEvent = ResponseConverter.clientEventToEvent(event, invocationContext);
    assertThat(optionalEvent).isPresent();
    Event resultEvent = optionalEvent.get();
    assertThat(resultEvent.content().get().parts().get().get(0).text())
        .hasValue("Input required message");
    assertThat(resultEvent.partial().orElse(false)).isFalse();
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
  public void taskToEvent_withInvalidMetadata_dropsFieldInsteadOfThrowing() {
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

    Event event = ResponseConverter.taskToEvent(task, invocationContext);

    assertThat(event.groundingMetadata()).isEmpty();
    assertThat(event.content().get().parts().get().get(0).text()).hasValue("Status message");
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
