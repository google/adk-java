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

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.Context;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.events.Event;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Predicate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Helpers for the tests that pause a workflow and resume it. */
final class WorkflowResumeTesting {

  private static final String APP = "resume_app";
  private static final String USER = "user";
  static final Start START = Start.INSTANCE;
  // The name of the long-running call that the nodes of these tests wait on.
  static final String LONG_RUNNING_CALL = "approve";

  /** An object schema that requires an integer {@code age}. */
  static final Schema AGE_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(ImmutableMap.of("age", Schema.builder().type(Type.Known.INTEGER).build()))
          .required("age")
          .build();

  private WorkflowResumeTesting() {}

  /** Returns the input that the {@code reply} node of a workflow named {@code wf} received. */
  static @Nullable Object replyInput(List<Event> events) {
    Object output = outputEventAt(events, "wf@1/reply@1").output().orElseThrow();
    return ((Map<?, ?>) output).get("received");
  }

  static Event outputEventAt(List<Event> events, String nodePath) {
    return events.stream()
        .filter(event -> pathOf(event).equals(nodePath) && event.output().isPresent())
        .collect(onlyElement());
  }

  static Event eventAt(List<Event> events, String nodePath) {
    return events.stream().filter(event -> pathOf(event).equals(nodePath)).collect(onlyElement());
  }

  static ImmutableList<String> pathsOf(List<Event> events) {
    return events.stream().map(WorkflowResumeTesting::pathOf).collect(toImmutableList());
  }

  static String pathOf(Event event) {
    return event.nodeInfo().map(NodeInfo::path).orElse("");
  }

  static Content go() {
    return Content.builder().role("user").parts(Part.fromText("go")).build();
  }

  static Content answer(String interruptId, Map<String, ?> response) {
    return answer(interruptId, response, LONG_RUNNING_CALL);
  }

  @SuppressWarnings("unchecked") // A function response's map holds JSON values.
  static Content answer(String interruptId, Map<String, ?> response, String name) {
    return Content.builder()
        .role("user")
        .parts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id(interruptId)
                        .name(name)
                        .response((Map<String, Object>) response)
                        .build())
                .build())
        .build();
  }

  /** Returns the user's answer to the {@link RequestInput} with ID {@code interruptId}. */
  static Content requestAnswer(String interruptId, Map<String, ?> response) {
    return answer(interruptId, response, RequestInput.FUNCTION_CALL_NAME);
  }

  /** Returns the answer {@code {"result": result}} to the request {@code interruptId}. */
  static Content requestResult(String interruptId, Object result) {
    return requestAnswer(interruptId, ImmutableMap.of("result", result));
  }

  /** Returns the {@code RequestInput} calls among {@code events}. */
  static ImmutableList<FunctionCall> requests(List<Event> events) {
    return events.stream()
        .flatMap(event -> event.functionCalls().stream())
        .filter(call -> call.name().orElse("").equals(RequestInput.FUNCTION_CALL_NAME))
        .collect(toImmutableList());
  }

  static FunctionCall onlyRequest(List<Event> events) {
    return requests(events).stream().collect(onlyElement());
  }

  static Content callContent(String name, String id) {
    return Content.builder()
        .role("model")
        .parts(
            Part.builder().functionCall(FunctionCall.builder().name(name).id(id).build()).build())
        .build();
  }

  static Content modelText(String text) {
    return Content.builder().role("model").parts(Part.fromText(text)).build();
  }

  static Event.Builder newEvent() {
    return Event.builder().id(Event.generateEventId()).author("");
  }

  /** Returns a node that outputs the input it received under the key {@code received}. */
  static Node captureInput(String name) {
    return new TestNode(
        name, (ctx, input) -> Flowable.just(Collections.singletonMap("received", input)));
  }

  /** Returns a node that waits on the long-running call {@code interruptId}, as a tool does. */
  static Node longRunningAsk(String name, String interruptId) {
    return new TestNode(name, (ctx, input) -> Flowable.just(interrupt(interruptId)));
  }

  /** Returns the event in which a node waits on the long-running call {@code interruptId}. */
  static Event interrupt(String interruptId) {
    return newEvent()
        .content(callContent(LONG_RUNNING_CALL, interruptId))
        .longRunningToolIds(ImmutableSet.of(interruptId))
        .build();
  }

  /** Returns a retry policy of {@code maxAttempts} attempts that never waits between them. */
  static RetryConfig instantRetry(int maxAttempts) {
    return RetryConfig.builder()
        .maxAttempts(maxAttempts)
        .initialDelay(Duration.ZERO)
        .jitter(0)
        .build();
  }

  static NodeConfig retrying(int maxAttempts) {
    return NodeConfig.builder().retryConfig(instantRetry(maxAttempts)).build();
  }

  /** A runner over an app rooted on a node, driven a turn at a time on one session. */
  static final class WorkflowSession {
    private final Runner runner;
    private final String sessionId;
    private RunConfig runConfig = RunConfig.builder().build();

    WorkflowSession(Node root) {
      this(root, /* resumable= */ true);
    }

    WorkflowSession(Node root, BaseSessionService sessionService) {
      this(root, /* resumable= */ true, sessionService);
    }

    WorkflowSession(Node root, boolean resumable, Plugin... plugins) {
      this(root, resumable, new InMemorySessionService(), plugins);
    }

    private WorkflowSession(
        Node root, boolean resumable, BaseSessionService sessionService, Plugin... plugins) {
      App.Builder app = App.builder().name(APP).rootNode(root).plugins(plugins);
      if (resumable) {
        app.resumabilityConfig(ResumabilityConfig.builder().resumable(true).build());
      }
      this.runner = Runner.builder().app(app.build()).sessionService(sessionService).build();
      this.sessionId = runner.sessionService().createSession(APP, USER).blockingGet().id();
    }

    /** Runs the later turns with {@code runConfig}. */
    @CanIgnoreReturnValue
    WorkflowSession withRunConfig(RunConfig runConfig) {
      this.runConfig = runConfig;
      return this;
    }

    ImmutableList<Event> turn(Content message) {
      return ImmutableList.copyOf(
          runner
              .runAsync(USER, sessionId, message, runConfig)
              .timeout(30, SECONDS)
              .toList()
              .blockingGet());
    }

    ImmutableList<Event> resume(String invocationId) {
      return ImmutableList.copyOf(
          runner
              .runAsync(
                  USER,
                  sessionId,
                  invocationId,
                  /* newMessage= */ null,
                  RunConfig.builder().build(),
                  /* stateDelta= */ null)
              .timeout(30, SECONDS)
              .toList()
              .blockingGet());
    }

    /** Runs a turn until an event matches {@code last}, which cancels the rest of the turn. */
    ImmutableList<Event> turnUntil(Content message, Predicate<Event> last) {
      return ImmutableList.copyOf(
          runner
              .runAsync(USER, sessionId, message)
              .takeUntil(last)
              .timeout(30, SECONDS)
              .toList()
              .blockingGet());
    }

    /** Runs a turn that must fail, returning the events it streamed and its failure. */
    FailedTurn failingTurn(Content message) {
      List<Event> events = new ArrayList<>();
      AtomicReference<Throwable> error = new AtomicReference<>();
      runner
          .runAsync(USER, sessionId, message)
          .timeout(30, SECONDS)
          .blockingSubscribe(events::add, error::set);
      checkState(error.get() != null, "expected the turn to fail");
      return new FailedTurn(ImmutableList.copyOf(events), error.get());
    }

    /** Runs a turn whose events matter only for the turns after it. */
    void advance(Content message) {
      checkState(!turn(message).isEmpty(), "a turn produced no events to resume from");
    }

    ImmutableList<String> invocationIds() {
      return storedEvents().stream().map(Event::invocationId).distinct().collect(toImmutableList());
    }

    ImmutableList<Event> storedEvents() {
      return runner
          .sessionService()
          .getSession(APP, USER, sessionId, Optional.empty())
          .blockingGet()
          .immutableEvents();
    }
  }

  /** The events a failed turn streamed before it failed, and its failure. */
  record FailedTurn(ImmutableList<Event> events, Throwable error) {}

  /** A node that waits for an output and runs its {@code body}. */
  record WaitingNode(
      String name, BiFunction<Context, @Nullable Object, Flowable<?>> body, boolean rerunOnResume)
      implements Node {

    WaitingNode(String name, BiFunction<Context, @Nullable Object, Flowable<?>> body) {
      this(name, body, /* rerunOnResume= */ false);
    }

    @Override
    public boolean waitForOutput() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return body.apply(context, nodeInput);
    }
  }

  /** A node whose model-style message is its output. */
  record MessageOutputNode(String name, String text, @Nullable Schema schema) implements Node {

    @Override
    public Optional<Schema> outputSchema() {
      return Optional.ofNullable(schema);
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(
          newEvent()
              .content(modelText(text))
              .nodeInfo(NodeInfo.builder().messageAsOutput(true).build())
              .build());
    }
  }

  /** Keeps each non-partial event as its JSON form, as a persistent session store does. */
  static final class JsonSessionService implements BaseSessionService {
    private final InMemorySessionService delegate = new InMemorySessionService();

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
      Event stored = event.partial().orElse(false) ? event : Event.fromJson(event.toJson());
      return delegate.appendEvent(session, stored);
    }

    // BaseSessionService's only abstract createSession overload is deprecated.
    @SuppressWarnings("deprecation")
    @Override
    public Single<Session> createSession(
        String appName,
        String userId,
        @Nullable ConcurrentMap<String, Object> state,
        @Nullable String sessionId) {
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
  }

  /**
   * Keeps each event as it was emitted in the live session, but reads a session back with each
   * event in its JSON form, as a persistent store that returns the original event does.
   */
  static final class LiveEventSessionService implements BaseSessionService {
    private final InMemorySessionService delegate = new InMemorySessionService();

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
      return delegate.appendEvent(session, event);
    }

    // BaseSessionService's only abstract createSession overload is deprecated.
    @SuppressWarnings("deprecation")
    @Override
    public Single<Session> createSession(
        String appName,
        String userId,
        @Nullable ConcurrentMap<String, Object> state,
        @Nullable String sessionId) {
      return delegate.createSession(appName, userId, state, sessionId);
    }

    @Override
    public Maybe<Session> getSession(
        String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
      return delegate
          .getSession(appName, userId, sessionId, config)
          .map(session ->
              Session.builder(session.id())
                  .appName(session.appName())
                  .userId(session.userId())
                  .state(new ConcurrentHashMap<>(session.state()))
                  .events(
                      session.immutableEvents().stream()
                          .map(event -> Event.fromJson(event.toJson()))
                          .collect(toImmutableList()))
                  .lastUpdateTime(session.lastUpdateTime())
                  .build());
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
  }
}
