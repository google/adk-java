package com.google.adk.a2a;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.adk.a2a.common.A2AMetadata;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
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
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.Message;
import io.a2a.spec.Task;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class RemoteA2AAgentTest {

  private Client mockClient;
  private AgentCard agentCard;
  private InvocationContext invocationContext;
  private Session session;

  @Before
  public void setUp() {
    mockClient = mock(Client.class);
    agentCard =
        new AgentCard.Builder()
            .name("remote-agent")
            .description("Remote Agent")
            .version("1.0.0")
            .url("http://example.com")
            .capabilities(new AgentCapabilities.Builder().streaming(true).build())
            .defaultInputModes(ImmutableList.of("text"))
            .defaultOutputModes(ImmutableList.of("text"))
            .skills(ImmutableList.of())
            .build();

    when(mockClient.getAgentCard()).thenReturn(agentCard);

    session =
        Session.builder("session-1")
            .appName("demo")
            .userId("user")
            .events(
                ImmutableList.of(
                    Event.builder()
                        .id("event-1")
                        .author("user")
                        .content(
                            Content.builder()
                                .role("user")
                                .parts(ImmutableList.of(Part.builder().text("Hello").build()))
                                .build())
                        .build()))
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

  @Test
  public void runAsync_aggregatesPartialEvents() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Hello ", true, false), agentCard);
          consumer.accept(createPartialEvent("World!", true, false), agentCard);
          consumer.accept(createFinalEvent("Final artifact content"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(4);
    assertText(events.get(0), "Hello ");
    assertNotAggregated(events.get(0));
    assertText(events.get(1), "World!");
    assertNotAggregated(events.get(1));
    Event aggregatedEvent = events.get(2);
    assertThat(aggregatedEvent.content().get().parts().get()).hasSize(1);
    assertThought(aggregatedEvent, false);
    assertText(aggregatedEvent, "Hello World!");
    assertAggregated(aggregatedEvent);
    Event finalEvent = events.get(3);
    assertText(finalEvent, "Final artifact content");
    assertRequestMetadata(finalEvent);
    assertResponseMetadata(finalEvent);
  }

  @Test
  public void runAsync_aggregatesInterleavedFunctionCalls() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Hello ", true, false), agentCard);
          consumer.accept(createPartialFunctionCallEvent("get_weather", "call_1"), agentCard);
          consumer.accept(createPartialEvent("World!", true, false), agentCard);
          consumer.accept(createFinalEvent("Final"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(6);
    assertText(events.get(0), "Hello ");
    assertNotAggregated(events.get(0));
    assertAggregated(events.get(1)); // Flushed Aggregation
    assertText(events.get(1), "Hello ");
    assertThat(events.get(2).content().get().parts().get().get(0).functionCall()).isPresent();
    assertThat(
            events
                .get(2)
                .content()
                .get()
                .parts()
                .get()
                .get(0)
                .functionCall()
                .get()
                .name()
                .orElse(""))
        .isEqualTo("get_weather");
    assertText(events.get(3), "World!");
    assertNotAggregated(events.get(3));
    assertText(events.get(5), "Final");
    assertNotAggregated(events.get(5));
    assertRequestMetadata(events.get(5));
    assertResponseMetadata(events.get(5));
  }

  @Test
  public void runAsync_aggregatesFiles() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Here is a file: ", true, false), agentCard);
          consumer.accept(
              createPartialFileEvent("http://example.com/file.txt", "text/plain"), agentCard);
          consumer.accept(createFinalEvent("Done"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(4);
    assertText(events.get(0), "Here is a file: ");
    assertNotAggregated(events.get(0));
    assertAggregated(events.get(1)); // Flushed Aggregation
    assertText(events.get(1), "Here is a file: ");
    Part filePart = events.get(2).content().get().parts().get().get(0);
    assertThat(filePart.fileData()).isPresent();
    assertThat(filePart.fileData().get().fileUri().orElse(""))
        .isEqualTo("http://example.com/file.txt");
    assertRequestMetadata(events.get(2));
    assertResponseMetadata(events.get(2));

    assertText(events.get(3), "Done");
    assertRequestMetadata(events.get(3));
    assertResponseMetadata(events.get(3));
  }

  @Test
  public void runAsync_handlesTasksWithStatusMessage() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          Task task =
              new Task.Builder()
                  .id("task-1")
                  .contextId("context-1")
                  .status(
                      new TaskStatus(
                          TaskState.COMPLETED,
                          new Message.Builder()
                              .role(Message.Role.AGENT)
                              .parts(ImmutableList.of(new TextPart("hello")))
                              .build(),
                          null))
                  .build();
          consumer.accept(new TaskEvent(task), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertText(events.get(0), "hello");
    assertRequestMetadata(events.get(0));
    assertResponseMetadata(events.get(0));
  }

  @Test
  public void runAsync_handlesTasksWithMultipartArtifact() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          Artifact artifact =
              new Artifact.Builder()
                  .artifactId("artifact-1")
                  .parts(ImmutableList.of(new TextPart("hello"), new TextPart("world")))
                  .build();
          Task task =
              new Task.Builder()
                  .id("task-1")
                  .contextId("context-1")
                  .status(new TaskStatus(TaskState.COMPLETED))
                  .artifacts(ImmutableList.of(artifact))
                  .build();
          consumer.accept(new TaskEvent(task), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content().get().parts().get()).hasSize(2);
    assertText(events.get(0), 0, "hello");
    assertText(events.get(0), 1, "world");
    assertRequestMetadata(events.get(0));
    assertResponseMetadata(events.get(0));
  }

  @Test
  public void runAsync_handlesNonFinalStatusUpdatesAsThoughts() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          Task task1 =
              new Task.Builder()
                  .id("task-1")
                  .contextId("context-1")
                  .status(new TaskStatus(TaskState.SUBMITTED))
                  .build();
          consumer.accept(
              new TaskUpdateEvent(
                  task1,
                  new TaskStatusUpdateEvent.Builder()
                      .taskId("task-1")
                      .contextId("context-1")
                      .status(
                          new TaskStatus(
                              TaskState.SUBMITTED,
                              new Message.Builder()
                                  .role(Message.Role.AGENT)
                                  .parts(ImmutableList.of(new TextPart("submitted...")))
                                  .build(),
                              null))
                      .build()),
              agentCard);
          Task task2 =
              new Task.Builder()
                  .id("task-1")
                  .contextId("context-1")
                  .status(new TaskStatus(TaskState.WORKING))
                  .build();
          consumer.accept(
              new TaskUpdateEvent(
                  task2,
                  new TaskStatusUpdateEvent.Builder()
                      .taskId("task-1")
                      .contextId("context-1")
                      .status(
                          new TaskStatus(
                              TaskState.WORKING,
                              new Message.Builder()
                                  .role(Message.Role.AGENT)
                                  .parts(ImmutableList.of(new TextPart("working...")))
                                  .build(),
                              null))
                      .build()),
              agentCard);
          Task task3 =
              new Task.Builder()
                  .id("task-1")
                  .contextId("context-1")
                  .status(new TaskStatus(TaskState.COMPLETED))
                  .artifacts(
                      ImmutableList.of(
                          new Artifact.Builder()
                              .artifactId("a1")
                              .parts(ImmutableList.of(new TextPart("done")))
                              .build()))
                  .build();
          consumer.accept(new TaskEvent(task3), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertText(events.get(0), "submitted...");
    assertThought(events.get(0), true);
    assertRequestMetadata(events.get(0));
    assertResponseMetadata(events.get(0));
    assertText(events.get(1), "working...");
    assertThought(events.get(1), true);
    assertRequestMetadata(events.get(1));
    assertResponseMetadata(events.get(1));
    assertText(events.get(2), "done");
    assertThought(events.get(2), false);
    assertRequestMetadata(events.get(2));
    assertResponseMetadata(events.get(2));
  }

  @Test
  @SuppressWarnings("unchecked") // cast for Mockito
  public void runAsync_constructsRequestWithHistory() {
    RemoteA2AAgent agent = createAgent();
    Session historySession =
        Session.builder("session-2")
            .appName("demo")
            .userId("user")
            .events(
                ImmutableList.of(
                    Event.builder()
                        .id("e1")
                        .author("user")
                        .content(
                            Content.builder()
                                .role("user")
                                .parts(ImmutableList.of(Part.builder().text("hello").build()))
                                .build())
                        .build(),
                    Event.builder()
                        .id("e2")
                        .author("model")
                        .content(
                            Content.builder()
                                .role("model")
                                .parts(ImmutableList.of(Part.builder().text("hi").build()))
                                .build())
                        .build(),
                    Event.builder()
                        .id("e3")
                        .author("user")
                        .content(
                            Content.builder()
                                .role("user")
                                .parts(
                                    ImmutableList.of(Part.builder().text("how are you?").build()))
                                .build())
                        .build()))
            .build();
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .pluginManager(new PluginManager())
            .invocationId("invocation-2")
            .agent(new TestAgent())
            .session(historySession)
            .runConfig(RunConfig.builder().build())
            .build();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createFinalEvent("fine"), agentCard);
        });

    var unused = agent.runAsync(context).toList().blockingGet();

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockClient)
        .sendMessage(messageCaptor.capture(), any(List.class), any(Consumer.class), any());
    Message message = messageCaptor.getValue();
    assertThat(message.getRole()).isEqualTo(Message.Role.USER);
    assertThat(message.getParts()).hasSize(3);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEqualTo("hello");
    assertThat(((TextPart) message.getParts().get(1)).getText()).isEqualTo("hi");
    assertThat(((TextPart) message.getParts().get(2)).getText()).isEqualTo("how are you?");
  }

  @Test
  @SuppressWarnings("unchecked") // cast for Mockito
  public void runAsync_constructsRequestWithFunctionResponse() {
    RemoteA2AAgent agent = createAgent();
    Session session =
        Session.builder("session-3")
            .appName("demo")
            .userId("user")
            .events(
                ImmutableList.of(
                    Event.builder()
                        .id("e1")
                        .author("user")
                        .content(
                            Content.builder()
                                .role("user")
                                .parts(
                                    ImmutableList.of(
                                        Part.builder()
                                            .functionResponse(
                                                FunctionResponse.builder()
                                                    .name("fn")
                                                    .id("call-1")
                                                    .response(ImmutableMap.of("status", "ok"))
                                                    .build())
                                            .build()))
                                .build())
                        .build()))
            .build();
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .pluginManager(new PluginManager())
            .invocationId("invocation-3")
            .agent(new TestAgent())
            .session(session)
            .runConfig(RunConfig.builder().build())
            .build();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createFinalEvent("ok"), agentCard);
        });

    var unused = agent.runAsync(context).toList().blockingGet();

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockClient)
        .sendMessage(messageCaptor.capture(), any(List.class), any(Consumer.class), any());
    Message message = messageCaptor.getValue();

    assertThat(message.getParts()).hasSize(1);
    io.a2a.spec.Part<?> part = message.getParts().get(0);
    assertThat(part).isInstanceOf(DataPart.class);
    DataPart dataPart = (DataPart) part;
    assertThat(dataPart.getData().get("name")).isEqualTo("fn");
    assertThat(dataPart.getData().get("id")).isEqualTo("call-1");
    assertThat(dataPart.getMetadata().get("adk_type")).isEqualTo("function_response");
  }

  @Test
  public void runAsync_invokesBeforeAndAfterCallbacks() {
    AtomicBoolean beforeCalled = new AtomicBoolean(false);
    AtomicBoolean afterCalled = new AtomicBoolean(false);
    RemoteA2AAgent agent =
        getAgentBuilder()
            .beforeAgentCallback(
                ImmutableList.<Callbacks.BeforeAgentCallback>of(
                    (CallbackContext unused) -> {
                      beforeCalled.set(true);
                      return Maybe.empty();
                    }))
            .afterAgentCallback(
                ImmutableList.<Callbacks.AfterAgentCallback>of(
                    (CallbackContext unused) -> {
                      afterCalled.set(true);
                      return Maybe.empty();
                    }))
            .build();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createFinalEvent("done"), agentCard);
        });

    var unused = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(beforeCalled.get()).isTrue();
    assertThat(afterCalled.get()).isTrue();
  }

  @Test
  public void runAsync_aggregatesCodeExecution() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialCodeEvent("print('hello')", "java"), agentCard);
          consumer.accept(createPartialCodeResultEvent("hello\n", "ok"), agentCard);
          consumer.accept(createFinalEvent("Done"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    Part codePart = events.get(0).content().get().parts().get().get(0);
    assertThat(codePart.executableCode()).isPresent();
    assertThat(codePart.executableCode().get().code()).hasValue("print('hello')");
    assertThat(codePart.executableCode().get().language().get().toString()).isEqualTo("java");
    Part resultPart = events.get(1).content().get().parts().get().get(0);
    assertThat(resultPart.codeExecutionResult()).isPresent();
    assertThat(resultPart.codeExecutionResult().get().output()).hasValue("hello\n");
    assertText(events.get(2), "Done");
    assertRequestMetadata(events.get(2));
    assertResponseMetadata(events.get(2));
  }

  @Test
  public void runAsync_aggregatesCodeExecution_defaultsToEmptyLanguage() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          Map<String, Object> data = new HashMap<>();
          data.put("code", "print('hello')");
          Map<String, Object> metadata = new HashMap<>();
          metadata.put("adk_type", "executable_code");
          consumer.accept(
              createTestEvent(new DataPart(data, metadata), TaskState.WORKING, true, false),
              agentCard);
          consumer.accept(createFinalEvent("Done"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(2);
    Part codePart = events.get(0).content().get().parts().get().get(0);
    assertThat(codePart.executableCode()).isPresent();
    assertThat(codePart.executableCode().get().code()).hasValue("print('hello')");
    assertThat(codePart.executableCode().get().language().get().toString())
        .isEqualTo("LANGUAGE_UNSPECIFIED");
  }

  @Test
  public void runAsync_aggregatesCodeExecutionResult_withOnlyMetadata() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          Map<String, Object> data = new HashMap<>();
          Map<String, Object> metadata = new HashMap<>();
          metadata.put("adk_type", "code_execution_result");
          consumer.accept(
              createTestEvent(new DataPart(data, metadata), TaskState.WORKING, true, false),
              agentCard);
          consumer.accept(createFinalEvent("Done"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(2);
    Part resultPart = events.get(0).content().get().parts().get().get(0);
    assertThat(resultPart.codeExecutionResult()).isPresent();
    assertThat(resultPart.codeExecutionResult().get().outcome().get().toString())
        .isEqualTo("OUTCOME_OK");
    assertThat(resultPart.codeExecutionResult().get().output()).hasValue("");
  }

  @Test
  public void runAsync_aggregatesPartialEvents_emptyFinalEvent() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Hello ", true, false), agentCard);
          consumer.accept(createPartialEvent("World!", true, false), agentCard);
          Task task =
              new Task.Builder()
                  .id("task-1")
                  .contextId("context-1")
                  .status(new TaskStatus(TaskState.COMPLETED))
                  .build();
          consumer.accept(new TaskEvent(task), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertText(events.get(0), "Hello ");
    assertNotAggregated(events.get(0));
    assertText(events.get(1), "World!");
    assertNotAggregated(events.get(1));
    Event finalEvent = events.get(2);
    assertText(finalEvent, "Hello World!");
    assertAggregated(finalEvent);
  }

  @Test
  public void runAsync_aggregatesPartialButNotNonPartialEvents() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("1", true, false), agentCard);
          consumer.accept(createPartialEvent("2", true, false), agentCard);
          consumer.accept(createPartialEvent("3", false, false), agentCard);
          consumer.accept(createPartialEvent("4", true, false), agentCard);
          consumer.accept(createFinalEvent("5"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(6);
    assertText(events.get(0), "1");
    assertRequestMetadata(events.get(0));
    assertResponseMetadata(events.get(0));
    assertNotAggregated(events.get(0));
    assertText(events.get(1), "2");
    assertRequestMetadata(events.get(1));
    assertResponseMetadata(events.get(1));
    assertNotAggregated(events.get(1));
    assertText(events.get(2), "3");
    assertRequestMetadata(events.get(2));
    assertResponseMetadata(events.get(2));
    assertNotAggregated(events.get(2));
    assertText(events.get(3), "4");
    assertRequestMetadata(events.get(3));
    assertResponseMetadata(events.get(3));
    assertNotAggregated(events.get(3));
    assertText(events.get(4), "34");
    assertRequestMetadata(events.get(4));
    // Aggregated events do not carry response metadata
    assertAggregated(events.get(4));
    assertText(events.get(5), "5");
    assertRequestMetadata(events.get(5));
    assertResponseMetadata(events.get(5));
    assertNotAggregated(events.get(5));
  }

  @Test
  public void runAsync_beforeCallbackCanShortCircuit() {
    Content shortCircuitContent =
        Content.builder()
            .role("model")
            .parts(ImmutableList.of(Part.builder().text("short circuit").build()))
            .build();
    RemoteA2AAgent agent =
        getAgentBuilder()
            .beforeAgentCallback(
                ImmutableList.<Callbacks.BeforeAgentCallback>of(
                    (CallbackContext unused) -> Maybe.just(shortCircuitContent)))
            .build();

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertText(events.get(0), "short circuit");
    verifyNoInteractions(mockClient);
  }

  @Test
  public void runAsync_handlesClientError() {
    RemoteA2AAgent agent = createAgent();
    mockStreamError(new RuntimeException("Connection failed"));

    agent
        .runAsync(invocationContext)
        .test()
        .awaitDone(5, SECONDS)
        .assertError(RuntimeException.class)
        .assertError(
            e -> e.getCause() != null && e.getCause().getMessage().contains("Connection failed"));
  }

  private ClientEvent createPartialEvent(String text, boolean append, boolean lastChunk) {
    return createTestEvent(new TextPart(text), TaskState.WORKING, append, lastChunk);
  }

  private ClientEvent createPartialFunctionCallEvent(String name, String id) {
    Map<String, Object> data = new HashMap<>();
    data.put("name", name);
    data.put("id", id);
    data.put("args", new HashMap<>());
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("adk_type", "function_call");

    return createTestEvent(new DataPart(data, metadata), TaskState.WORKING, true, false);
  }

  private ClientEvent createPartialCodeEvent(String code, String language) {
    Map<String, Object> data = new HashMap<>();
    data.put("code", code);
    data.put("language", language);
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("adk_type", "executable_code");

    return createTestEvent(new DataPart(data, metadata), TaskState.WORKING, true, false);
  }

  private ClientEvent createPartialCodeResultEvent(String output, String outcome) {
    Map<String, Object> data = new HashMap<>();
    data.put("output", output);
    data.put("outcome", outcome);
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("adk_type", "code_execution_result");

    return createTestEvent(new DataPart(data, metadata), TaskState.WORKING, true, false);
  }

  private ClientEvent createPartialFileEvent(String uri, String mimeType) {
    return createTestEvent(
        new FilePart(new FileWithUri(mimeType, "file", uri)), TaskState.WORKING, true, false);
  }

  private ClientEvent createFinalEvent(String text) {
    return createTestEvent(new TextPart(text), TaskState.COMPLETED, false, false);
  }

  private ClientEvent createTestEvent(
      io.a2a.spec.Part<?> part, TaskState state, boolean append, boolean lastChunk) {
    Artifact artifact =
        new Artifact.Builder().artifactId("artifact-1").parts(ImmutableList.of(part)).build();
    Task task =
        new Task.Builder()
            .id("task-1")
            .contextId("context-1")
            .status(new TaskStatus(state))
            .artifacts(ImmutableList.of(artifact))
            .build();

    if (state == TaskState.COMPLETED && !append && !lastChunk) {
      return new TaskEvent(task);
    }

    TaskArtifactUpdateEvent updateEvent =
        new TaskArtifactUpdateEvent.Builder()
            .lastChunk(lastChunk)
            .append(append)
            .contextId("context-1")
            .artifact(artifact)
            .taskId("task-id-1")
            .build();
    return new TaskUpdateEvent(task, updateEvent);
  }

  private RemoteA2AAgent.Builder getAgentBuilder() {
    return RemoteA2AAgent.builder().name("remote-agent").a2aClient(mockClient).agentCard(agentCard);
  }

  private RemoteA2AAgent createAgent() {
    return getAgentBuilder().build();
  }

  @SuppressWarnings("unchecked") // cast for Mockito
  private void mockStreamResponse(Consumer<BiConsumer<ClientEvent, AgentCard>> responseProducer) {
    doAnswer(
            invocation -> {
              List<BiConsumer<ClientEvent, AgentCard>> consumers = invocation.getArgument(1);
              BiConsumer<ClientEvent, AgentCard> consumer = consumers.get(0);
              responseProducer.accept(consumer);
              return null;
            })
        .when(mockClient)
        .sendMessage(any(Message.class), any(List.class), any(Consumer.class), any());
  }

  @SuppressWarnings("unchecked") // cast for Mockito
  private void mockStreamError(Throwable error) {
    doAnswer(
            invocation -> {
              Consumer<Throwable> errorConsumer = invocation.getArgument(2);
              errorConsumer.accept(error);
              return null;
            })
        .when(mockClient)
        .sendMessage(any(Message.class), any(List.class), any(Consumer.class), any());
  }

  private void assertText(Event event, String expectedText) {
    assertText(event, 0, expectedText);
  }

  private void assertText(Event event, int partIndex, String expectedText) {
    assertThat(event.content().get().parts().get().get(partIndex).text().orElse(""))
        .isEqualTo(expectedText);
  }

  private void assertThought(Event event, boolean expected) {
    assertThat(event.content().get().parts().get().get(0).thought().orElse(false))
        .isEqualTo(expected);
  }

  private void assertAggregated(Event event) {
    assertThat(event.customMetadata()).isPresent();
    List<CustomMetadata> metadata = event.customMetadata().get();

    boolean hasAggregated =
        metadata.stream()
            .anyMatch(
                m ->
                    A2AMetadata.Key.AGGREGATED.getValue().equals(m.key().orElse(""))
                        && Objects.equals(m.stringValue().orElse(""), "true"));
    boolean hasRequest =
        metadata.stream()
            .anyMatch(m -> A2AMetadata.Key.REQUEST.getValue().equals(m.key().orElse("")));

    assertThat(hasAggregated).isTrue();
    assertThat(hasRequest).isTrue();
  }

  private void assertNotAggregated(Event event) {
    if (event.customMetadata().isEmpty()) {
      return;
    }
    List<CustomMetadata> metadata = event.customMetadata().get();
    boolean hasAggregated =
        metadata.stream()
            .anyMatch(
                m ->
                    A2AMetadata.Key.AGGREGATED.getValue().equals(m.key().orElse(""))
                        && Objects.equals(m.stringValue().orElse(""), "true"));
    assertThat(hasAggregated).isFalse();
  }

  private void assertRequestMetadata(Event event) {
    assertThat(event.customMetadata()).isPresent();
    List<CustomMetadata> metadata = event.customMetadata().get();
    boolean hasRequest =
        metadata.stream()
            .anyMatch(m -> A2AMetadata.Key.REQUEST.getValue().equals(m.key().orElse("")));
    assertThat(hasRequest).isTrue();
  }

  private void assertResponseMetadata(Event event) {
    assertThat(event.customMetadata()).isPresent();
    List<CustomMetadata> metadata = event.customMetadata().get();
    boolean hasResponse =
        metadata.stream()
            .anyMatch(m -> A2AMetadata.Key.RESPONSE.getValue().equals(m.key().orElse("")));
    assertThat(hasResponse).isTrue();
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
