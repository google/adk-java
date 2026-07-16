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
package com.google.adk.a2a.v1;

import static java.util.Objects.requireNonNull;

import com.google.adk.a2a.v1.Callbacks.AfterEventCallback;
import com.google.adk.a2a.v1.converters.EventConverter;
import com.google.adk.a2a.v1.converters.PartConverter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.InvalidAgentResponseError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AgentExecutor} implementation that executes ADK agents and converts ADK events to A2A
 * events.
 */
public class AgentExecutor implements org.a2aproject.sdk.server.agentexecution.AgentExecutor {

  private static final Logger logger = LoggerFactory.getLogger(AgentExecutor.class);
  private static final String USER_ID_PREFIX = "A2A_USER_";
  private static final String A2A_METADATA_KEY = "a2a_metadata";

  private final Runner runner;
  private final AgentExecutorConfig agentExecutorConfig;
  private final ConcurrentMap<String, CompositeDisposable> activeTasks = new ConcurrentHashMap<>();

  @SuppressWarnings("deprecation")
  private AgentExecutor(
      App app,
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      Runner runner,
      AgentExecutorConfig agentExecutorConfig) {
    requireNonNull(agentExecutorConfig);
    this.agentExecutorConfig = agentExecutorConfig;
    if (runner != null) {
      this.runner = runner;
      return;
    }
    Runner.Builder runnerBuilder = Runner.builder();
    if (app != null) {
      if (appName != null) {
        runnerBuilder.appName(appName);
      }
      runnerBuilder.app(app);
    } else {
      runnerBuilder.agent(agent).appName(appName).plugins(plugins);
    }
    this.runner =
        runnerBuilder
            .artifactService(artifactService)
            .sessionService(sessionService)
            .memoryService(memoryService)
            .build();
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
    private Runner runner;

    @CanIgnoreReturnValue
    public Builder runner(Runner runner) {
      this.runner = runner;
      return this;
    }

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
          runner,
          agentExecutorConfig);
    }
  }

  @Override
  public void cancel(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    emitter.cancel();
    cleanupTask(ctx.getTaskId());
  }

  @Override
  public void execute(RequestContext ctx, AgentEmitter emitter) throws A2AError {
    Message message = ctx.getMessage();
    if (message == null) {
      throw new IllegalArgumentException("Message cannot be null");
    }
    // Submits a new task if there is no active task.
    if (ctx.getTask() == null) {
      emitter.submit();
    }
    // Group all reactive work for this task into one container
    CompositeDisposable taskDisposables = new CompositeDisposable();
    // Check if the task with the task id is already running, put if absent.
    if (activeTasks.putIfAbsent(ctx.getTaskId(), taskDisposables) != null) {
      throw new IllegalStateException(String.format("Task %s already running", ctx.getTaskId()));
    }
    EventProcessor p = new EventProcessor(agentExecutorConfig.outputMode());
    Content content = PartConverter.messageToContent(message);
    Single<Boolean> skipExecution =
        agentExecutorConfig.beforeExecuteCallback() != null
            ? agentExecutorConfig.beforeExecuteCallback().call(ctx)
            : Single.just(false);

    Runner runner = this.runner;
    // We block the execution thread until the RxJava execution chain terminates.
    // This is because the A2A framework expects synchronous execution from this call,
    // and if we return immediately, the task stays in WORKING state indefinitely
    // because the queue gets closed before completed events are processed.
    CountDownLatch latch = new CountDownLatch(1);
    taskDisposables.add(
        skipExecution
            .flatMapPublisher(
                skip -> {
                  if (skip) {
                    cancel(ctx, emitter);
                    return Flowable.empty();
                  }
                  return Maybe.defer(
                          () -> {
                            return prepareSession(ctx, runner.appName(), runner.sessionService());
                          })
                      .flatMapPublisher(
                          session -> {
                            emitter.startWork();
                            return runner.runAsync(
                                getUserId(ctx),
                                session.id(),
                                content,
                                runConfigWithA2aMetadata(ctx));
                          });
                })
            .concatMap(
                event -> {
                  return p.process(event, ctx, agentExecutorConfig.afterEventCallback(), emitter)
                      .toFlowable();
                })
            // Ignore all events from the runner, since they are already processed.
            .ignoreElements()
            .materialize()
            .flatMapCompletable(
                notification -> {
                  Throwable error = notification.getError();
                  if (error != null) {
                    logger.error("Runner failed to execute", error);
                  }
                  return handleExecutionEnd(ctx, error, emitter);
                })
            .doFinally(
                () -> {
                  cleanupTask(ctx.getTaskId());
                  latch.countDown();
                })
            .subscribe(
                () -> {},
                error -> {
                  logger.error("Failed to handle execution end", error);
                }));
    try {
      latch.await();
    } catch (InterruptedException e) {
      logger.warn("AgentExecutor.execute interrupted for task {}", ctx.getTaskId(), e);
      Thread.currentThread().interrupt();
    }
  }

  private Completable handleExecutionEnd(
      RequestContext ctx, Throwable error, AgentEmitter emitter) {
    TaskState state = error != null ? TaskState.TASK_STATE_FAILED : TaskState.TASK_STATE_COMPLETED;
    Message message = error != null ? failedMessage(ctx, error) : null;
    TaskStatusUpdateEvent initialEvent =
        TaskStatusUpdateEvent.builder()
            .taskId(ctx.getTaskId())
            .contextId(ctx.getContextId())
            .status(new TaskStatus(state, message, null))
            .build();
    Maybe<TaskStatusUpdateEvent> afterExecute =
        agentExecutorConfig.afterExecuteCallback() != null
            ? agentExecutorConfig.afterExecuteCallback().call(ctx, initialEvent)
            : Maybe.just(initialEvent);
    return afterExecute.doOnSuccess(event -> emitter.emitEvent(event)).ignoreElement();
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

  /**
   * Returns the configured run config enriched with the caller's incoming A2A request metadata
   * under the {@code a2a_metadata} key, so downstream processing can read it via {@link
   * RunConfig#customMetadata()}.
   */
  private RunConfig runConfigWithA2aMetadata(RequestContext ctx) {
    RunConfig runConfig = agentExecutorConfig.runConfig();
    Map<String, Object> requestMetadata = ctx.getMetadata();
    if (requestMetadata == null || requestMetadata.isEmpty()) {
      return runConfig;
    }
    Map<String, Object> customMetadata = new HashMap<>(runConfig.customMetadata());
    customMetadata.put(A2A_METADATA_KEY, requestMetadata);
    return runConfig.toBuilder().customMetadata(customMetadata).build();
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
    return Message.builder()
        .contextId(context.getContextId())
        .taskId(context.getTaskId())
        .role(Message.Role.ROLE_AGENT)
        .parts(new TextPart(e.getMessage() != null ? e.getMessage() : "Unknown error"))
        .build();
  }

  // Processor that will process all events related to the one runner invocation.
  private static class EventProcessor {
    private final String runArtifactId;
    private final AgentExecutorConfig.OutputMode outputMode;
    private final Map<String, String> lastAgentPartialArtifact = new ConcurrentHashMap<>();
    private boolean isFirstEventForRun = true;

    // All artifacts related to the invocation should have the same artifact id.
    private EventProcessor(AgentExecutorConfig.OutputMode outputMode) {
      this.runArtifactId = UUID.randomUUID().toString();
      this.outputMode = outputMode;
    }

    private Maybe<TaskArtifactUpdateEvent> process(
        Event event, RequestContext ctx, AfterEventCallback callback, AgentEmitter emitter) {
      if (event.errorCode().isPresent()) {
        return Maybe.error(
            new InvalidAgentResponseError(
                null, // Uses default code -32006
                "Agent returned an error: " + event.errorCode().get(),
                null));
      }
      ImmutableList<Part<?>> parts =
          EventConverter.contentToParts(event.content(), event.partial().orElse(false));
      Map<String, Object> metadata = new HashMap<>();
      if (event.customMetadata().isPresent()) {
        for (CustomMetadata cm : event.customMetadata().get()) {
          if (cm.key().isPresent() && cm.stringValue().isPresent()) {
            metadata.put(cm.key().get(), cm.stringValue().get());
          }
        }
      }

      boolean append = !isFirstEventForRun;
      isFirstEventForRun = false;
      boolean lastChunk = !event.partial().orElse(false);
      String artifactId = runArtifactId;

      if (outputMode == AgentExecutorConfig.OutputMode.ARTIFACT_PER_EVENT) {
        String author = event.author();
        boolean isPartial = event.partial().orElse(false);

        if (lastAgentPartialArtifact.containsKey(author)) {
          artifactId = lastAgentPartialArtifact.get(author);
          append = isPartial;
        } else {
          artifactId = UUID.randomUUID().toString();
          append = isPartial;
        }

        lastChunk = !isPartial;

        if (isPartial) {
          lastAgentPartialArtifact.put(author, artifactId);
        } else {
          lastAgentPartialArtifact.remove(author);
        }
      }

      TaskArtifactUpdateEvent initialEvent =
          TaskArtifactUpdateEvent.builder()
              .taskId(ctx.getTaskId())
              .contextId(ctx.getContextId())
              .lastChunk(lastChunk)
              .append(append)
              .artifact(
                  Artifact.builder().artifactId(artifactId).parts(parts).metadata(metadata).build())
              .build();

      Maybe<TaskArtifactUpdateEvent> afterEvent =
          callback != null ? callback.call(ctx, initialEvent, event) : Maybe.just(initialEvent);
      return afterEvent.doOnSuccess(
          finalEvent -> {
            emitter.emitEvent(finalEvent);
          });
    }
  }
}
