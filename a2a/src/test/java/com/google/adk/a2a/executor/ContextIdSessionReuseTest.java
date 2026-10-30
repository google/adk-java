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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionException;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.Message;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Verifies that the A2A {@code contextId} anchors the ADK session across turns.
 *
 * <p>A2A defines the context id as the identifier a client sends back on a follow-up message so
 * that the remote agent continues the same conversation. That only holds if the context id and the
 * session id are the same value.
 */
@RunWith(JUnit4.class)
public final class ContextIdSessionReuseTest {

  private static final String APP_NAME = "test_app";
  private static final String CONTEXT_ID = "context-1";
  private static final String USER_ID = "A2A_USER_" + CONTEXT_ID;

  private EventQueue eventQueue;
  private InMemorySessionService sessionService;
  private RecordingAgent agent;
  private AgentExecutor executor;
  private final List<String> finalStates = new ArrayList<>();

  @Before
  public void setUp() {
    eventQueue = mock(EventQueue.class);
    doAnswer(
            invocation -> {
              Object event = invocation.getArgument(0);
              if (event instanceof TaskStatusUpdateEvent statusEvent && statusEvent.isFinal()) {
                finalStates.add(statusEvent.getStatus().state().toString());
              }
              return null;
            })
        .when(eventQueue)
        .enqueueEvent(any());

    sessionService = new InMemorySessionService();
    agent = new RecordingAgent();
    executor =
        new AgentExecutor.Builder()
            .app(App.builder().name(APP_NAME).rootAgent(agent).build())
            .sessionService(sessionService)
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .build();
  }

  @Test
  public void execute_secondTurnWithSameContextId_reusesTheFirstTurnSession() {
    executor.execute(requestContext("task-1"), eventQueue);
    String firstTurnSessionId = agent.lastSessionId;

    executor.execute(requestContext("task-2"), eventQueue);

    assertThat(agent.lastSessionId).isEqualTo(firstTurnSessionId);
  }

  @Test
  public void execute_secondTurnWithSameContextId_seesTheFirstTurnHistory() {
    executor.execute(requestContext("task-1"), eventQueue);
    int firstTurnEventCount = agent.lastEventCount;

    executor.execute(requestContext("task-2"), eventQueue);

    assertThat(agent.lastEventCount).isGreaterThan(firstTurnEventCount);
  }

  @Test
  public void execute_firstTurn_storesTheSessionUnderTheContextId() {
    executor.execute(requestContext("task-1"), eventQueue);

    assertThat(sessionIdForContext()).isEqualTo(CONTEXT_ID);
  }

  /**
   * Two first turns with the same new context id can both miss {@code getSession}. A service that
   * rejects duplicate ids fails the loser; the turn must recover by re-reading the session the
   * winner created, instead of handing the peer a failed task.
   */
  @Test
  public void createLosingTheRaceToAnotherTurn_fallsBackToTheSessionItCreated() {
    Session winner = Session.builder(CONTEXT_ID).appName(APP_NAME).userId(USER_ID).build();
    BaseSessionService racing = mock(BaseSessionService.class);
    // The first read misses, so the executor tries to create and loses the race. Every read after
    // that -- the fallback's, and the one Runner does before running -- finds the winner's session.
    when(racing.getSession(APP_NAME, USER_ID, CONTEXT_ID, Optional.empty()))
        .thenReturn(Maybe.empty())
        .thenReturn(Maybe.just(winner));
    when(racing.createSession(APP_NAME, USER_ID, (Map<String, Object>) null, CONTEXT_ID))
        .thenReturn(Single.error(new SessionException(SessionException.SESSION_ALREADY_EXISTS)));
    when(racing.appendEvent(any(), any()))
        .thenAnswer(invocation -> Single.just(invocation.getArgument(1)));

    RecordingAgent racingAgent = new RecordingAgent();
    AgentExecutor racingExecutor =
        new AgentExecutor.Builder()
            .app(App.builder().name(APP_NAME).rootAgent(racingAgent).build())
            .sessionService(racing)
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .build();

    racingExecutor.execute(requestContext("task-1"), eventQueue);

    assertThat(racingAgent.lastSessionId).isEqualTo(CONTEXT_ID);
  }

  /** A creation failure that is not "already exists" must still surface. */
  @Test
  public void createFailingForAnotherReason_failsTheTurn() {
    BaseSessionService failing = mock(BaseSessionService.class);
    when(failing.getSession(APP_NAME, USER_ID, CONTEXT_ID, Optional.empty()))
        .thenReturn(Maybe.empty());
    when(failing.createSession(APP_NAME, USER_ID, (Map<String, Object>) null, CONTEXT_ID))
        .thenReturn(Single.error(new SessionException("backend unavailable")));

    RecordingAgent failingAgent = new RecordingAgent();
    AgentExecutor failingExecutor =
        new AgentExecutor.Builder()
            .app(App.builder().name(APP_NAME).rootAgent(failingAgent).build())
            .sessionService(failing)
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .build();

    failingExecutor.execute(requestContext("task-1"), eventQueue);

    assertThat(failingAgent.lastSessionId).isNull();
    assertThat(lastFinalState()).isEqualTo("FAILED");
  }

  private String lastFinalState() {
    return finalStates.isEmpty() ? "" : finalStates.get(finalStates.size() - 1);
  }

  private String sessionIdForContext() {
    return sessionService
        .getSession(APP_NAME, USER_ID, CONTEXT_ID, Optional.empty())
        .map(Session::id)
        .defaultIfEmpty("")
        .blockingGet();
  }

  private static RequestContext requestContext(String taskId) {
    Message message =
        new Message.Builder()
            .messageId("msg-" + taskId)
            .taskId(taskId)
            .contextId(CONTEXT_ID)
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("turn")))
            .build();

    RequestContext ctx = mock(RequestContext.class);
    when(ctx.getMessage()).thenReturn(message);
    when(ctx.getTaskId()).thenReturn(taskId);
    when(ctx.getContextId()).thenReturn(CONTEXT_ID);
    when(ctx.getTask()).thenReturn(null);
    when(ctx.getParams()).thenReturn(null);
    return ctx;
  }

  private static final class RecordingAgent extends BaseAgent {
    private final AtomicInteger eventId = new AtomicInteger();
    private volatile String lastSessionId;
    private volatile int lastEventCount;

    RecordingAgent() {
      super("recording_agent", "test", ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      lastSessionId = invocationContext.session().id();
      lastEventCount = invocationContext.session().immutableEvents().size();
      Event event =
          Event.builder()
              .id("event-" + eventId.incrementAndGet())
              .author("recording_agent")
              .content(
                  Content.builder()
                      .role("model")
                      .parts(ImmutableList.of(Part.builder().text("ok").build()))
                      .build())
              .build();
      return Flowable.just(event);
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }
}
