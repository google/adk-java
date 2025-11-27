package com.google.adk.a2a;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import io.a2a.spec.Message;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared SendMessage execution between HTTP service and other integrations.
 *
 * @apiNote **EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do
 *     not use in production code.
 */
public final class A2ASendMessageExecutor {
  private static final Logger logger = LoggerFactory.getLogger(A2ASendMessageExecutor.class);

  @FunctionalInterface
  public interface AgentExecutionStrategy {
    Single<ImmutableList<Event>> execute(
        String userId,
        String sessionId,
        Content userContent,
        RunConfig runConfig,
        String invocationId);
  }

  private final InMemorySessionService sessionService;
  private final String appName;
  @Nullable private final Runner runner;
  @Nullable private final Duration agentTimeout;
  private static final RunConfig DEFAULT_RUN_CONFIG =
      RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.NONE).setMaxLlmCalls(20).build();

  public A2ASendMessageExecutor(InMemorySessionService sessionService, String appName) {
    this.sessionService = sessionService;
    this.appName = appName;
    this.runner = null;
    this.agentTimeout = null;
  }

  public A2ASendMessageExecutor(BaseAgent agent, String appName, Duration agentTimeout) {
    InMemorySessionService sessionService = new InMemorySessionService();
    Runner runnerInstance =
        new Runner(
            agent,
            appName,
            new InMemoryArtifactService(),
            sessionService,
            new InMemoryMemoryService());
    this.sessionService = sessionService;
    this.appName = appName;
    this.runner = runnerInstance;
    this.agentTimeout = agentTimeout;
  }

  public Single<Message> execute(
      @Nullable Message request, AgentExecutionStrategy agentExecutionStrategy) {
    final String invocationId = UUID.randomUUID().toString();
    final String contextId = resolveContextId(request);
    final ImmutableList<Event> inputEvents = buildInputEvents(request, invocationId);

    ConversationPreprocessor.PreparedInput prepared =
        ConversationPreprocessor.extractHistoryAndUserContent(inputEvents);

    String userId = buildUserId(contextId);
    String sessionId = contextId;

    return ensureSessionExistsSingle(userId, sessionId, contextId)
        .flatMap(
            session ->
                processEventsSingle(
                    session, prepared, userId, sessionId, invocationId, agentExecutionStrategy))
        .map(
            resultEvents -> {
              final String taskId = resolveTaskId(request);
              return ResponseConverter.eventsToMessage(resultEvents, contextId, taskId);
            })
        .onErrorReturn(
            throwable -> {
              logger.error("Error processing A2A request", throwable);
              return errorResponse("Internal error: " + throwable.getMessage(), contextId);
            });
  }

  public Single<Message> execute(@Nullable Message request) {
    if (runner == null || agentTimeout == null) {
      throw new IllegalStateException(
          "Runner-based handle invoked without configured runner or timeout");
    }
    return execute(request, this::executeAgentWithTimeout);
  }

  private Single<Session> ensureSessionExistsSingle(
      String userId, String sessionId, String contextId) {
    return sessionService
        .getSession(appName, userId, sessionId, Optional.empty())
        .switchIfEmpty(
            Single.defer(
                () -> {
                  ConcurrentHashMap<String, Object> initialState = new ConcurrentHashMap<>();
                  return sessionService.createSession(appName, userId, initialState, sessionId);
                }));
  }

  private Completable appendHistoryEvents(
      Session session, ConversationPreprocessor.PreparedInput prepared, String invocationId) {
    ImmutableList<Event> eventsToAppend =
        filterNewHistoryEvents(session, prepared.historyEvents, invocationId);
    return appendEvents(session, eventsToAppend);
  }

  private ImmutableList<Event> filterNewHistoryEvents(
      Session session, List<Event> historyEvents, String invocationId) {
    Set<String> existingEventIds = new HashSet<>();
    for (Event existing : session.events()) {
      if (existing.id() != null) {
        existingEventIds.add(existing.id());
      }
    }

    ImmutableList.Builder<Event> eventsToAppend = ImmutableList.builder();
    for (Event historyEvent : historyEvents) {
      ensureIdentifiers(historyEvent, invocationId);
      if (existingEventIds.add(historyEvent.id())) {
        eventsToAppend.add(historyEvent);
      }
    }
    return eventsToAppend.build();
  }

  private Completable appendEvents(Session session, ImmutableList<Event> events) {
    Completable chain = Completable.complete();
    for (Event event : events) {
      chain = chain.andThen(sessionService.appendEvent(session, event).ignoreElement());
    }
    return chain;
  }

  private Single<ImmutableList<Event>> processEventsSingle(
      Session session,
      ConversationPreprocessor.PreparedInput prepared,
      String userId,
      String sessionId,
      String invocationId,
      AgentExecutionStrategy agentExecutionStrategy) {
    Content userContent =
        prepared.userContent.orElseGet(A2ASendMessageExecutor::defaultUserContent);
    return appendHistoryEvents(session, prepared, invocationId)
        .andThen(
            agentExecutionStrategy.execute(
                userId, sessionId, userContent, DEFAULT_RUN_CONFIG, invocationId));
  }

  private static ImmutableList<Event> defaultHelloEvent(String invocationId) {
    Event e =
        Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId(invocationId)
            .author("user")
            .content(defaultUserContent())
            .build();
    return ImmutableList.of(e);
  }

  private static Content defaultUserContent() {
    return Content.builder()
        .role("user")
        .parts(ImmutableList.of(com.google.genai.types.Part.builder().text("Hello").build()))
        .build();
  }

  private static Message errorResponse(String msg, String contextId) {
    Message error =
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.AGENT)
            .parts(List.of(new TextPart("Error: " + msg)))
            .build();
    if (contextId != null && !contextId.isEmpty()) {
      error.setContextId(contextId);
    }
    return error;
  }

  private Single<ImmutableList<Event>> executeAgentWithTimeout(
      String userId,
      String sessionId,
      Content userContent,
      RunConfig runConfig,
      String invocationId) {
    if (runner == null || agentTimeout == null) {
      throw new IllegalStateException("Runner-based execution invoked without configuration");
    }

    Single<ImmutableList<Event>> agentResultSingle =
        runner
            .runAsync(userId, sessionId, userContent, runConfig)
            .toList()
            .map(events -> ImmutableList.copyOf(events));

    return agentResultSingle
        .timeout(agentTimeout.toMillis(), MILLISECONDS)
        .onErrorResumeNext(
            throwable -> {
              if (isTimeout(throwable)) {
                logger.warn(
                    "Agent execution exceeded {}; returning timeout event",
                    agentTimeout,
                    throwable);
                return Single.just(ImmutableList.of(createTimeoutEvent(invocationId)));
              }
              return Single.error(throwable);
            });
  }

  private static String resolveContextId(@Nullable Message inbound) {
    if (inbound == null || inbound.getContextId() == null || inbound.getContextId().isEmpty()) {
      return UUID.randomUUID().toString();
    }
    return inbound.getContextId();
  }

  private static String resolveTaskId(@Nullable Message inbound) {
    if (inbound != null && inbound.getTaskId() != null && !inbound.getTaskId().isEmpty()) {
      return inbound.getTaskId();
    }
    return UUID.randomUUID().toString();
  }

  private static ImmutableList<Event> buildInputEvents(
      @Nullable Message inbound, String invocationId) {
    if (inbound == null) {
      return defaultHelloEvent(invocationId);
    }
    return RequestConverter.convertAggregatedA2aMessageToAdkEvents(inbound, invocationId);
  }

  private static String buildUserId(String contextId) {
    return "user-" + contextId;
  }

  private static void ensureIdentifiers(Event event, String invocationId) {
    if (isNullOrEmpty(event.id())) {
      event.setId(Event.generateEventId());
    }
    if (isNullOrEmpty(event.invocationId())) {
      event.setInvocationId(invocationId);
    }
  }

  private static Event createTimeoutEvent(String invocationId) {
    return Event.builder()
        .id(UUID.randomUUID().toString())
        .invocationId(invocationId)
        .author("agent")
        .content(
            Content.builder()
                .role("model")
                .parts(
                    ImmutableList.of(
                        com.google.genai.types.Part.builder()
                            .text("Agent execution timed out.")
                            .build()))
                .build())
        .build();
  }

  private static boolean isTimeout(@Nullable Throwable throwable) {
    while (throwable != null) {
      if (throwable instanceof TimeoutException) {
        return true;
      }
      if (throwable.getClass().getName().endsWith("TimeoutException")) {
        return true;
      }
      throwable = throwable.getCause();
    }
    return false;
  }
}
