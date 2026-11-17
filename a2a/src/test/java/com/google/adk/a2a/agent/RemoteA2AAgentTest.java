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

package com.google.adk.a2a.agent;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.adk.a2a.common.A2AClientError;
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
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
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
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class RemoteA2AAgentTest {

  private static final ImmutableMap<String, Object> AUTH_CONFIG =
      ImmutableMap.of("authScheme", "oauth2", "rawAuthCredential", "secret");

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
  public void createAgent_streaming_false_returnsNonStreamingAgent() {
    // With streaming false, the agent should not stream even if the AgentCard supports streaming.
    RemoteA2AAgent agent = getAgentBuilder().streaming(false).build();
    assertThat(agent.isStreaming()).isFalse();
  }

  @Test
  public void createAgent_streaming_true_returnsStreamingAgent() {
    // With streaming true, the agent should support streaming if the AgentCard supports streaming.
    RemoteA2AAgent agent = getAgentBuilder().streaming(true).build();
    assertThat(agent.isStreaming()).isTrue();
  }

  @Test
  public void runAsync_withUnconvertiblePartInStream_skipsItAndCompletes() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Hello", true, false), agentCard);
          TaskStatus working =
              new TaskStatus(
                  TaskState.WORKING,
                  new Message.Builder()
                      .role(Message.Role.AGENT)
                      .parts(
                          ImmutableList.of(
                              new FilePart(new FileWithBytes("text/plain", "bad.txt", "!!!"))))
                      .build(),
                  null);
          consumer.accept(
              new TaskUpdateEvent(
                  new Task.Builder().id("task-1").contextId("context-1").status(working).build(),
                  new TaskStatusUpdateEvent.Builder()
                      .taskId("task-1")
                      .contextId("context-1")
                      .status(working)
                      .isFinal(false)
                      .build()),
              agentCard);
          consumer.accept(createFinalEvent("Done"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertText(events.get(0), "Hello");
    assertText(events.get(1), "Hello");
    assertAggregated(events.get(1));
    assertText(events.get(2), "Done");
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
  @SuppressWarnings("unchecked") // cast for Mockito
  public void runAsync_usesInvocationProvidersForMessageIdAndEvents() {
    RemoteA2AAgent agent = createAgent();
    AtomicInteger counter = new AtomicInteger();
    InvocationContext providerContext =
        invocationContext.toBuilder()
            .instantSource(() -> Instant.ofEpochMilli(1234L))
            .uuidProvider(() -> String.format("uuid-%04d", counter.getAndIncrement()))
            .build();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Hello ", true, false), agentCard);
          consumer.accept(createPartialEvent("World!", true, false), agentCard);
          consumer.accept(createFinalEvent("Final artifact content"), agentCard);
        });

    List<Event> events = agent.runAsync(providerContext).toList().blockingGet();

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockClient)
        .sendMessage(messageCaptor.capture(), any(List.class), any(Consumer.class), any());
    // The outbound A2A message id is the first id drawn from the invocation's provider: building
    // the request parts draws none.
    assertThat(messageCaptor.getValue().getMessageId()).isEqualTo("uuid-0000");
    assertThat(events).hasSize(4);
    assertThat(events.stream().map(Event::id).distinct()).hasSize(4);
    for (Event event : events) {
      assertThat(event.id()).startsWith("uuid-");
      assertThat(event.timestamp()).isEqualTo(1234L);
    }
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
  public void runAsync_whenConversionThrowsOnCallingThread_reportsError() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(consumer -> consumer.accept(unconvertibleEvent(), agentCard));

    agent
        .runAsync(invocationContext)
        .test()
        .awaitDone(5, SECONDS)
        .assertError(A2AClientError.class)
        .assertError(e -> e.getCause() instanceof IllegalArgumentException);
  }

  @Test
  public void runAsync_whenConversionThrowsOnCallingThread_doesNotSignalTwice() {
    List<Throwable> undeliverable = Collections.synchronizedList(new ArrayList<>());
    io.reactivex.rxjava3.functions.Consumer<? super Throwable> previousHandler =
        RxJavaPlugins.getErrorHandler();
    RxJavaPlugins.setErrorHandler(undeliverable::add);
    try {
      RemoteA2AAgent agent = createAgent();
      mockStreamResponse(consumer -> consumer.accept(unconvertibleEvent(), agentCard));

      agent
          .runAsync(invocationContext)
          .test()
          .awaitDone(5, SECONDS)
          .assertError(A2AClientError.class);
    } finally {
      // Process-global; reset before asserting so a failure cannot leak it into the rest of the
      // suite.
      RxJavaPlugins.setErrorHandler(previousHandler);
    }
    // Rethrowing on the subscribe thread would land here via FlowableCreate.
    assertThat(undeliverable).isEmpty();
  }

  @Test
  public void runAsync_whenConversionThrowsOnSubscribeThreadAfterSendMessage_rethrows() {
    RemoteA2AAgent agent = createAgent();
    AtomicReference<BiConsumer<ClientEvent, AgentCard>> captured = new AtomicReference<>();
    mockStreamResponse(captured::set); // capture, do not invoke

    TestSubscriber<Event> subscriber = agent.runAsync(invocationContext).test();

    // Subscribe thread, but sendMessage has already returned: the transport still needs the throw.
    assertThrows(
        IllegalArgumentException.class,
        () -> captured.get().accept(unconvertibleEvent(), agentCard));
    subscriber.assertError(A2AClientError.class);
  }

  @Test
  public void runAsync_whenConversionThrowsOnTransportThreadDuringSendMessage_rethrows() {
    RemoteA2AAgent agent = createAgent();
    AtomicReference<Throwable> escaped = new AtomicReference<>();
    mockStreamResponse(
        consumer -> {
          Thread thread = new Thread(() -> consumer.accept(unconvertibleEvent(), agentCard));
          thread.setName("a2a-fake-transport");
          thread.setUncaughtExceptionHandler((t, e) -> escaped.set(e));
          thread.start();
          try {
            thread.join(); // deliver while sendMessage is still on the stack
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    agent
        .runAsync(invocationContext)
        .test()
        .awaitDone(5, SECONDS)
        .assertError(A2AClientError.class);
    assertThat(escaped.get()).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void runAsync_whenConversionThrowsOnTransportThread_failsStream()
      throws InterruptedException {
    RemoteA2AAgent agent = createAgent();
    CountDownLatch delivered = new CountDownLatch(1);
    // Released once subscribe has returned, so delivery is deterministically *after* sendMessage.
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> deliveryThread = new AtomicReference<>();
    AtomicReference<Throwable> escaped = new AtomicReference<>();
    mockStreamResponse(
        consumer -> {
          Thread thread =
              new Thread(
                  () -> {
                    try {
                      release.await();
                      consumer.accept(unconvertibleEvent(), agentCard);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } finally {
                      delivered.countDown();
                    }
                  });
          thread.setName("a2a-fake-transport");
          thread.setUncaughtExceptionHandler((t, e) -> escaped.set(e));
          thread.start();
          deliveryThread.set(thread);
        });

    TestSubscriber<Event> subscriber = agent.runAsync(invocationContext).test();
    release.countDown();
    assertThat(delivered.await(5, SECONDS)).isTrue();

    subscriber
        .awaitDone(5, SECONDS)
        .assertError(A2AClientError.class)
        .assertError(e -> e.getCause() instanceof IllegalArgumentException);
    deliveryThread.get().join();
    // The failure must also leave the handler, so the transport can tear the connection down.
    assertThat(escaped.get()).isInstanceOf(IllegalArgumentException.class);
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
    assertThat(message.getParts()).hasSize(4);
    assertThat(((TextPart) message.getParts().get(0)).getText()).isEqualTo("hello");
    assertThat(((TextPart) message.getParts().get(1)).getText()).isEqualTo("For context:");
    assertThat(((TextPart) message.getParts().get(2)).getText()).isEqualTo("[model] said: hi");
    assertThat(((TextPart) message.getParts().get(3)).getText()).isEqualTo("how are you?");
  }

  @Test
  public void runAsync_answeringRemoteCall_sendsAnswerInItsTask() {
    // Recorded under the keys ResponseConverter writes.
    Message message =
        sendAnswer(
            functionCall("fn", "call-1"),
            ImmutableList.of(
                metadata("a2a:task_id", "task-1"), metadata("a2a:context_id", "context-1")),
            functionResponse("fn", "call-1", ImmutableMap.of("result", "ok")));

    assertThat(message.getTaskId()).isEqualTo("task-1");
    assertThat(message.getContextId()).isEqualTo("context-1");
    DataPart answer = (DataPart) Iterables.getOnlyElement(message.getParts());
    assertThat(answer.getMetadata()).containsEntry("adk_type", "function_response");
    assertThat(answer.getData()).containsEntry("id", "call-1");
  }

  @Test
  public void runAsync_answeringRemoteCallWithoutIds_sendsNoIds() {
    Message message =
        sendAnswer(
            functionCall("fn", "call-1"),
            ImmutableList.of(),
            functionResponse("fn", "call-1", ImmutableMap.of("result", "ok")));

    assertThat(message.getTaskId()).isNull();
    assertThat(message.getContextId()).isNull();
  }

  @Test
  public void runAsync_answerWithTextBesideResponse_sendsOnlyResponse() {
    Message message =
        sendAnswer(
            functionCall("fn", "call-1"),
            ImmutableList.of(),
            Part.builder().text("Here you go").build(),
            functionResponse("fn", "call-1", ImmutableMap.of("result", "ok")));

    DataPart answer = (DataPart) Iterables.getOnlyElement(message.getParts());
    assertThat(answer.getData()).containsEntry("id", "call-1");
  }

  @Test
  public void runAsync_answerWithCredentialBesideResponse_sendsOnlyResponse() {
    Message message =
        sendAnswer(
            functionCall("fn", "call-1"),
            ImmutableList.of(metadata("a2a:task_id", "task-1")),
            functionResponse("fn", "call-1", ImmutableMap.of("result", "ok")),
            functionResponse("adk_request_credential", "cred-1", ImmutableMap.of("token", "t")),
            functionResponse("other", "x-1", ImmutableMap.of("result", AUTH_CONFIG)));

    assertThat(message.getTaskId()).isEqualTo("task-1");
    DataPart answer = (DataPart) Iterables.getOnlyElement(message.getParts());
    assertThat(answer.getData()).containsEntry("id", "call-1");
  }

  @Test
  public void runAsync_answerWithCredentialResponseFirst_resumesItsTask() {
    Message message =
        sendAnswer(
            functionCall("fn", "call-1"),
            ImmutableList.of(metadata("a2a:task_id", "task-1")),
            functionResponse("adk_request_credential", "cred-1", ImmutableMap.of("token", "t")),
            functionResponse("fn", "call-1", ImmutableMap.of("result", "ok")));

    assertThat(message.getTaskId()).isEqualTo("task-1");
    DataPart answer = (DataPart) Iterables.getOnlyElement(message.getParts());
    assertThat(answer.getData()).containsEntry("id", "call-1");
  }

  @Test
  public void runAsync_credentialOnlyAnswer_emitsEmptyEventWithoutCalling() {
    RemoteA2AAgent agent = createAgent();
    InvocationContext context =
        answerContext(
            credentialCall(),
            ImmutableList.of(),
            functionResponse("adk_request_credential", "cred-1", AUTH_CONFIG));

    assertEmitsEmptyEventWithoutCalling(agent, context);
  }

  @Test
  public void runAsync_credentialOnlyAnswerToRelayedCall_sendsHistoryWithoutCredentials() {
    // Another agent relayed the call, so the history since this agent's last turn holds more.
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            event("other_agent", credentialCall()),
            event("user", functionResponse("adk_request_credential", "cred-1", AUTH_CONFIG)));

    Message message = sendAndCapture(context);

    assertThat(message.getTaskId()).isNull();
    assertThat(((TextPart) Iterables.getOnlyElement(message.getParts())).getText())
        .isEqualTo("hello");
  }

  @Test
  public void runAsync_historyWithCredentials_sendsItWithoutThem() {
    InvocationContext context =
        historyContext(
            event(
                "other_agent",
                functionResponse("lookup", "x-1", ImmutableMap.of("result", AUTH_CONFIG)),
                Part.builder().text("done").build()),
            event("user", Part.builder().text("next question").build()));

    Message message = sendAndCapture(context);

    assertThat(message.getParts().stream().map(part -> ((TextPart) part).getText()))
        .containsExactly("For context:", "[other_agent] said: done", "next question")
        .inOrder();
  }

  @Test
  public void runAsync_nothingSinceLastTurn_emitsEmptyEventWithoutCalling() {
    RemoteA2AAgent agent = createAgent();
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            event("remote-agent", Part.builder().text("hi").build()));

    assertEmitsEmptyEventWithoutCalling(agent, context);
  }

  /** Asserts that the agent ends the turn with one empty event and makes no remote call. */
  @SuppressWarnings("unchecked") // cast for Mockito
  private void assertEmitsEmptyEventWithoutCalling(
      RemoteA2AAgent agent, InvocationContext context) {
    List<Event> events = agent.runAsync(context).toList().blockingGet();

    Event event = Iterables.getOnlyElement(events);
    assertThat(event.author()).isEqualTo("remote-agent");
    assertThat(event.content().flatMap(Content::parts)).isEmpty();
    verify(mockClient, never())
        .sendMessage(any(Message.class), any(List.class), any(Consumer.class), any());
  }

  @Test
  public void runAsync_afterRemoteResponse_sendsItsContextId() {
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            remoteResponse("context-1", Part.builder().text("hi").build()),
            event("user", Part.builder().text("how are you?").build()));

    Message message = sendAndCapture(context);

    assertThat(message.getContextId()).isEqualTo("context-1");
    assertThat(message.getTaskId()).isNull();
  }

  @Test
  public void runAsync_afterLaterEventWithoutResponse_sendsLastResponsesContextId() {
    // An event this agent authored without a recorded response, such as a callback's.
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            remoteResponse("context-1", Part.builder().text("hi").build()),
            event("remote-agent", Part.builder().text("noted").build()),
            event("user", Part.builder().text("how are you?").build()));

    Message message = sendAndCapture(context);

    assertThat(message.getContextId()).isEqualTo("context-1");
  }

  @Test
  public void runAsync_afterAnotherAgentsResponse_sendsOwnContextId() {
    Event otherAgentsResponse =
        remoteResponse("context-2", Part.builder().text("bye").build()).toBuilder()
            .author("other-agent")
            .build();
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            remoteResponse("context-1", Part.builder().text("hi").build()),
            otherAgentsResponse,
            event("user", Part.builder().text("how are you?").build()));

    Message message = sendAndCapture(context);

    assertThat(message.getContextId()).isEqualTo("context-1");
  }

  @Test
  public void runAsync_afterEventWithContextButNoResponse_sendsItsContextId() {
    // When a response fails to serialize, its event keeps the context ID but no a2a:response.
    Event unrecordedResponse =
        event("remote-agent", Part.builder().text("hi").build()).toBuilder()
            .customMetadata(ImmutableList.of(metadata("a2a:context_id", "context-1")))
            .build();
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            unrecordedResponse,
            event("user", Part.builder().text("how are you?").build()));

    Message message = sendAndCapture(context);

    assertThat(message.getContextId()).isEqualTo("context-1");
  }

  @Test
  @SuppressWarnings("unchecked") // cast for Mockito
  public void runAsync_secondTurnAfterTaskReply_sendsItsContextId() {
    RemoteA2AAgent agent = createAgent();
    InvocationContext firstTurn =
        historyContext(event("user", Part.builder().text("hello").build()));
    mockStreamResponse(consumer -> consumer.accept(createFinalEvent("hi"), agentCard));
    List<Event> replies = agent.runAsync(firstTurn).toList().blockingGet();

    ImmutableList<Event> history =
        ImmutableList.<Event>builder()
            .addAll(firstTurn.session().immutableEvents())
            .addAll(replies)
            .add(event("user", Part.builder().text("how are you?").build()))
            .build();
    var unused =
        agent.runAsync(historyContext(history.toArray(new Event[0]))).toList().blockingGet();

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockClient, times(2))
        .sendMessage(messageCaptor.capture(), any(List.class), any(Consumer.class), any());
    assertThat(messageCaptor.getAllValues().get(1).getContextId()).isEqualTo("context-1");
  }

  @Test
  @SuppressWarnings("unchecked") // cast for Mockito
  public void runAsync_secondTurnAfterStreamedReply_sendsItsContextId() {
    RemoteA2AAgent agent = createAgent();
    InvocationContext firstTurn =
        historyContext(event("user", Part.builder().text("hello").build()));
    mockStreamResponse(consumer -> {
      consumer.accept(createPartialEvent("Hello ", true, false), agentCard);
      consumer.accept(createPartialEvent("World!", true, false), agentCard);
      consumer.accept(createFinalEvent("Done"), agentCard);
    });
    List<Event> replies = agent.runAsync(firstTurn).toList().blockingGet();

    ImmutableList<Event> history =
        ImmutableList.<Event>builder()
            .addAll(firstTurn.session().immutableEvents())
            .addAll(replies)
            .add(event("user", Part.builder().text("how are you?").build()))
            .build();
    var unused =
        agent.runAsync(historyContext(history.toArray(new Event[0]))).toList().blockingGet();

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockClient, times(2))
        .sendMessage(messageCaptor.capture(), any(List.class), any(Consumer.class), any());
    assertThat(messageCaptor.getAllValues().get(1).getContextId()).isEqualTo("context-1");
  }

  @Test
  public void runAsync_afterResponseWithoutContext_sendsNoContextId() {
    InvocationContext context =
        historyContext(
            event("user", Part.builder().text("hello").build()),
            remoteResponse(null, Part.builder().text("hi").build()),
            event("user", Part.builder().text("how are you?").build()));

    Message message = sendAndCapture(context);

    assertThat(message.getContextId()).isNull();
  }

  @Test
  public void runAsync_firstTurn_sendsNoContextId() {
    Message message =
        sendAndCapture(historyContext(event("user", Part.builder().text("hello").build())));

    assertThat(message.getContextId()).isNull();
  }

  /** Runs the agent in {@code context} and returns the message sent. */
  @SuppressWarnings("unchecked") // cast for Mockito
  private Message sendAndCapture(InvocationContext context) {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(consumer -> consumer.accept(createFinalEvent("ok"), agentCard));

    var unused = agent.runAsync(context).toList().blockingGet();

    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockClient)
        .sendMessage(messageCaptor.capture(), any(List.class), any(Consumer.class), any());
    return messageCaptor.getValue();
  }

  /** A context whose session holds {@code events}. */
  private InvocationContext historyContext(Event... events) {
    Session session =
        Session.builder("session-5")
            .appName("demo")
            .userId("user")
            .events(ImmutableList.copyOf(events))
            .build();
    return invocationContext.toBuilder().session(session).build();
  }

  /** An event recording a response from the remote agent in {@code contextId}, if not null. */
  private static Event remoteResponse(@Nullable String contextId, Part... parts) {
    ImmutableList.Builder<CustomMetadata> entries = ImmutableList.builder();
    if (contextId != null) {
      entries.add(metadata("a2a:context_id", contextId));
    }
    entries.add(metadata("a2a:response", "{}"));
    return event("remote-agent", parts).toBuilder().customMetadata(entries.build()).build();
  }

  private static Event event(String author, Part... parts) {
    return Event.builder()
        .author(author)
        .content(Content.builder().parts(ImmutableList.copyOf(parts)).build())
        .build();
  }

  /**
   * Runs the agent on a session where the user answers {@code call}, recorded with {@code
   * callMetadata}, with {@code answer}, and returns the message sent.
   */
  private Message sendAnswer(
      Part call, ImmutableList<CustomMetadata> callMetadata, Part... answer) {
    return sendAndCapture(answerContext(call, callMetadata, answer));
  }

  /** A context whose session holds the remote agent's {@code call}, then the user's answer. */
  private InvocationContext answerContext(
      Part call, ImmutableList<CustomMetadata> callMetadata, Part... answer) {
    return historyContext(
        event("remote-agent", call).toBuilder().customMetadata(callMetadata).build(),
        event("user", answer));
  }

  /** A function-call part as converted from the remote's function-call part. */
  private static Part functionCall(String name, String id) {
    return Part.builder()
        .functionCall(FunctionCall.builder().name(name).id(id).build())
        .partMetadata(ImmutableMap.of("adk_type", "function_call"))
        .build();
  }

  /** A credential request whose arguments hold an AuthConfig. */
  private static Part credentialCall() {
    return Part.builder()
        .functionCall(
            FunctionCall.builder()
                .name("adk_request_credential")
                .id("cred-1")
                .args(ImmutableMap.of("authConfig", AUTH_CONFIG))
                .build())
        .build();
  }

  private static CustomMetadata metadata(String key, String value) {
    return CustomMetadata.builder().key(key).stringValue(value).build();
  }

  private static Part functionResponse(String name, String id, Map<String, Object> response) {
    return Part.builder()
        .functionResponse(FunctionResponse.builder().name(name).id(id).response(response).build())
        .build();
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

  private ClientEvent createTerminalStatusUpdateEvent(String message, TaskState state) {
    Task task =
        new Task.Builder()
            .id("task-id-1")
            .contextId("context-1")
            .status(new TaskStatus(state))
            .build();
    TaskStatusUpdateEvent statusUpdate =
        new TaskStatusUpdateEvent.Builder()
            .taskId("task-id-1")
            .contextId("context-1")
            .status(
                new TaskStatus(
                    state,
                    new Message.Builder()
                        .role(Message.Role.AGENT)
                        .parts(ImmutableList.of(new TextPart(message)))
                        .build(),
                    null))
            .build();
    return new TaskUpdateEvent(task, statusUpdate);
  }

  private ClientEvent createFailedEvent(String errorMessage) {
    return createTerminalStatusUpdateEvent(errorMessage, TaskState.FAILED);
  }

  private ClientEvent createCanceledEvent(String message) {
    return createTerminalStatusUpdateEvent(message, TaskState.CANCELED);
  }

  private ClientEvent createMessageEvent(String text) {
    Message message =
        new Message.Builder()
            .messageId("msg-id-1")
            .role(Message.Role.AGENT)
            .parts(new TextPart(text))
            .build();
    return new MessageEvent(message);
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

  @Test
  public void runAsync_terminatesOnFailureTaskState() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Processing data...", true, false), agentCard);
          consumer.accept(createFailedEvent("Internal Server Error"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    // The stream must terminate cleanly and include the final event with error info.
    assertThat(events).hasSize(2);
    assertText(events.get(0), "Processing data...");
    assertText(events.get(1), "Processing data..."); // aggregated/merged
    assertThat(events.get(1).errorMessage().orElse(null)).isEqualTo("Internal Server Error");
  }

  @Test
  public void runAsync_terminatesOnCanceledTaskState() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Processing data...", true, false), agentCard);
          consumer.accept(createCanceledEvent("Execution Canceled"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    // The stream must terminate cleanly and include the final event with cancellation info.
    assertThat(events).hasSize(3);
    assertText(events.get(0), "Processing data...");
    assertText(events.get(1), "Processing data..."); // aggregated
    assertText(events.get(2), "Execution Canceled"); // terminal canceled event
  }

  @Test
  public void runAsync_terminatesOnInputRequiredTaskState() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Processing data...", true, false), agentCard);
          consumer.accept(
              createTerminalStatusUpdateEvent("User Action Needed", TaskState.INPUT_REQUIRED),
              agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertText(events.get(0), "Processing data...");
    assertText(events.get(1), "Processing data..."); // aggregated
    assertText(events.get(2), "User Action Needed");
  }

  @Test
  public void runAsync_terminatesOnRejectedTaskState() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Analyzing request...", true, false), agentCard);
          consumer.accept(
              createTerminalStatusUpdateEvent("Execution Rejected by Policy", TaskState.REJECTED),
              agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(3);
    assertText(events.get(0), "Analyzing request...");
    assertText(events.get(1), "Analyzing request..."); // aggregated
    assertText(events.get(2), "Execution Rejected by Policy");
  }

  @Test
  public void runAsync_doesNotTerminateOnMessageEvent() {
    RemoteA2AAgent agent = createAgent();
    mockStreamResponse(
        consumer -> {
          consumer.accept(createPartialEvent("Processing data...", true, false), agentCard);
          consumer.accept(createMessageEvent("Standard chat update message"), agentCard);
          consumer.accept(createFinalEvent("Done"), agentCard);
        });

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    // The stream must not terminate early on MessageEvent, and run until createFinalEvent.
    assertThat(events).hasSize(4);
    assertText(events.get(0), "Processing data...");
    assertText(events.get(1), "Processing data..."); // aggregated (flushed)
    assertText(events.get(2), "Standard chat update message"); // message event
    assertText(events.get(3), "Done"); // terminal completed event
  }

  private RemoteA2AAgent.Builder getAgentBuilder() {
    return RemoteA2AAgent.builder().name("remote-agent").a2aClient(mockClient).agentCard(agentCard);
  }

  private RemoteA2AAgent createAgent() {
    return getAgentBuilder().streaming(true).build();
  }

  /**
   * An event whose conversion throws. Conversion skips malformed peer parts and metadata, so the
   * failure is injected through a parts list that throws when read.
   */
  private ClientEvent unconvertibleEvent() {
    List<io.a2a.spec.Part<?>> unreadableParts =
        new AbstractList<>() {
          @Override
          public io.a2a.spec.Part<?> get(int index) {
            throw new IllegalArgumentException("injected");
          }

          @Override
          public int size() {
            return 1;
          }
        };
    return new MessageEvent(
        new Message.Builder().role(Message.Role.AGENT).parts(unreadableParts).build());
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
