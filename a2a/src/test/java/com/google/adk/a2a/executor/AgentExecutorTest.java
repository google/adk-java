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
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class AgentExecutorTest {

  /** A throwable message shaped like the ones that leak host detail. */
  private static final String SECRET_ERROR =
      "Runner error: /home/victim/.config/adk/credentials.json (No such file)";

  private static final String APP_NAME = "test_app";
  private static final String CONTEXT_ID = "context-1";
  private static final String CONTEXT_USER_ID = "A2A_USER_" + CONTEXT_ID;

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
        ctx -> Single.error(new RuntimeException(SECRET_ERROR));

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
    assertThat(statusEvent.getStatus().state()).isEqualTo(TaskState.FAILED);
    assertThat(statusEvent.getStatus().message().getParts().get(0)).isInstanceOf(TextPart.class);
    TextPart textPart = (TextPart) statusEvent.getStatus().message().getParts().get(0);
    // The remote peer gets a correlation id for the logged throwable, not its
    // message -- see AgentExecutor#failedMessage.
    assertThat(textPart.getText()).startsWith("Agent execution failed. (error_id: ");
    assertThat(textPart.getText()).doesNotContain(SECRET_ERROR);
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
    testAgent.setEventsToEmit(Flowable.error(new RuntimeException(SECRET_ERROR)));
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
    // A runner failure is reported to the peer as a correlation id only: the
    // throwable's message names host paths and is for the server log. The id is
    // 12 hex characters, the shape adk-python emits.
    assertThat(textPart.getText())
        .matches("Agent execution failed\\. \\(error_id: [0-9a-f]{12}\\)");
    assertThat(textPart.getText()).doesNotContain(SECRET_ERROR);
    assertThat(textPart.getText()).doesNotContain("/home/victim");
  }

  @Test
  public void failureText_withoutDebug_carriesOnlyTheCorrelationId() {
    String text = AgentExecutor.failureText(new RuntimeException(SECRET_ERROR), "abc-123", false);

    assertThat(text).isEqualTo("Agent execution failed. (error_id: abc-123)");
  }

  @Test
  public void failureText_withDebug_carriesTheThrowableDetail() {
    // ADK_DEBUG_ERRORS=1 is the documented opt-in for local debugging.
    String text = AgentExecutor.failureText(new RuntimeException(SECRET_ERROR), "abc-123", true);

    assertThat(text).startsWith("Agent execution failed. (error_id: abc-123): ");
    assertThat(text).contains("java.lang.RuntimeException");
    assertThat(text).contains(SECRET_ERROR);
  }

  @Test
  public void debugErrorsEnabled_recognizesTheDocumentedValues() {
    assertThat(AgentExecutor.debugErrorsEnabled("1")).isTrue();
    assertThat(AgentExecutor.debugErrorsEnabled("true")).isTrue();
    assertThat(AgentExecutor.debugErrorsEnabled("TRUE")).isTrue();
    assertThat(AgentExecutor.debugErrorsEnabled("True")).isTrue();
  }

  @Test
  public void debugErrorsEnabled_defaultsToOff() {
    // Anything else leaves the redaction in place, including an unset variable.
    assertThat(AgentExecutor.debugErrorsEnabled(null)).isFalse();
    assertThat(AgentExecutor.debugErrorsEnabled("")).isFalse();
    assertThat(AgentExecutor.debugErrorsEnabled("0")).isFalse();
    assertThat(AgentExecutor.debugErrorsEnabled("false")).isFalse();
    assertThat(AgentExecutor.debugErrorsEnabled("yes")).isFalse();
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

  @Test
  public void execute_propagatesRequestMetadataIntoRunConfig() {
    testAgent.setEventsToEmit(Flowable.empty());
    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    Message message =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("trigger")))
            .build();
    MessageSendParams params =
        new MessageSendParams.Builder()
            .message(message)
            .metadata(ImmutableMap.of("key", "value"))
            .build();
    RequestContext ctx = mock(RequestContext.class);
    when(ctx.getMessage()).thenReturn(message);
    when(ctx.getTaskId()).thenReturn("task-1");
    when(ctx.getContextId()).thenReturn("ctx-1");
    when(ctx.getParams()).thenReturn(params);

    executor.execute(ctx, eventQueue);

    // The runner passes the enriched run config down to the agent's invocation context.
    RunConfig runConfig = testAgent.lastInvocationContext.runConfig();
    assertThat(runConfig.customMetadata())
        .containsEntry("a2a_metadata", ImmutableMap.of("key", "value"));
  }

  @Test
  public void execute_withoutRequestMetadata_leavesRunConfigCustomMetadataEmpty() {
    testAgent.setEventsToEmit(Flowable.empty());
    AgentExecutor executor =
        new AgentExecutor.Builder()
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .build();

    // createRequestContext() does not stub getParams(), mirroring a request with no metadata.
    RequestContext ctx = createRequestContext();

    executor.execute(ctx, eventQueue);

    RunConfig runConfig = testAgent.lastInvocationContext.runConfig();
    assertThat(runConfig.customMetadata()).doesNotContainKey("a2a_metadata");
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

  /**
   * Builds the real SDK {@link RequestContext} from {@link MessageSendParams}, the way a JSON-RPC
   * transport does: the context id is read off the message, and a UUID is generated when it is
   * absent. A2A generates a task id per request, so the turns of one conversation differ there.
   */
  private static RequestContext requestContext(String text, String contextId) {
    Message message =
        new Message.Builder()
            .role(Message.Role.USER)
            .contextId(contextId)
            .parts(ImmutableList.of(new TextPart(text)))
            .build();
    return new RequestContext.Builder()
        .setParams(new MessageSendParams.Builder().message(message).build())
        .build();
  }

  private AgentExecutor executorWith(BaseSessionService sessionService) {
    return new AgentExecutor.Builder()
        .app(App.builder().name(APP_NAME).rootAgent(testAgent).build())
        .sessionService(sessionService)
        .artifactService(new InMemoryArtifactService())
        .agentExecutorConfig(AgentExecutorConfig.builder().build())
        .build();
  }

  /** Makes the next turn emit one model event; a fresh id keeps turns distinguishable. */
  private void emitOneResponse() {
    Event response =
        Event.builder()
            .id("event-" + UUID.randomUUID())
            .author("test_agent")
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(Part.builder().text("ok").build()))
                    .build())
            .build();
    testAgent.setEventsToEmit(Flowable.just(response));
  }

  private TaskState lastFinalState() {
    return enqueuedEvents.stream()
        .filter(e -> e instanceof TaskStatusUpdateEvent)
        .map(e -> (TaskStatusUpdateEvent) e)
        .filter(TaskStatusUpdateEvent::isFinal)
        .map(e -> e.getStatus().state())
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  @Test
  public void execute_secondTurnWithSameContextId_reusesTheFirstTurnSession() {
    AgentExecutor executor = executorWith(new InMemorySessionService());

    emitOneResponse();
    executor.execute(requestContext("turn one", CONTEXT_ID), eventQueue);
    String firstTurnSessionId = testAgent.lastInvocationContext.session().id();

    emitOneResponse();
    executor.execute(requestContext("turn two", CONTEXT_ID), eventQueue);

    assertThat(testAgent.lastInvocationContext.session().id()).isEqualTo(firstTurnSessionId);
  }

  @Test
  public void execute_secondTurnWithSameContextId_seesTheFirstTurnHistory() {
    AgentExecutor executor = executorWith(new InMemorySessionService());

    emitOneResponse();
    executor.execute(requestContext("turn one", CONTEXT_ID), eventQueue);
    int firstTurnEventCount = testAgent.lastInvocationContext.session().immutableEvents().size();

    emitOneResponse();
    executor.execute(requestContext("turn two", CONTEXT_ID), eventQueue);

    assertThat(testAgent.lastInvocationContext.session().immutableEvents().size())
        .isGreaterThan(firstTurnEventCount);
  }

  @Test
  public void execute_firstTurn_storesTheSessionUnderTheContextId() {
    InMemorySessionService sessionService = new InMemorySessionService();
    AgentExecutor executor = executorWith(sessionService);

    emitOneResponse();
    executor.execute(requestContext("turn one", CONTEXT_ID), eventQueue);

    assertThat(
            sessionService
                .getSession(APP_NAME, CONTEXT_USER_ID, CONTEXT_ID, Optional.empty())
                .map(Session::id)
                .defaultIfEmpty("")
                .blockingGet())
        .isEqualTo(CONTEXT_ID);
  }

  @Test
  public void execute_twoTurnsWithoutAContextId_getSeparateSessions() {
    AgentExecutor executor = executorWith(new InMemorySessionService());

    emitOneResponse();
    RequestContext firstCtx = requestContext("turn one", null);
    executor.execute(firstCtx, eventQueue);
    String firstTurnSessionId = testAgent.lastInvocationContext.session().id();

    emitOneResponse();
    RequestContext secondCtx = requestContext("turn two", null);
    executor.execute(secondCtx, eventQueue);

    // Each request got its own generated context id, and that id anchors its session; neither
    // turn sees the other.
    assertThat(firstCtx.getContextId()).isNotNull();
    assertThat(secondCtx.getContextId()).isNotNull();
    assertThat(firstTurnSessionId).isEqualTo(firstCtx.getContextId());
    assertThat(testAgent.lastInvocationContext.session().id()).isEqualTo(secondCtx.getContextId());
    assertThat(testAgent.lastInvocationContext.session().id()).isNotEqualTo(firstTurnSessionId);
  }

  @Test
  public void execute_secondTurnEchoingTheGeneratedContextId_continuesTheSameSession() {
    AgentExecutor executor = executorWith(new InMemorySessionService());

    emitOneResponse();
    RequestContext firstCtx = requestContext("remember the code word", null);
    executor.execute(firstCtx, eventQueue);
    String generatedContextId = firstCtx.getContextId();
    int firstTurnEventCount = testAgent.lastInvocationContext.session().immutableEvents().size();

    // The typical handshake: turn 2 echoes back the context id turn 1 was answered with.
    emitOneResponse();
    executor.execute(requestContext("what was the code word?", generatedContextId), eventQueue);

    assertThat(testAgent.lastInvocationContext.session().id()).isEqualTo(generatedContextId);
    assertThat(testAgent.lastInvocationContext.session().immutableEvents().size())
        .isGreaterThan(firstTurnEventCount);
  }

  /**
   * Two first turns with the same new context id can both miss {@code getSession}. A service that
   * rejects duplicate ids fails the loser; the turn must recover by re-reading the session the
   * winner created, instead of handing the peer a failed task.
   */
  @Test
  public void execute_losingTheRaceToAnotherTurn_fallsBackToTheSessionItCreated() {
    Session winner = Session.builder(CONTEXT_ID).appName(APP_NAME).userId(CONTEXT_USER_ID).build();
    BaseSessionService racing = mock(BaseSessionService.class);
    // The first read misses, so the executor tries to create and loses the race. Every read after
    // that -- the fallback's, and the one Runner does before running -- finds the winner's session.
    when(racing.getSession(APP_NAME, CONTEXT_USER_ID, CONTEXT_ID, Optional.empty()))
        .thenReturn(Maybe.empty())
        .thenReturn(Maybe.just(winner));
    when(racing.createSession(APP_NAME, CONTEXT_USER_ID, (Map<String, Object>) null, CONTEXT_ID))
        .thenReturn(Single.error(new SessionException(SessionException.SESSION_ALREADY_EXISTS)));
    when(racing.appendEvent(any(), any()))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(1)));

    emitOneResponse();
    executorWith(racing).execute(requestContext("turn one", CONTEXT_ID), eventQueue);

    assertThat(testAgent.lastInvocationContext.session().id()).isEqualTo(CONTEXT_ID);
  }

  /** A creation failure that is not "already exists" must still surface. */
  @Test
  public void execute_failingForAnotherReason_failsTheTurn() {
    BaseSessionService failing = mock(BaseSessionService.class);
    when(failing.getSession(APP_NAME, CONTEXT_USER_ID, CONTEXT_ID, Optional.empty()))
        .thenReturn(Maybe.empty());
    when(failing.createSession(APP_NAME, CONTEXT_USER_ID, (Map<String, Object>) null, CONTEXT_ID))
        .thenReturn(Single.error(new SessionException("backend unavailable")));

    executorWith(failing).execute(requestContext("turn one", CONTEXT_ID), eventQueue);

    assertThat(testAgent.lastInvocationContext).isNull();
    assertThat(lastFinalState()).isEqualTo(TaskState.FAILED);
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
    private volatile InvocationContext lastInvocationContext;

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
      this.lastInvocationContext = invocationContext;
      return eventsToEmit;
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return eventsToEmit;
    }
  }
}
