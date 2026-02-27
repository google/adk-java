package com.google.adk.a2a.executor;

import static java.util.Objects.requireNonNull;

import com.google.adk.a2a.converters.EventConverter;
import com.google.adk.a2a.converters.PartConverter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.Artifact;
import io.a2a.spec.InvalidAgentResponseError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the A2A AgentExecutor interface that uses ADK to execute agent tasks.
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public class AgentExecutor implements io.a2a.server.agentexecution.AgentExecutor {
  private static final Logger logger = LoggerFactory.getLogger(AgentExecutor.class);
  private static final String USER_ID_PREFIX = "A2A_USER_";
  private final Map<String, Disposable> activeTasks = new ConcurrentHashMap<>();
  private final Runner.Builder runnerBuilder;
  private final AgentExecutorConfig agentExecutorConfig;

  private AgentExecutor(
      App app,
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      AgentExecutorConfig agentExecutorConfig) {
    requireNonNull(agentExecutorConfig);
    this.agentExecutorConfig = agentExecutorConfig;

    this.runnerBuilder =
        Runner.builder()
            .agent(agent)
            .appName(appName)
            .artifactService(artifactService)
            .sessionService(sessionService)
            .memoryService(memoryService)
            .plugins(plugins);
    if (app != null) {
      this.runnerBuilder.app(app);
    }
    // Check that the runner is configured correctly and can be built.
    var unused = runnerBuilder.build();
  }

  /** Builder for {@link AgentExecutor}. */
  public static class Builder {
    private App app;
    private BaseAgent agent;
    private String appName;
    private BaseArtifactService artifactService;
    private BaseSessionService sessionService;
    private BaseMemoryService memoryService;
    private List<? extends Plugin> plugins = ImmutableList.of();
    private AgentExecutorConfig agentExecutorConfig;

    @CanIgnoreReturnValue
    public Builder agentExecutorConfig(AgentExecutorConfig agentExecutorConfig) {
      this.agentExecutorConfig = agentExecutorConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder app(App app) {
      this.app = app;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder agent(BaseAgent agent) {
      this.agent = agent;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder appName(String appName) {
      this.appName = appName;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder artifactService(BaseArtifactService artifactService) {
      this.artifactService = artifactService;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder sessionService(BaseSessionService sessionService) {
      this.sessionService = sessionService;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder memoryService(BaseMemoryService memoryService) {
      this.memoryService = memoryService;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder plugins(List<? extends Plugin> plugins) {
      this.plugins = plugins;
      return this;
    }

    public AgentExecutor build() {
      return new AgentExecutor(
          app,
          agent,
          appName,
          artifactService,
          sessionService,
          memoryService,
          plugins,
          agentExecutorConfig);
    }
  }

  @Override
  public void cancel(RequestContext ctx, EventQueue eventQueue) {
    TaskUpdater updater = new TaskUpdater(ctx, eventQueue);
    updater.cancel();
    cleanupTask(ctx.getTaskId());
  }

  @Override
  public void execute(RequestContext ctx, EventQueue eventQueue) {
    TaskUpdater updater = new TaskUpdater(ctx, eventQueue);
    Message message = ctx.getMessage();
    if (message == null) {
      throw new IllegalArgumentException("Message cannot be null");
    }
    // Submits a new task if there is no active task.
    if (ctx.getTask() == null) {
      updater.submit();
    }
    // Group all reactive work for this task into one container
    CompositeDisposable taskDisposables = new CompositeDisposable();
    // Check if the task with the task id is already running, put if absent.
    if (activeTasks.putIfAbsent(ctx.getTaskId(), taskDisposables) != null) {
      throw new IllegalStateException(String.format("Task %s already running", ctx.getTaskId()));
    }
    EventProcessor p = new EventProcessor();
    Content content = PartConverter.messageToContent(message);
    Single<Boolean> skipExecution =
        agentExecutorConfig.beforeExecuteCallback() != null
            ? agentExecutorConfig.beforeExecuteCallback().call(ctx)
            : Single.just(false);

    Runner runner = runnerBuilder.build();
    taskDisposables.add(
        skipExecution
            .flatMapPublisher(
                skip -> {
                  if (skip) {
                    cancel(ctx, eventQueue);
                    return Flowable.empty();
                  }
                  return Maybe.defer(
                          () -> {
                            return prepareSession(ctx, runner.appName(), runner.sessionService());
                          })
                      .flatMapPublisher(
                          session -> {
                            updater.startWork();
                            return runner.runAsync(
                                getUserId(ctx),
                                session.id(),
                                content,
                                agentExecutorConfig.runConfig());
                          });
                })
            .concatMap(
                event -> {
                  return p.process(event, ctx, agentExecutorConfig.afterEventCallback(), eventQueue)
                      .toFlowable();
                })
            .subscribe(
                // Events are already processed and enqueued in concatMap
                event -> {},
                error -> {
                  logger.error("Runner failed with {}", error);
                  var unused =
                      handleExecutionEnd(ctx, error, eventQueue)
                          .subscribe(
                              () -> cleanupTask(ctx.getTaskId()),
                              e -> {
                                logger.error("Failed to handle execution end", e);
                                cleanupTask(ctx.getTaskId());
                              });
                },
                () -> {
                  var unused =
                      handleExecutionEnd(ctx, null, eventQueue)
                          .subscribe(
                              () -> cleanupTask(ctx.getTaskId()),
                              e -> {
                                logger.error("Failed to handle execution end", e);
                                cleanupTask(ctx.getTaskId());
                              });
                }));
  }

  private Completable handleExecutionEnd(
      RequestContext ctx, Throwable error, EventQueue eventQueue) {
    TaskState state = error != null ? TaskState.FAILED : TaskState.COMPLETED;
    Message message = error != null ? failedMessage(ctx, error) : null;
    TaskStatusUpdateEvent initialEvent =
        new TaskStatusUpdateEvent.Builder()
            .taskId(ctx.getTaskId())
            .contextId(ctx.getContextId())
            .isFinal(true)
            .status(new TaskStatus(state, message, null))
            .build();
    Maybe<TaskStatusUpdateEvent> afterExecute =
        agentExecutorConfig.afterExecuteCallback() != null
            ? agentExecutorConfig.afterExecuteCallback().call(ctx, initialEvent)
            : Maybe.just(initialEvent);
    return afterExecute.doOnSuccess(event -> eventQueue.enqueueEvent(event)).ignoreElement();
  }

  private void cleanupTask(String taskId) {
    Disposable d = activeTasks.remove(taskId);
    if (d != null) {
      d.dispose(); // Stops all streams in the CompositeDisposable
    }
  }

  private String getUserId(RequestContext ctx) {
    return USER_ID_PREFIX + ctx.getContextId();
  }

  private Maybe<Session> prepareSession(
      RequestContext ctx, String appName, BaseSessionService service) {
    return service
        .getSession(appName, getUserId(ctx), ctx.getContextId(), Optional.empty())
        .switchIfEmpty(
            Maybe.defer(
                () -> {
                  return service.createSession(appName, getUserId(ctx)).toMaybe();
                }));
  }

  private static Message failedMessage(RequestContext context, Throwable e) {
    return new Message.Builder()
        .messageId(UUID.randomUUID().toString())
        .contextId(context.getContextId())
        .taskId(context.getTaskId())
        .role(Message.Role.AGENT)
        .parts(ImmutableList.of(new TextPart(e.getMessage())))
        .build();
  }

  // Processor that will process all events related to the one runner invocation.
  private static class EventProcessor {
    private final String artifactId;

    // All artifacts related to the invocation should have the same artifact id.
    private EventProcessor() {
      artifactId = UUID.randomUUID().toString();
    }

    private Maybe<TaskArtifactUpdateEvent> process(
        Event event,
        RequestContext ctx,
        Callbacks.AfterEventCallback callback,
        EventQueue eventQueue) {
      if (event.errorCode().isPresent()) {
        return Maybe.error(
            new InvalidAgentResponseError(
                null, // Uses default code -32006
                "Agent returned an error: " + event.errorCode().get(),
                null));
      }
      ImmutableList<Part<?>> parts = EventConverter.contentToParts(event.content());
      // Mark all parts as partial if the event is partial.
      if (event.partial().orElse(false)) {
        parts.forEach(
            part -> {
              Map<String, Object> metadata = part.getMetadata();
              metadata.put("adk_partial", true);
            });
      }
      TaskArtifactUpdateEvent initialEvent =
          new TaskArtifactUpdateEvent.Builder()
              .taskId(ctx.getTaskId())
              .contextId(ctx.getContextId())
              .artifact(
                  new Artifact.Builder()
                      .artifactId(artifactId)
                      .parts(parts)
                      .metadata(ImmutableMap.of())
                      .build())
              .build();
      Maybe<TaskArtifactUpdateEvent> afterEvent =
          callback != null ? callback.call(ctx, initialEvent, event) : Maybe.just(initialEvent);
      return afterEvent.doOnSuccess(
          evt -> {
            eventQueue.enqueueEvent(evt);
          });
    }
  }
}
