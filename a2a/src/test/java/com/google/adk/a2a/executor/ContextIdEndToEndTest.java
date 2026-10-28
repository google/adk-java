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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.server.ServerCallContext;
import io.a2a.server.events.InMemoryQueueManager;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.server.tasks.InMemoryPushNotificationConfigStore;
import io.a2a.server.tasks.InMemoryTaskStore;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Two {@code message/send} calls through the A2A request handler, the way a JSON-RPC transport
 * drives them, sharing one {@code contextId}.
 *
 * <p>This covers the layer the other tests skip: the request context is built by the A2A SDK from
 * {@link MessageSendParams}, so the context id travels the whole path a real client takes rather
 * than being handed to the executor by the test.
 */
@RunWith(JUnit4.class)
public final class ContextIdEndToEndTest {

  private static final String APP = "test_app";
  private static final String CONTEXT_ID = "ctx-e2e";
  private static final String USER_ID = "A2A_USER_" + CONTEXT_ID;

  private InMemorySessionService sessions;
  private HistoryAgent agent;
  private DefaultRequestHandler handler;

  @Before
  public void setUp() {
    sessions = new InMemorySessionService();
    agent = new HistoryAgent();

    AgentExecutor executor =
        new AgentExecutor.Builder()
            .app(App.builder().name(APP).rootAgent(agent).build())
            .sessionService(sessions)
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .build();

    InMemoryTaskStore taskStore = new InMemoryTaskStore();
    handler =
        DefaultRequestHandler.create(
            executor,
            taskStore,
            new InMemoryQueueManager(taskStore),
            new InMemoryPushNotificationConfigStore(),
            task -> {},
            Runnable::run);
  }

  @Test
  public void twoSendsSharingAContextId_continueTheSameConversation() {
    handler.onMessageSend(send("msg-1", "task-1", "remember the code word", CONTEXT_ID), call());
    int historyAfterFirstTurn = agent.lastHistorySize;

    handler.onMessageSend(send("msg-2", "task-2", "what was the code word?", CONTEXT_ID), call());

    assertThat(agent.lastSessionId).isEqualTo(CONTEXT_ID);
    assertThat(agent.lastHistorySize).isGreaterThan(historyAfterFirstTurn);
    assertThat(storedSession().map(Session::id)).hasValue(CONTEXT_ID);
  }

  @Test
  public void twoSendsWithoutAContextId_getSeparateConversations() {
    handler.onMessageSend(send("msg-1", "task-1", "one", null), call());
    String firstSession = agent.lastSessionId;

    handler.onMessageSend(send("msg-2", "task-2", "two", null), call());

    // The service generated a context id for each request, so neither turn sees the other.
    assertThat(firstSession).isNotEmpty();
    assertThat(agent.lastSessionId).isNotEqualTo(firstSession);
  }

  private Optional<Session> storedSession() {
    return sessions
        .getSession(APP, USER_ID, CONTEXT_ID, Optional.empty())
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .blockingGet();
  }

  private static ServerCallContext call() {
    return new ServerCallContext(null, Map.of(), Set.of());
  }

  private static MessageSendParams send(
      String messageId, String taskId, String text, String contextId) {
    Message message =
        new Message.Builder()
            .messageId(messageId)
            .taskId(taskId)
            .contextId(contextId)
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart(text)))
            .build();
    return new MessageSendParams.Builder().message(message).build();
  }

  /** Records what the agent could see on each turn. */
  private static final class HistoryAgent extends BaseAgent {
    private final AtomicInteger eventId = new AtomicInteger();
    private volatile String lastSessionId;
    private volatile int lastHistorySize;

    HistoryAgent() {
      super("history_agent", "test", ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      lastSessionId = invocationContext.session().id();
      lastHistorySize = invocationContext.session().immutableEvents().size();
      return Flowable.just(
          Event.builder()
              .id("event-" + eventId.incrementAndGet())
              .author("history_agent")
              .content(
                  Content.builder()
                      .role("model")
                      .parts(ImmutableList.of(Part.builder().text("ok").build()))
                      .build())
              .build());
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }
}
