package com.google.adk.a2a;

import com.google.adk.a2a.converters.EventConverter;
import com.google.adk.a2a.converters.PartConverter;
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
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.InvalidAgentResponseError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.HashMap;
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

  /**
   * Output mode for the agent executor.
   *
   * <p>ARTIFACT_PER_RUN: The agent executor will return one artifact per run.
   *
   * <p>ARTIFACT_PER_EVENT: The agent executor will return one artifact per event.
   */
  public enum OutputMode {
    ARTIFACT_PER_RUN,
    ARTIFACT_PER_EVENT
  }

  private static final Logger logger = LoggerFactory.getLogger(AgentExecutor.class);
  private static final String USER_ID_PREFIX = "A2A_USER_";
  private static final RunConfig DEFAULT_RUN_CONFIG =
      RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.NONE).setMaxLlmCalls(20).build();

  private final Map<String, Disposable> activeTasks = new ConcurrentHashMap<>();
  private final Runner.Builder runnerBuilder;
  private final RunConfig runConfig;
  private final OutputMode outputMode;

  private AgentExecutor(
      App app,
      BaseAgent agent,
      String appName,
      BaseArtifactService artifactService,
      BaseSessionService sessionService,
      BaseMemoryService memoryService,
      List<? extends Plugin> plugins,
      RunConfig runConfig,
      OutputMode outputMode) {
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
    this.runConfig = runConfig == null ? DEFAULT_RUN_CONFIG : runConfig;
    this.outputMode = outputMode == null ? OutputMode.ARTIFACT_PER_RUN : outputMode;
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
    private RunConfig runConfig;
    private OutputMode outputMode;

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

    @CanIgnoreReturnValue
    public Builder runConfig(RunConfig runConfig) {
      this.runConfig = runConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputMode(OutputMode outputMode) {
      this.outputMode = outputMode;
      return this;
    }

    @CanIgnoreReturnValue
    public AgentExecutor build() {
      return new AgentExecutor(
          app,
          agent,
          appName,
          artifactService,
          sessionService,
          memoryService,
          plugins,
          runConfig,
          outputMode);
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

    EventProcessor p = new EventProcessor(outputMode);
    Content content = PartConverter.messageToContent(message);
    Runner runner = runnerBuilder.build();

    taskDisposables.add(
        prepareSession(ctx, runner.appName(), runner.sessionService())
            .flatMapPublisher(
                session -> {
                  updater.startWork();
                  return runner.runAsync(getUserId(ctx), session.id(), content, runConfig);
                })
            .subscribe(
                event -> {
                  p.process(event, updater);
                },
                error -> {
                  logger.error("Runner failed with {}", error);
                  updater.fail(failedMessage(ctx, error));
                  cleanupTask(ctx.getTaskId());
                },
                () -> {
                  updater.complete();
                  cleanupTask(ctx.getTaskId());
                }));
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
    private final String runArtifactId;
    private final OutputMode outputMode;
    private final Map<String, String> lastAgentPartialArtifact = new ConcurrentHashMap<>();

    // All artifacts related to the invocation should have the same artifact id.
    private EventProcessor(OutputMode outputMode) {
      this.runArtifactId = UUID.randomUUID().toString();
      this.outputMode = outputMode;
    }

    private void process(Event event, TaskUpdater updater) {
      if (event.errorCode().isPresent()) {
        throw new InvalidAgentResponseError(
            null, // Uses default code -32006
            "Agent returned an error: " + event.errorCode().get(),
            null);
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

      Map<String, Object> metadata = new HashMap<>();
      if (event.customMetadata().isPresent()) {
        for (CustomMetadata cm : event.customMetadata().get()) {
          if (cm.key().isPresent() && cm.stringValue().isPresent()) {
            metadata.put(cm.key().get(), cm.stringValue().get());
          }
        }
      }

      Boolean append = false;
      Boolean lastChunk = null;
      String artifactId = runArtifactId;

      if (outputMode == OutputMode.ARTIFACT_PER_EVENT) {
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

      updater.addArtifact(parts, artifactId, null, metadata, append, lastChunk);
    }
  }
}
