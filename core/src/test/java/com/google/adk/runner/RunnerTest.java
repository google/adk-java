/*
 * Copyright 2025 Google LLC
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

package com.google.adk.runner;

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.telemetry.Tracing;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.testing.TestUtils.FailingEchoTool;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class RunnerTest {
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  private final BasePlugin plugin = mockPlugin("test");
  private final Content pluginContent = createContent("from plugin");
  private final TestLlm testLlm = createTestLlm(createLlmResponse(createContent("from llm")));
  private final LlmAgent agent = createTestAgentBuilder(testLlm).build();
  private Runner runner;
  private Session session;
  private Tracer originalTracer;

  private final FailingEchoTool failingEchoTool = new FailingEchoTool();
  private final EchoTool echoTool = new EchoTool();

  private final TestLlm testLlmWithFunctionCall =
      createTestLlm(
          createLlmResponse(
              Content.builder()
                  .role("model")
                  .parts(
                      Part.builder()
                          .functionCall(
                              FunctionCall.builder()
                                  // Note: echoTool and failingEchoTool have the same name name
                                  .name(echoTool.name())
                                  .args(ImmutableMap.of("args_name", "args_value"))
                                  .build())
                          .build())
                  .build()),
          createLlmResponse(createContent("done")));

  private BasePlugin mockPlugin(String name) {
    // Need CALLS_REAL_METHODS to avoid NPE. The default implementation is only returning
    // Maybe.empty()
    BasePlugin plugin = mock(BasePlugin.class, CALLS_REAL_METHODS);
    when(plugin.getName()).thenReturn(name);
    return plugin;
  }

  @Before
  public void setUp() {
    this.originalTracer = Tracing.getTracer();
    Tracing.setTracerForTesting(openTelemetryRule.getOpenTelemetry().getTracer("RunnerTest"));
    this.runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    this.session = runner.sessionService().createSession("test", "user").blockingGet();
  }

  @After
  public void tearDown() {
    Tracing.setTracerForTesting(originalTracer);
  }

  @Test
  public void eventsCompaction_enabled() {
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(createContent("llm 1")),
            createLlmResponse(createContent("summary 1")),
            createLlmResponse(createContent("llm 2")),
            createLlmResponse(createContent("summary 2")));
    LlmAgent agent = createTestAgent(testLlm);

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name(this.runner.appName())
                    .rootAgent(agent)
                    .eventsCompactionConfig(new EventsCompactionConfig(1, 0))
                    .build())
            .sessionService(this.runner.sessionService())
            .build();
    var events =
        runner.runAsync("user", session.id(), createContent("user 1")).toList().blockingGet();
    assertThat(simplifyEvents(events)).containsExactly("test agent: llm 1");

    events = runner.runAsync("user", session.id(), createContent("user 2")).toList().blockingGet();
    assertThat(simplifyEvents(events)).containsExactly("test agent: llm 2");

    Session updatedSession =
        runner
            .sessionService()
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(simplifyEvents(updatedSession.events()))
        .containsExactly(
            "user: user 1",
            "test agent: llm 1",
            "user: summary 1",
            "user: user 2",
            "test agent: llm 2",
            "user: summary 2");
  }

  @Test
  public void eventsCompaction_withNullOverlap_doesNotCompact() {
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(createContent("llm 1")), createLlmResponse(createContent("llm 2")));
    LlmAgent agent = createTestAgent(testLlm);

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name(this.runner.appName())
                    .rootAgent(agent)
                    .eventsCompactionConfig(new EventsCompactionConfig(1, null, null, null, null))
                    .build())
            .sessionService(this.runner.sessionService())
            .build();

    var unused1 =
        runner.runAsync("user", session.id(), createContent("user 1")).toList().blockingGet();
    var unused2 =
        runner.runAsync("user", session.id(), createContent("user 2")).toList().blockingGet();

    Session updatedSession =
        runner
            .sessionService()
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(simplifyEvents(updatedSession.events()))
        .containsExactly("user: user 1", "test agent: llm 1", "user: user 2", "test agent: llm 2");
  }

  @Test
  public void eventsCompaction_withNullInterval_doesNotCompact() {
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(createContent("llm 1")), createLlmResponse(createContent("llm 2")));
    LlmAgent agent = createTestAgent(testLlm);

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name(this.runner.appName())
                    .rootAgent(agent)
                    .eventsCompactionConfig(new EventsCompactionConfig(null, 0, null, null, null))
                    .build())
            .sessionService(this.runner.sessionService())
            .build();

    var unused1 =
        runner.runAsync("user", session.id(), createContent("user 1")).toList().blockingGet();
    var unused2 =
        runner.runAsync("user", session.id(), createContent("user 2")).toList().blockingGet();

    Session updatedSession =
        runner
            .sessionService()
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    assertThat(simplifyEvents(updatedSession.events()))
        .containsExactly("user: user 1", "test agent: llm 1", "user: user 2", "test agent: llm 2");
  }

  @Test
  public void pluginDoesNothing() {
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
  }

  @Test
  public void beforeRunCallback_success() {
    when(plugin.beforeRunCallback(any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner
            .runAsync("user", session.id(), createContent("will not be processed"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("model: from plugin");
  }

  @Test
  public void beforeRunCallback_error() {
    Exception exception = new Exception("test");
    when(plugin.beforeRunCallback(any())).thenReturn(Maybe.error(exception));

    runner
        .runAsync("user", session.id(), createContent("will not be processed"))
        .test()
        .assertError(exception);
  }

  @Test
  public void beforeRunCallback_multiplePluginsFirstOnly() {
    BasePlugin plugin1 = mockPlugin("test1");
    when(plugin1.beforeRunCallback(any())).thenReturn(Maybe.just(pluginContent));
    BasePlugin plugin2 = mockPlugin("test2");
    when(plugin2.beforeRunCallback(any())).thenReturn(Maybe.empty());

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin1, plugin2))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner
            .runAsync("user", session.id(), createContent("will not be processed"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("model: from plugin");
    verify(plugin2, never()).beforeRunCallback(any());
  }

  @Test
  public void afterRunCallback_success() {
    when(plugin.afterRunCallback(any())).thenReturn(Completable.complete());

    var events =
        runner
            .runAsync("user", session.id(), createContent("will not be processed"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
    verify(plugin).afterRunCallback(any());
  }

  @Test
  public void afterRunCallback_error() {
    Exception exception = new Exception("test");

    when(plugin.afterRunCallback(any())).thenReturn(Completable.error(exception));

    runner
        .runAsync("user", session.id(), createContent("will not be processed"))
        .test()
        .assertError(exception);

    verify(plugin).afterRunCallback(any());
  }

  @Test
  public void onUserMessageCallback_success() {
    when(plugin.onUserMessageCallback(any(), any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
    ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
    verify(plugin).onUserMessageCallback(any(), contentCaptor.capture());
    assertThat(contentCaptor.getValue().parts().get().get(0).text()).hasValue("from user");
  }

  @Test
  public void beforeAgentCallback_success() {
    when(plugin.beforeAgentCallback(any(), any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).beforeAgentCallback(any(), any());
  }

  @Test
  public void afterAgentCallback_success() {
    when(plugin.afterAgentCallback(any(), any())).thenReturn(Maybe.just(pluginContent));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly("test agent: from llm", "test agent: from plugin");
    verify(plugin).afterAgentCallback(any(), any());
  }

  @Test
  public void beforeModelCallback_success() {
    LlmResponse pluginResponse = createLlmResponse(createContent("from plugin"));

    when(plugin.beforeModelCallback(any(), any())).thenReturn(Maybe.just(pluginResponse));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).beforeModelCallback(any(), any());
  }

  @Test
  public void afterModelCallback_success() {
    LlmResponse pluginResponse = createLlmResponse(createContent("from plugin"));

    when(plugin.afterModelCallback(any(), any())).thenReturn(Maybe.just(pluginResponse));

    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).afterModelCallback(any(), any());
  }

  @Test
  public void onModelErrorCallback_success() {
    Exception exception = new Exception("test");
    LlmResponse pluginResponse = createLlmResponse(createContent("from plugin"));

    when(plugin.onModelErrorCallback(any(), any(), any())).thenReturn(Maybe.just(pluginResponse));

    TestLlm failingTestLlm = createTestLlm(Flowable.error(exception));
    LlmAgent agent = createTestAgentBuilder(failingTestLlm).build();

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from plugin");
    verify(plugin).onModelErrorCallback(any(), any(), any());
  }

  @Test
  public void onModelErrorCallback_error() {
    Exception exception = new Exception("test");

    when(plugin.onModelErrorCallback(any(), any(), any())).thenReturn(Maybe.empty());

    TestLlm failingTestLlm = createTestLlm(Flowable.error(exception));
    LlmAgent agent = createTestAgentBuilder(failingTestLlm).build();

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    runner.runAsync("user", session.id(), createContent("from user")).test().assertError(exception);

    verify(plugin).onModelErrorCallback(any(), any(), any());
  }

  @Test
  public void beforeToolCallback_success() {
    ImmutableMap<String, Object> pluginResponse = ImmutableMap.of("result", "from plugin");

    when(plugin.beforeToolCallback(any(), any(), any())).thenReturn(Maybe.just(pluginResponse));

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool, response={result=from plugin})",
            "test agent: done");
    verify(plugin).beforeToolCallback(any(), any(), any());
  }

  @Test
  public void afterToolCallback_success() {
    ImmutableMap<String, Object> pluginResponse = ImmutableMap.of("result", "from plugin");

    when(plugin.afterToolCallback(any(), any(), any(), any()))
        .thenReturn(Maybe.just(pluginResponse));

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall).tools(ImmutableList.of(echoTool)).build();

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool, response={result=from plugin})",
            "test agent: done");
    verify(plugin).afterToolCallback(any(), any(), any(), any());
  }

  @Test
  public void onToolErrorCallback_success() {
    ImmutableMap<String, Object> pluginResponse = ImmutableMap.of("result", "from plugin");

    when(plugin.onToolErrorCallback(any(), any(), any(), any()))
        .thenReturn(Maybe.just(pluginResponse));

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    var events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool, response={result=from plugin})",
            "test agent: done");
    verify(plugin).onToolErrorCallback(any(), any(), any(), any());
  }

  @Test
  public void onToolErrorCallback_error() {
    when(plugin.onToolErrorCallback(any(), any(), any(), any())).thenReturn(Maybe.empty());

    LlmAgent agent =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();

    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    runner
        .runAsync("user", session.id(), createContent("from user"))
        .test()
        .assertError(RuntimeException.class);

    verify(plugin).onToolErrorCallback(any(), any(), any(), any());
  }

  @Test
  public void onEventCallback_success() {
    when(plugin.onEventCallback(any(), any()))
        .thenReturn(Maybe.just(TestUtils.createEvent("form plugin")));

    List<Event> events =
        runner.runAsync("user", session.id(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("author: content for event form plugin");

    verify(plugin).onEventCallback(any(), any());
  }

  @Test
  public void callbackContextData_preservedAcrossInvocation() {
    String testKey = "testKey";
    String testValue = "testValue";

    when(plugin.onUserMessageCallback(any(), any()))
        .thenAnswer(
            invocation -> {
              InvocationContext context = invocation.getArgument(0);
              context.callbackContextData().put(testKey, testValue);
              return Maybe.empty();
            });

    ArgumentCaptor<InvocationContext> contextCaptor =
        ArgumentCaptor.forClass(InvocationContext.class);
    when(plugin.afterRunCallback(contextCaptor.capture())).thenReturn(Completable.complete());

    var unused =
        runner.runAsync("user", session.id(), createContent("test")).toList().blockingGet();

    assertThat(contextCaptor.getValue().callbackContextData()).containsEntry(testKey, testValue);
  }

  @Test
  public void runAsync_passesSessionSnapshotToPersistenceService() {
    BaseSessionService mockSessionService = mock(BaseSessionService.class);
    Event agentEvent = Event.builder().id("agent-event").author("agent").build();

    // Mock agent to return one event
    BaseAgent mockAgent = mock(BaseAgent.class);
    when(mockAgent.runAsync(any())).thenReturn(Flowable.just(agentEvent));

    // Mock session service
    Session testSession = Session.builder("session-id").appName("test").userId("user").build();
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(testSession));
    when(mockSessionService.appendEvent(any(), any())).thenReturn(Single.just(agentEvent));

    Runner runnerWithMockService =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(mockAgent).build())
            .sessionService(mockSessionService)
            .build();

    var unused =
        runnerWithMockService
            .runAsync("user", "session-id", createContent("start"))
            .toList()
            .blockingGet();

    ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
    ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

    // We expect 2 calls to appendEvent: one for user message, one for agent response.
    verify(mockSessionService, times(2))
        .appendEvent(sessionCaptor.capture(), eventCaptor.capture());

    List<Session> capturedSessions = sessionCaptor.getAllValues();

    // The second call should be for the agent response
    Session sessionForAgentEvent = capturedSessions.get(1);

    assertThat(sessionForAgentEvent.id()).isEqualTo("session-id");

    // Verify it is a snapshot (does not contain the agent event itself)
    assertThat(sessionForAgentEvent.events()).doesNotContain(agentEvent);
  }

  @Test
  public void runAsync_multiEventExecution_lastUpdateTimeProgresses() throws Exception {
    BaseSessionService mockSessionService = mock(BaseSessionService.class);

    Event event1 = Event.builder().id("event-1").author("agent").timestamp(200).build();
    Event event2 = Event.builder().id("event-2").author("agent").timestamp(300).build();

    BaseAgent mockAgent = mock(BaseAgent.class);
    when(mockAgent.runAsync(any())).thenReturn(Flowable.just(event1, event2));

    // Initial session with timestamp 100
    Session testSession =
        Session.builder("session-id")
            .appName("test")
            .userId("user")
            .lastUpdateTime(Instant.ofEpochMilli(100))
            .build();

    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(testSession));

    // Mock appendEvent to return the event passed to it and capture timestamps
    List<Instant> capturedTimestamps = new ArrayList<>();
    when(mockSessionService.appendEvent(any(), any()))
        .thenAnswer(
            invocation -> {
              Session s = invocation.getArgument(0);
              Event e = invocation.getArgument(1);
              capturedTimestamps.add(s.lastUpdateTime());
              if (!Objects.equals(e.author(), "user")) {
                s.lastUpdateTime(Instant.ofEpochMilli(e.timestamp()));
              }
              return Single.just(e);
            });

    Runner runnerWithMockService =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(mockAgent).build())
            .sessionService(mockSessionService)
            .build();

    var unused =
        runnerWithMockService
            .runAsync("user", "session-id", createContent("start"))
            .toList()
            .blockingGet();

    ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
    ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

    // We expect 3 calls to appendEvent:
    // 1 for user message
    // 2 for agent events (event1, event2)
    verify(mockSessionService, times(3))
        .appendEvent(sessionCaptor.capture(), eventCaptor.capture());

    // Verify timestamp for event1 call is the initial timestamp (100)
    assertThat(capturedTimestamps.get(1)).isEqualTo(Instant.ofEpochMilli(100));

    // Verify timestamp for event2 call is the timestamp of event1 (200)
    assertThat(capturedTimestamps.get(2)).isEqualTo(Instant.ofEpochMilli(200));
  }

  @Test
  public void runAsync_concurrentCalls_staleRead() throws Exception {
    BaseSessionService mockSessionService = mock(BaseSessionService.class);
    Event agentEvent = Event.builder().id("agent-event").author("agent").build();

    BaseAgent mockAgent = mock(BaseAgent.class);
    when(mockAgent.runAsync(any())).thenReturn(Flowable.just(agentEvent));

    Session initialSession = Session.builder("session-id").appName("test").userId("user").build();
    AtomicReference<Session> dbSession = new AtomicReference<>(initialSession);

    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> Maybe.just(dbSession.get()));

    PublishSubject<Event> appendSubject = PublishSubject.create();

    when(mockSessionService.appendEvent(any(), any()))
        .thenAnswer(
            invocation -> {
              Session s = invocation.getArgument(0);
              Event e = invocation.getArgument(1);
              return appendSubject
                  .firstOrError()
                  .doOnSuccess(
                      event -> {
                        s.events().add(e);
                        if (e.actions() != null && e.actions().stateDelta() != null) {
                          s.state().putAll(e.actions().stateDelta());
                        }
                        List<Event> newEvents = new ArrayList<>(s.events());
                        Session updated =
                            Session.builder(s.id())
                                .appName(s.appName())
                                .userId(s.userId())
                                .state(s.state())
                                .events(newEvents)
                                .build();
                        dbSession.set(updated);
                      });
            });

    Runner runnerWithMockService =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(mockAgent).build())
            .sessionService(mockSessionService)
            .build();

    TestSubscriber<Event> subscriber1 = new TestSubscriber<>();
    runnerWithMockService
        .runAsync("user", "session-id", createContent("message 1"))
        .subscribe(subscriber1);

    TestSubscriber<Event> subscriber2 = new TestSubscriber<>();
    runnerWithMockService
        .runAsync("user", "session-id", createContent("message 2"))
        .subscribe(subscriber2);

    appendSubject.onNext(agentEvent); // Completes first appendEvent (user msg 1)
    appendSubject.onNext(agentEvent); // Completes second appendEvent (agent event 1)
    appendSubject.onNext(agentEvent); // Completes third appendEvent (user msg 2)
    appendSubject.onNext(agentEvent); // Completes fourth appendEvent (agent event 2)

    subscriber1.awaitDone(5, SECONDS);
    subscriber2.awaitDone(5, SECONDS);

    ArgumentCaptor<InvocationContext> contextCaptor =
        ArgumentCaptor.forClass(InvocationContext.class);
    verify(mockAgent, times(2)).runAsync(contextCaptor.capture());

    List<InvocationContext> capturedContexts = contextCaptor.getAllValues();
    InvocationContext context2 = capturedContexts.get(1);

    assertThat(simplifyEvents(context2.session().events())).contains("user: message 1");
  }

  @Test
  public void runAsync_concurrentCalls_firstFails_secondSucceeds() throws Exception {
    BaseSessionService mockSessionService = mock(BaseSessionService.class);
    Event agentEvent = Event.builder().id("agent-event").author("agent").build();

    BaseAgent mockAgent = mock(BaseAgent.class);
    when(mockAgent.runAsync(any()))
        .thenReturn(Flowable.error(new RuntimeException("Agent failed")))
        .thenReturn(Flowable.just(agentEvent));

    Session initialSession = Session.builder("session-id").appName("test").userId("user").build();
    AtomicReference<Session> dbSession = new AtomicReference<>(initialSession);

    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> Maybe.just(dbSession.get()));

    when(mockSessionService.appendEvent(any(), any()))
        .thenAnswer(
            invocation -> {
              Session s = invocation.getArgument(0);
              Event e = invocation.getArgument(1);
              List<Event> newEvents = new ArrayList<>(s.events());
              newEvents.add(e);
              Session updated =
                  Session.builder(s.id())
                      .appName(s.appName())
                      .userId(s.userId())
                      .state(s.state())
                      .events(newEvents)
                      .build();
              dbSession.set(updated);
              return Single.just(e);
            });

    Runner runnerWithMockService =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(mockAgent).build())
            .sessionService(mockSessionService)
            .build();

    TestSubscriber<Event> subscriber1 = new TestSubscriber<>();
    runnerWithMockService
        .runAsync("user", "session-id", createContent("message 1"))
        .subscribe(subscriber1);

    TestSubscriber<Event> subscriber2 = new TestSubscriber<>();
    runnerWithMockService
        .runAsync("user", "session-id", createContent("message 2"))
        .subscribe(subscriber2);

    subscriber1.awaitDone(5, SECONDS);
    subscriber2.awaitDone(5, SECONDS);

    subscriber1.assertError(RuntimeException.class);
    subscriber2.assertComplete();
    subscriber2.assertValue(agentEvent);
  }

  /**
   * A slow appendEvent must not let the next LLM step start with a stale session missing the
   * previous step's function-response event.
   */
  @Test
  public void runAsync_slowAppendEvent_doesNotCauseStaleSessionInNextStep() throws Exception {
    TestLlm raceTestLlm =
        createTestLlm(
            createFunctionCallLlmResponse("call_1", echoTool.name(), ImmutableMap.of("arg", "v1")),
            createTextLlmResponse("done"));

    LlmAgent agentForRace =
        createTestAgentBuilder(raceTestLlm).tools(ImmutableList.of(echoTool)).build();

    BaseSessionService delayedSessionService =
        new AppendDelayingSessionService(new InMemorySessionService(), 50);

    Runner runnerForRace =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(agentForRace).build())
            .sessionService(delayedSessionService)
            .build();
    Session raceSession =
        runnerForRace.sessionService().createSession("test", "user").blockingGet();

    var unused =
        runnerForRace
            .runAsync("user", raceSession.id(), createContent("start"))
            .toList()
            .blockingGet();

    ImmutableList<LlmRequest> requests = raceTestLlm.getRequests();
    assertThat(requests).hasSize(2);

    // Second LLM request must see the function response from step 1.
    boolean foundToolResponse =
        requests.get(1).contents().stream()
            .flatMap(c -> c.parts().stream().flatMap(List::stream))
            .anyMatch(part -> part.functionResponse().isPresent());
    assertThat(foundToolResponse).isTrue();
  }

  /**
   * When an LlmAgent transfers to a sub-LlmAgent, the sub-agent's events flow back up through the
   * parent's flow and must each be appended to the session exactly once.
   */
  @Test
  public void runAsync_transferToSubAgent_eventsAppendedOnce() throws Exception {
    LlmAgent subAgent =
        createTestAgentBuilder(createTestLlm(createTextLlmResponse("sub response")))
            .name("sub-agent")
            .build();

    // Force a transfer to sub-agent using an afterModelCallback.
    AfterModelCallback transferCallback =
        (ctx, response) -> {
          ctx.eventActions().setTransferToAgent(subAgent.name());
          return Maybe.empty();
        };

    TestLlm rootTestLlm = createTestLlm(createTextLlmResponse("initial"));
    LlmAgent rootAgent =
        createTestAgentBuilder(rootTestLlm)
            .subAgents(subAgent)
            .afterModelCallback(ImmutableList.of(transferCallback))
            .build();

    Runner transferRunner =
        Runner.builder().app(App.builder().name("test").rootAgent(rootAgent).build()).build();
    Session transferSession =
        transferRunner.sessionService().createSession("test", "user").blockingGet();

    var unused =
        transferRunner
            .runAsync("user", transferSession.id(), createContent("start"))
            .toList()
            .blockingGet();

    Session finalSession =
        transferRunner
            .sessionService()
            .getSession(
                transferSession.appName(),
                transferSession.userId(),
                transferSession.id(),
                Optional.empty())
            .blockingGet();

    // Each event id should appear at most once in the session.
    List<String> eventIds = finalSession.events().stream().map(Event::id).toList();
    assertThat(eventIds).containsNoDuplicates();
  }

  /** {@link BaseSessionService} that delays {@link #appendEvent} to surface ordering bugs. */
  private static final class AppendDelayingSessionService implements BaseSessionService {
    private final BaseSessionService delegate;
    private final long appendDelayMs;

    AppendDelayingSessionService(BaseSessionService delegate, long appendDelayMs) {
      this.delegate = delegate;
      this.appendDelayMs = appendDelayMs;
    }

    // Wrapper must preserve the deprecated overload's signature.
    @SuppressWarnings("deprecation")
    @Override
    public Single<Session> createSession(
        String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
      return delegate.createSession(appName, userId, state, sessionId);
    }

    @Override
    public Maybe<Session> getSession(
        String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
      return delegate.getSession(appName, userId, sessionId, config);
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
      return delegate.listSessions(appName, userId);
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
      return delegate.deleteSession(appName, userId, sessionId);
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
      return delegate.listEvents(appName, userId, sessionId);
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
      // Delay the mutation itself so session.events() lags behind the flow's emissions.
      return Single.timer(appendDelayMs, MILLISECONDS)
          .flatMap(unused -> delegate.appendEvent(session, event));
    }
  }

  /**
   * Regression test: {@code outputKey} state delta must reach {@code session.state()}. {@code
   * LlmAgent} applies {@code maybeSaveOutputToState} to the event before the Runner persists it.
   */
  @Test
  public void runAsync_llmAgentWithOutputKey_writesValueToSessionState() {
    Content modelContent = Content.fromParts(Part.fromText("Saved output"));
    TestLlm outputKeyTestLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent outputKeyAgent =
        createTestAgentBuilder(outputKeyTestLlm).outputKey("myOutput").build();

    Runner outputKeyRunner =
        Runner.builder().app(App.builder().name("test").rootAgent(outputKeyAgent).build()).build();
    Session outputKeySession =
        outputKeyRunner.sessionService().createSession("test", "user").blockingGet();

    var unused =
        outputKeyRunner
            .runAsync("user", outputKeySession.id(), createContent("hi"))
            .toList()
            .blockingGet();

    Session persistedSession =
        outputKeyRunner
            .sessionService()
            .getSession("test", "user", outputKeySession.id(), Optional.empty())
            .blockingGet();
    assertThat(persistedSession.state()).containsEntry("myOutput", "Saved output");
  }

  /**
   * Regression test: the Runner is the sole event persister, so each LlmAgent event reaches {@code
   * BaseSessionService.appendEvent} exactly once -- a single-step run appends 2 (user msg + agent
   * event). A second writer would regress this to 3.
   */
  @Test
  public void runAsync_serviceAppendEventCalledOncePerEvent() {
    TestLlm idempotencyTestLlm = createTestLlm(createLlmResponse(createContent("from agent")));
    LlmAgent llmAgent = createTestAgentBuilder(idempotencyTestLlm).build();

    InMemorySessionService realSessionService = new InMemorySessionService();
    BaseSessionService mockSessionService = mock(BaseSessionService.class);
    Session realSession = realSessionService.createSession("test", "user").blockingGet();
    when(mockSessionService.createSession(anyString(), anyString()))
        .thenReturn(Single.just(realSession));
    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> Maybe.just(realSession));
    when(mockSessionService.appendEvent(any(), any()))
        .thenAnswer(
            invocation ->
                realSessionService.appendEvent(
                    invocation.getArgument(0), invocation.getArgument(1)));

    Runner countingRunner =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(llmAgent).build())
            .sessionService(mockSessionService)
            .build();

    var unused =
        countingRunner
            .runAsync("user", realSession.id(), createContent("user message"))
            .toList()
            .blockingGet();

    // Two calls only: user message + agent response. A second writer would push this to 3.
    verify(mockSessionService, times(2)).appendEvent(any(), any());
  }

  /**
   * Regression test: an {@code afterAgentCallback} that mutates state emits a state-delta event
   * authored by the agent; the Runner must persist it like any other agent event (3 events total).
   * Exercised through the Runner, unlike {@code CallbacksTest}.
   */
  @Test
  public void runAsync_afterAgentCallbackWritesState_callbackEventIsPersisted() {
    TestLlm callbackTestLlm = createTestLlm(createLlmResponse(createContent("from agent")));
    Callbacks.AfterAgentCallback writeState =
        callbackContext -> {
          var unused = callbackContext.state().put("after_agent_callback_state_key", "value1");
          return Maybe.empty();
        };
    LlmAgent callbackAgent =
        createTestAgentBuilder(callbackTestLlm).afterAgentCallback(writeState).build();

    Runner callbackRunner =
        Runner.builder().app(App.builder().name("test").rootAgent(callbackAgent).build()).build();
    Session session = callbackRunner.sessionService().createSession("test", "user").blockingGet();

    var unused =
        callbackRunner.runAsync("user", session.id(), createContent("hi")).toList().blockingGet();

    Session persisted =
        callbackRunner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();

    // user message + model response + after-agent-callback state-delta event.
    assertThat(persisted.events()).hasSize(3);
    Event callbackEvent = persisted.events().get(2);
    assertThat(callbackEvent.author()).isEqualTo(callbackAgent.name());
    assertThat(callbackEvent.actions().stateDelta())
        .containsEntry("after_agent_callback_state_key", "value1");
    assertThat(persisted.state()).containsEntry("after_agent_callback_state_key", "value1");
  }

  /**
   * Pure-mock {@link BaseSessionService} returning a sentinel from {@code appendEvent}; verifies
   * the Runner calls it exactly 2 times (user msg + agent event).
   */
  @Test
  public void runAsync_pureMockSessionService_appendEventCalledOncePerLlmAgentEvent() {
    Event sentinelEvent =
        Event.builder()
            .id("sentinel")
            .author("test agent")
            .content(createContent("sentinel response"))
            .build();
    BaseSessionService pureMockSessionService = mock(BaseSessionService.class);
    Session backingSession = Session.builder("session-id").appName("test").userId("user").build();
    when(pureMockSessionService.createSession(anyString(), anyString()))
        .thenReturn(Single.just(backingSession));
    when(pureMockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> Maybe.just(backingSession));
    when(pureMockSessionService.appendEvent(any(), any())).thenReturn(Single.just(sentinelEvent));

    TestLlm pureMockLlm = createTestLlm(createLlmResponse(createContent("from agent")));
    LlmAgent pureMockLlmAgent = createTestAgentBuilder(pureMockLlm).build();
    Runner pureMockRunner =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(pureMockLlmAgent).build())
            .sessionService(pureMockSessionService)
            .build();

    var unused =
        pureMockRunner
            .runAsync("user", backingSession.id(), createContent("user message"))
            .toList()
            .blockingGet();

    // Exactly 2: user message + agent event. A second writer would make it 3.
    verify(pureMockSessionService, times(2)).appendEvent(any(), any());
  }

  /**
   * Multi-step variant: tool call + final response. The append count is 1 (user msg) + N (agent
   * events), never 1 + 2N.
   */
  @Test
  public void runAsync_pureMockSessionService_multiStepLlmAgent_appendsExactlyOncePerEvent() {
    Event sentinelEvent = Event.builder().id("sentinel").author("test agent").build();
    BaseSessionService pureMockSessionService = mock(BaseSessionService.class);
    Session backingSession = Session.builder("session-id").appName("test").userId("user").build();
    when(pureMockSessionService.createSession(anyString(), anyString()))
        .thenReturn(Single.just(backingSession));
    when(pureMockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenAnswer(invocation -> Maybe.just(backingSession));
    when(pureMockSessionService.appendEvent(any(), any())).thenReturn(Single.just(sentinelEvent));

    // Function call, then function-response triggers a second LLM call returning the final text.
    TestLlm twoStepLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "call_1", new EchoTool().name(), ImmutableMap.of("arg", "v1")),
            createTextLlmResponse("final answer"));
    LlmAgent twoStepLlmAgent =
        createTestAgentBuilder(twoStepLlm).tools(ImmutableList.of(new EchoTool())).build();
    Runner twoStepRunner =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(twoStepLlmAgent).build())
            .sessionService(pureMockSessionService)
            .build();

    var emittedEvents =
        twoStepRunner
            .runAsync("user", backingSession.id(), createContent("start"))
            .toList()
            .blockingGet();

    // 1 (user msg) + N (agent events); a second writer would make it 1 + 2N.
    int expectedAppendCount = 1 + emittedEvents.size();
    verify(pureMockSessionService, times(expectedAppendCount)).appendEvent(any(), any());
  }

  @Test
  public void runAsync_bypassesRedundantGetSession() {
    BaseSessionService mockSessionService = mock(BaseSessionService.class);
    Session backingSession = Session.builder("session-id").appName("test").userId("user").build();

    when(mockSessionService.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(Maybe.just(backingSession));
    when(mockSessionService.appendEvent(any(), any()))
        .thenReturn(Single.just(Event.builder().id("sentinel").author("user").build()));

    BaseAgent mockAgent = mock(BaseAgent.class);
    when(mockAgent.runAsync(any()))
        .thenReturn(Flowable.just(Event.builder().id("agent-event").author("agent").build()));

    Runner spyRunner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(mockAgent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .sessionService(mockSessionService)
            .build();

    List<Event> unused =
        spyRunner
            .runAsync("user", backingSession.id(), createContent("from user"))
            .toList()
            .blockingGet();

    // Verify getSession was only called once (at the start of runAsync)
    verify(mockSessionService, times(1)).getSession(anyString(), anyString(), anyString(), any());
  }

  @Test
  public void runAsync_withSessionKey_success() {
    var events =
        runner.runAsync(session.sessionKey(), createContent("from user")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
  }

  @Test
  public void runAsync_withStateDelta_mergesStateIntoSession() {
    ImmutableMap<String, Object> stateDelta = ImmutableMap.of("key1", "value1", "key2", 42);

    var events =
        runner
            .runAsync(
                "user",
                session.id(),
                createContent("test message"),
                RunConfig.builder().build(),
                stateDelta)
            .toList()
            .blockingGet();

    // Verify agent runs successfully
    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");

    // Verify state was merged into session
    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(finalSession.state()).containsAtLeastEntriesIn(stateDelta);
  }

  @Test
  public void runAsync_withSessionKeyAndStateDelta_mergesStateIntoSession() {
    ImmutableMap<String, Object> stateDelta = ImmutableMap.of("key1", "value1", "key2", 42);

    var events =
        runner
            .runAsync(
                session.sessionKey(),
                createContent("test message"),
                RunConfig.builder().build(),
                stateDelta)
            .toList()
            .blockingGet();

    // Verify agent runs successfully
    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");

    // Verify state was merged into session
    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(finalSession.state()).containsAtLeastEntriesIn(stateDelta);
  }

  @Test
  public void runAsync_withEmptyStateDelta_doesNotModifySession() {
    ImmutableMap<String, Object> emptyStateDelta = ImmutableMap.of();

    var events =
        runner
            .runAsync(
                "user",
                session.id(),
                createContent("test message"),
                RunConfig.builder().build(),
                emptyStateDelta)
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");

    // Verify no state events were emitted for empty delta
    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(finalSession.state()).isEmpty();
  }

  @Test
  public void runAsync_withNullStateDelta_doesNotModifySession() {
    var events =
        runner
            .runAsync(
                "user",
                session.id(),
                createContent("test message"),
                RunConfig.builder().build(),
                null)
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");

    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();
    assertThat(finalSession.state()).isEmpty();
  }

  @Test
  public void runAsync_withStateDelta_attachesStateToUserMessageEvent() {
    var unused =
        runner
            .runAsync(
                "user",
                session.id(),
                createContent("test message"),
                RunConfig.builder().build(),
                ImmutableMap.of("testKey", "testValue"))
            .toList()
            .blockingGet();

    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", session.id(), Optional.empty())
            .blockingGet();

    // Verify state delta is attached to the user message event, not a separate event
    Event userEvent =
        finalSession.events().stream()
            .filter(
                e ->
                    e.author().equals("user")
                        && e.content().isPresent()
                        && e.content().get().parts().get().get(0).text().isPresent()
                        && e.content()
                            .get()
                            .parts()
                            .get()
                            .get(0)
                            .text()
                            .get()
                            .equals("test message"))
            .findFirst()
            .orElseThrow();

    assertThat(userEvent.actions()).isNotNull();
    assertThat(userEvent.actions().stateDelta()).containsEntry("testKey", "testValue");

    // Verify there is no separate state-only event
    long stateOnlyEvents =
        finalSession.events().stream()
            .filter(
                e ->
                    e.author().equals("user")
                        && e.content().isEmpty()
                        && e.actions() != null
                        && !e.actions().stateDelta().isEmpty())
            .count();
    assertThat(stateOnlyEvents).isEqualTo(0);
  }

  @Test
  public void runAsync_withStateDelta_mergesWithExistingState() {
    // Create a new session with initial state
    ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
    initialState.put("existing_key", "existing_value");
    Session sessionWithState =
        runner.sessionService().createSession("test", "user", initialState, null).blockingGet();

    // Add new state via stateDelta
    ImmutableMap<String, Object> newDelta = ImmutableMap.of("new_key", "new_value");
    var unused =
        runner
            .runAsync(
                "user",
                sessionWithState.id(),
                createContent("test message"),
                RunConfig.builder().build(),
                newDelta)
            .toList()
            .blockingGet();

    // Verify both old and new states are present (merged, not replaced)
    Session finalSession =
        runner
            .sessionService()
            .getSession("test", "user", sessionWithState.id(), Optional.empty())
            .blockingGet();
    assertThat(finalSession.state()).containsEntry("existing_key", "existing_value");
    assertThat(finalSession.state()).containsEntry("new_key", "new_value");
  }

  @Test
  public void beforeRunCallback_seesUserMessageInSession() {
    ArgumentCaptor<InvocationContext> contextCaptor =
        ArgumentCaptor.forClass(InvocationContext.class);
    when(plugin.beforeRunCallback(contextCaptor.capture())).thenReturn(Maybe.empty());

    var unused =
        runner
            .runAsync("user", session.id(), createContent("user message for callback"))
            .toList()
            .blockingGet();

    // Verify beforeRunCallback was called
    verify(plugin).beforeRunCallback(any());

    // Verify the context passed to beforeRunCallback contains the session with user message
    InvocationContext capturedContext = contextCaptor.getValue();
    Session sessionInCallback = capturedContext.session();

    // Check that the user message is in the session history
    boolean userMessageFound =
        sessionInCallback.events().stream()
            .anyMatch(
                e ->
                    e.author().equals("user")
                        && e.content().isPresent()
                        && e.content().get().parts().get().get(0).text().isPresent()
                        && e.content()
                            .get()
                            .parts()
                            .get()
                            .get(0)
                            .text()
                            .get()
                            .contains("user message for callback"));

    assertThat(userMessageFound).isTrue();
  }

  @Test
  public void beforeRunCallback_withStateDelta_seesMergedState() {
    ArgumentCaptor<InvocationContext> contextCaptor =
        ArgumentCaptor.forClass(InvocationContext.class);
    when(plugin.beforeRunCallback(contextCaptor.capture())).thenReturn(Maybe.empty());

    ImmutableMap<String, Object> stateDelta =
        ImmutableMap.of("callback_key", "callback_value", "number", 123);

    var unused =
        runner
            .runAsync(
                "user",
                session.id(),
                createContent("test with state"),
                RunConfig.builder().build(),
                stateDelta)
            .toList()
            .blockingGet();

    // Verify the context passed to beforeRunCallback has the merged state
    InvocationContext capturedContext = contextCaptor.getValue();
    Session sessionInCallback = capturedContext.session();

    // Verify state delta was merged before beforeRunCallback was invoked
    assertThat(sessionInCallback.state()).containsEntry("callback_key", "callback_value");
    assertThat(sessionInCallback.state()).containsEntry("number", 123);
  }

  @Test
  public void onUserMessageCallback_withStateDelta_seesMergedState() {
    // Snapshot the session state *inside* the callback, otherwise the assertion would
    // observe the post-runAsync state which is mutated by appendEvent regardless of whether
    // the pre-merge in Runner is applied.
    AtomicReference<ConcurrentHashMap<String, Object>> stateInCallback = new AtomicReference<>();
    when(plugin.onUserMessageCallback(any(), any()))
        .thenAnswer(
            invocation -> {
              InvocationContext ctx = invocation.getArgument(0);
              stateInCallback.set(new ConcurrentHashMap<>(ctx.session().state()));
              return Maybe.empty();
            });

    ImmutableMap<String, Object> stateDelta =
        ImmutableMap.of("callback_key", "callback_value", "number", 123);

    var unused =
        runner
            .runAsync(
                "user",
                session.id(),
                createContent("test with state"),
                RunConfig.builder().build(),
                stateDelta)
            .toList()
            .blockingGet();

    // Verify onUserMessageCallback was called
    verify(plugin).onUserMessageCallback(any(), any());

    // Verify state delta was merged before onUserMessageCallback was invoked
    assertThat(stateInCallback.get()).containsEntry("callback_key", "callback_value");
    assertThat(stateInCallback.get()).containsEntry("number", 123);
  }

  @Test
  public void runAsync_ensureEventsAreAppendedInOrder() throws Exception {
    Event event1 = TestUtils.createEvent("1");
    Event event2 = TestUtils.createEvent("2");
    BaseAgent mockAgent = TestUtils.createSubAgent("test agent", event1, event2);

    BaseSessionService mockSessionService = mock(BaseSessionService.class);

    when(mockSessionService.getSession(any(), any(), any(), any())).thenReturn(Maybe.just(session));
    when(mockSessionService.appendEvent(any(), any()))
        .thenAnswer(
            invocation -> {
              Event eventArg = invocation.getArgument(1);
              Single<Event> result = Single.just(eventArg);
              if (eventArg.id().equals("1")) {
                // Artificially delay the first event to ensure it is appended first.
                return result.delay(100, MILLISECONDS);
              }
              return result;
            });

    Runner mockRunner =
        Runner.builder()
            .agent(mockAgent)
            .appName("test")
            .sessionService(mockSessionService)
            .build();

    List<Event> results =
        mockRunner
            .runAsync("user", session.id(), createContent("user message"))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(results))
        .containsExactly("author: content for event 1", "author: content for event 2")
        .inOrder();
  }

  private Content createContent(String text) {
    return Content.builder().parts(Part.builder().text(text).build()).build();
  }

  private static Content createInlineDataContent(byte[]... data) {
    return Content.builder()
        .parts(
            stream(data)
                .map(dataBytes -> Part.fromBytes(dataBytes, "example/octet-stream"))
                .toArray(Part[]::new))
        .build();
  }

  private static Content createInlineDataContent(String... data) {
    return createInlineDataContent(stream(data).map(d -> d.getBytes(UTF_8)).toArray(byte[][]::new));
  }

  @Test
  public void runAsync_createsInvocationSpan() {
    var unused =
        runner.runAsync("user", session.id(), createContent("test message")).toList().blockingGet();

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).isNotEmpty();

    Optional<SpanData> invocationSpan =
        spans.stream().filter(span -> Objects.equals(span.getName(), "invocation")).findFirst();

    assertThat(invocationSpan).isPresent();
    assertThat(invocationSpan.get().hasEnded()).isTrue();
  }

  @Test
  public void runLive_success() throws Exception {
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values())).containsExactly("test agent: from llm");
  }

  @Test
  public void runLive_withSessionKey_success() throws Exception {
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    TestSubscriber<Event> testSubscriber =
        runner.runLive(session.sessionKey(), liveRequestQueue, RunConfig.builder().build()).test();

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values())).containsExactly("test agent: from llm");
  }

  @Test
  public void runLive_withToolExecution() throws Exception {
    LlmAgent agentWithTool =
        createTestAgentBuilder(testLlmWithFunctionCall).tools(ImmutableList.of(echoTool)).build();
    Runner runnerWithTool =
        Runner.builder().app(App.builder().name("test").rootAgent(agentWithTool).build()).build();
    Session sessionWithTool =
        runnerWithTool.sessionService().createSession("test", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    TestSubscriber<Event> testSubscriber =
        runnerWithTool
            .runLive(sessionWithTool, liveRequestQueue, RunConfig.builder().build())
            .test();

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "test agent: FunctionCall(name=echo_tool, args={args_name=args_value})",
            "test agent: FunctionResponse(name=echo_tool,"
                + " response={result={args_name=args_value}})",
            "test agent: done");
  }

  @Test
  public void runLive_llmError() throws Exception {
    Exception exception = new Exception("LLM test error");
    TestLlm failingTestLlm = createTestLlm(Flowable.error(exception));
    LlmAgent agent = createTestAgentBuilder(failingTestLlm).build();
    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(agent).build()).build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();

    liveRequestQueue.content(createContent("from user"));
    // No liveRequestQueue.close() here as the LLM throws an error

    testSubscriber.await();
    testSubscriber.assertError(exception);
  }

  @Test
  public void runLive_toolError() throws Exception {
    LlmAgent agentWithFailingTool =
        createTestAgentBuilder(testLlmWithFunctionCall)
            .tools(ImmutableList.of(failingEchoTool))
            .build();
    Runner runnerWithFailingTool =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(agentWithFailingTool).build())
            .build();
    Session sessionWithFailingTool =
        runnerWithFailingTool.sessionService().createSession("test", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    TestSubscriber<Event> testSubscriber =
        runnerWithFailingTool
            .runLive(sessionWithFailingTool, liveRequestQueue, RunConfig.builder().build())
            .test();

    liveRequestQueue.content(createContent("from user"));
    // No liveRequestQueue.close() here as the tool throws an error

    testSubscriber.await();
    testSubscriber.assertError(RuntimeException.class);
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly("test agent: FunctionCall(name=echo_tool, args={args_name=args_value})");
  }

  @Test
  public void runLive_createsInvocationSpan() {
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    var unused = runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertThat(spans).isNotEmpty();

    Optional<SpanData> invocationSpan =
        spans.stream().filter(span -> Objects.equals(span.getName(), "invocation")).findFirst();

    assertThat(invocationSpan).isPresent();
    assertThat(invocationSpan.get().hasEnded()).isTrue();
  }

  @Test
  public void runAsync_createsToolSpansWithCorrectParent() {
    LlmAgent agentWithTool =
        createTestAgentBuilder(testLlmWithFunctionCall).tools(ImmutableList.of(echoTool)).build();
    Runner runnerWithTool =
        Runner.builder().app(App.builder().name("test").rootAgent(agentWithTool).build()).build();
    Session sessionWithTool =
        runnerWithTool.sessionService().createSession("test", "user").blockingGet();

    var unused =
        runnerWithTool
            .runAsync(
                sessionWithTool.sessionKey(),
                createContent("from user"),
                RunConfig.builder().build())
            .toList()
            .blockingGet();

    List<SpanData> spans = openTelemetryRule.getSpans();
    List<SpanData> llmSpans = spans.stream().filter(s -> s.getName().equals("call_llm")).toList();
    List<SpanData> toolSpans =
        spans.stream().filter(s -> s.getName().equals("execute_tool echo_tool")).toList();

    assertThat(llmSpans).hasSize(2);
    assertThat(toolSpans).hasSize(1);

    List<String> llmSpanIds = llmSpans.stream().map(s -> s.getSpanContext().getSpanId()).toList();
    String toolParentId = toolSpans.get(0).getParentSpanContext().getSpanId();

    assertThat(llmSpanIds).contains(toolParentId);
  }

  @Test
  public void runLive_createsToolSpansWithCorrectParent() throws Exception {
    LlmAgent agentWithTool =
        createTestAgentBuilder(testLlmWithFunctionCall).tools(ImmutableList.of(echoTool)).build();
    Runner runnerWithTool =
        Runner.builder().app(App.builder().name("test").rootAgent(agentWithTool).build()).build();
    Session sessionWithTool =
        runnerWithTool.sessionService().createSession("test", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runnerWithTool
            .runLive(sessionWithTool.sessionKey(), liveRequestQueue, RunConfig.builder().build())
            .test();

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();

    List<SpanData> spans = openTelemetryRule.getSpans();
    List<SpanData> llmSpans = spans.stream().filter(s -> s.getName().equals("call_llm")).toList();
    List<SpanData> toolSpans =
        spans.stream().filter(s -> s.getName().equals("execute_tool echo_tool")).toList();

    // In runLive, there is one call_llm span for the execution
    assertThat(llmSpans).hasSize(1);
    assertThat(toolSpans).hasSize(1);

    List<String> llmSpanIds = llmSpans.stream().map(s -> s.getSpanContext().getSpanId()).toList();
    String toolParentId = toolSpans.get(0).getParentSpanContext().getSpanId();

    assertThat(llmSpanIds).contains(toolParentId);
  }

  @Test
  public void runAsync_withoutSessionAndAutoCreateSessionTrue_createsSession() {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(true).build();
    String newSessionId = UUID.randomUUID().toString();

    var events =
        runner
            .runAsync("user", newSessionId, createContent("from user"), runConfig)
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
    assertThat(
            runner
                .sessionService()
                .getSession("test", "user", newSessionId, Optional.empty())
                .blockingGet())
        .isNotNull();
  }

  @Test
  public void runAsync_withoutSessionAndAutoCreateSessionTrue_withSessionKey_createsSession() {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(true).build();
    SessionKey sessionKey = new SessionKey("test", "user", UUID.randomUUID().toString());

    var events =
        runner.runAsync(sessionKey, createContent("from user"), runConfig).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
    assertThat(runner.sessionService().getSession(sessionKey, null).blockingGet()).isNotNull();
  }

  @Test
  public void runAsync_withoutSessionAndAutoCreateSessionFalse_throwsException() {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(false).build();
    String newSessionId = UUID.randomUUID().toString();

    runner
        .runAsync("user", newSessionId, createContent("from user"), runConfig)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  @Test
  public void runAsync_withoutSessionAndAutoCreateSessionFalse_withSessionKey_throwsException() {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(false).build();
    SessionKey sessionKey = new SessionKey("test", "user", UUID.randomUUID().toString());

    runner
        .runAsync(sessionKey, createContent("from user"), runConfig)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  @Test
  public void runLive_withoutSessionAndAutoCreateSessionTrue_createsSession() throws Exception {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(true).build();
    String newSessionId = UUID.randomUUID().toString();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive("user", newSessionId, liveRequestQueue, runConfig).test();

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values())).containsExactly("test agent: from llm");
    assertThat(
            runner
                .sessionService()
                .getSession("test", "user", newSessionId, Optional.empty())
                .blockingGet())
        .isNotNull();
  }

  @Test
  public void runLive_withoutSessionAndAutoCreateSessionTrue_withSessionKey_createsSession()
      throws Exception {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(true).build();
    SessionKey sessionKey = new SessionKey("test", "user", UUID.randomUUID().toString());
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(sessionKey, liveRequestQueue, runConfig).test();

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values())).containsExactly("test agent: from llm");
    assertThat(runner.sessionService().getSession(sessionKey, null).blockingGet()).isNotNull();
  }

  @Test
  public void runLive_withoutSessionAndAutoCreateSessionFalse_throwsException() {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(false).build();
    String newSessionId = UUID.randomUUID().toString();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    runner
        .runLive("user", newSessionId, liveRequestQueue, runConfig)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  @Test
  public void runLive_withoutSessionAndAutoCreateSessionFalse_withSessionKey_throwsException() {
    RunConfig runConfig = RunConfig.builder().setAutoCreateSession(false).build();
    SessionKey sessionKey = new SessionKey("test", "user", UUID.randomUUID().toString());
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    runner
        .runLive(sessionKey, liveRequestQueue, runConfig)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  @Test
  public void runAsync_withToolConfirmation() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "tool_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("Response after observing tool needs confirmation."),
            createTextLlmResponse("Response after user confirmed."));
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .tools(FunctionTool.create(Tools.class, "echoTool", /* requireConfirmation= */ true))
            .build();
    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(agent).build()).build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> eventsBeforeConfirmation =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();
    FunctionCall askUserConfirmationFunctionCall =
        Iterables.getOnlyElement(
            eventsBeforeConfirmation.stream()
                .map(Functions::getAskUserConfirmationFunctionCalls)
                .filter(functionCalls -> !functionCalls.isEmpty())
                .findFirst()
                .get());
    List<Event> eventsAfterConfirmation =
        runner
            .runAsync(
                "user",
                session.id(),
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id(askUserConfirmationFunctionCall.id().get())
                                .name(askUserConfirmationFunctionCall.name().get())
                                .response(ImmutableMap.of("confirmed", true)))
                        .build()))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(eventsBeforeConfirmation))
        .containsExactly(
            "test agent: FunctionCall(name=echoTool, args={message=hello})",
            "test agent: FunctionCall(name=adk_request_confirmation,"
                + " args={originalFunctionCall=FunctionCall{id=Optional[tool_call_id],"
                + " args=Optional[{message=hello}], name=Optional[echoTool],"
                + " partialArgs=Optional.empty, willContinue=Optional.empty},"
                + " toolConfirmation=ToolConfirmation{hint=Please approve or reject the tool call"
                + " echoTool() by responding with a FunctionResponse with an expected"
                + " ToolConfirmation payload., confirmed=false, payload=null}})",
            "test agent: FunctionResponse(name=echoTool, response={error=This tool call requires"
                + " confirmation, please approve or reject.})",
            "test agent: Response after observing tool needs confirmation.")
        .inOrder();
    assertThat(simplifyEvents(eventsAfterConfirmation))
        .containsExactly(
            "test agent: FunctionResponse(name=echoTool, response={message=hello})",
            "test agent: Response after user confirmed.")
        .inOrder();
    assertThat(testLlm.getLastRequest().contents().stream().map(TestUtils::formatContent))
        .containsExactly(
            "from user",
            "FunctionCall(name=echoTool, args={message=hello})",
            "FunctionResponse(name=echoTool, response={message=hello})")
        .inOrder();
  }

  // HITL tool confirmation must resume the originating sub-agent even when wrapped inside a
  // non-LlmAgent workflow agent (e.g. SequentialAgent).
  @Test
  public void runAsync_withToolConfirmation_inSequentialAgentSubAgent_resumesSubAgent() {
    TestLlm childTestLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "tool_call_id", "echoTool", ImmutableMap.of("message", "hello")),
            createTextLlmResponse("Response after observing tool needs confirmation."),
            createTextLlmResponse("Response after user confirmed."));
    LlmAgent childAgent =
        createTestAgentBuilder(childTestLlm)
            .name("child_agent")
            .tools(FunctionTool.create(Tools.class, "echoTool", /* requireConfirmation= */ true))
            .build();
    SequentialAgent workflowAgent =
        SequentialAgent.builder()
            .name("workflow_agent")
            .subAgents(ImmutableList.of(childAgent))
            .build();
    // Root transfers to workflow_agent to mirror the bug report's control flow.
    TestLlm rootTestLlm =
        createTestLlm(
            createLlmResponse(
                Content.fromParts(
                    Part.fromFunctionCall(
                        "transfer_to_agent", ImmutableMap.of("agent_name", "workflow_agent")))));
    LlmAgent rootAgent =
        createTestAgentBuilder(rootTestLlm)
            .name("root_agent")
            .subAgents(ImmutableList.of(workflowAgent))
            .build();
    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(rootAgent).build()).build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    List<Event> eventsBeforeConfirmation =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("from user")))
            .toList()
            .blockingGet();
    FunctionCall askUserConfirmationFunctionCall =
        Iterables.getOnlyElement(
            eventsBeforeConfirmation.stream()
                .map(Functions::getAskUserConfirmationFunctionCalls)
                .filter(functionCalls -> !functionCalls.isEmpty())
                .findFirst()
                .get());
    List<Event> eventsAfterConfirmation =
        runner
            .runAsync(
                "user",
                session.id(),
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id(askUserConfirmationFunctionCall.id().get())
                                .name(askUserConfirmationFunctionCall.name().get())
                                .response(ImmutableMap.of("confirmed", true)))
                        .build()))
            .toList()
            .blockingGet();

    // The originating child agent (not the root agent) must execute the tool.
    assertThat(simplifyEvents(eventsAfterConfirmation))
        .containsExactly(
            "child_agent: FunctionResponse(name=echoTool, response={message=hello})",
            "child_agent: Response after user confirmed.")
        .inOrder();
  }

  // Orphan function responses (id not matching any prior call) should fall back to the root agent.
  @Test
  public void runAsync_withFunctionResponseNotMatchingAnyCall_fallsBackToRootAgent() {
    TestLlm rootLlm = createTestLlm(createTextLlmResponse("after function response"));
    LlmAgent rootAgent = createTestAgentBuilder(rootLlm).name("root_agent").build();
    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(rootAgent).build()).build();
    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    // Function response with id that does not match any prior function call.
    List<Event> events =
        runner
            .runAsync(
                "user",
                session.id(),
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id("non_existent_id")
                                .name("orphanFn")
                                .response(ImmutableMap.of("x", 1)))
                        .build()))
            .toList()
            .blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("root_agent: after function response");
  }

  @Test
  public void close_closesPluginsAndCodeExecutors() {
    BasePlugin plugin = mockPlugin("close_test_plugin");
    when(plugin.close()).thenReturn(Completable.complete());
    LlmAgent agentWithCodeExecutor = createTestAgentBuilder(testLlm).build();
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name("test")
                    .rootAgent(agentWithCodeExecutor)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();

    runner.close().blockingAwait();

    verify(plugin).close();
  }

  @Test
  public void runAsync_contextPropagation() {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.current().with(testKey, "test-value");

    List<Event> events;
    try (Scope scope = testContext.makeCurrent()) {
      events =
          runner
              .runAsync("user", session.id(), createContent("test message"))
              .doOnNext(
                  event -> {
                    assertThat(Context.current().get(testKey)).isEqualTo("test-value");
                  })
              .toList()
              .blockingGet();
    }

    assertThat(simplifyEvents(events)).containsExactly("test agent: from llm");
  }

  @Test
  public void runLive_contextPropagation() throws Exception {
    ContextKey<String> testKey = ContextKey.named("test-key");
    Context testContext = Context.current().with(testKey, "test-value");
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber;
    try (Scope scope = testContext.makeCurrent()) {
      testSubscriber =
          runner
              .runLive(session, liveRequestQueue, RunConfig.builder().build())
              .doOnNext(
                  event -> {
                    assertThat(Context.current().get(testKey)).isEqualTo("test-value");
                  })
              .test();
    }

    liveRequestQueue.content(createContent("from user"));
    liveRequestQueue.close();

    testSubscriber.await();
    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values())).containsExactly("test agent: from llm");
  }

  @Test
  public void buildRunnerWithPlugins_success() {
    BasePlugin plugin1 = mockPlugin("test1");
    BasePlugin plugin2 = mockPlugin("test2");
    Runner runner = Runner.builder().agent(agent).appName("test").plugins(plugin1, plugin2).build();
    assertThat(runner.pluginManager().getPlugins()).containsExactly(plugin1, plugin2);
  }

  public static class Tools {
    private Tools() {}

    public static ImmutableMap<String, Object> echoTool(String message) {
      return ImmutableMap.of("message", message);
    }
  }

  @Test
  public void runner_executesSaveArtifactFlow() {
    // arrange
    final AtomicInteger artifactsSavedCounter = new AtomicInteger();
    BaseArtifactService mockArtifactService = Mockito.mock(BaseArtifactService.class);
    when(mockArtifactService.saveArtifact(any(), any(), any(), any(), any()))
        .thenReturn(
            Single.defer(
                () -> {
                  // we want to assert not only that the saveArtifact method was
                  // called, but also that the flow that it returned was run, so
                  // we need to record the call in a counter
                  artifactsSavedCounter.incrementAndGet();
                  return Single.just(42);
                }));
    Runner runner =
        Runner.builder()
            .app(App.builder().name("test").rootAgent(agent).build())
            .artifactService(mockArtifactService)
            .build();
    session = runner.sessionService().createSession("test", "user").blockingGet();
    // each inline data will be saved using our mock artifact service
    Content content = createInlineDataContent("test data", "test data 2");
    RunConfig runConfig = RunConfig.builder().setSaveInputBlobsAsArtifacts(true).build();

    // act
    var events = runner.runAsync("user", session.id(), content, runConfig).test();

    // assert
    events.assertComplete();
    // artifacts were saved
    assertThat(artifactsSavedCounter.get()).isEqualTo(2);
    // agent was run
    assertThat(simplifyEvents(events.values())).containsExactly("test agent: from llm");
  }
}
