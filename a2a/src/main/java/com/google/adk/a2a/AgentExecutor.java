package com.google.adk.a2a;

import com.google.adk.a2a.converters.EventConverter;
import com.google.adk.a2a.converters.PartConverter;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
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
import io.a2a.spec.InvalidAgentResponseError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
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
  private static final RunConfig DEFAULT_RUN_CONFIG =
      RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.NONE).setMaxLlmCalls(20).build();

  private final Runner runner;
  private final Map<String, Disposable> activeTasks = new ConcurrentHashMap<>();

  private AgentExecutor(Runner runner) {
    this.runner = runner;
  }

  /** Builder for {@link AgentExecutor}. */
  public static class Builder {
    private Runner runner;

    @CanIgnoreReturnValue
    public Builder runner(Runner runner) {
      this.runner = runner;
      return this;
    }

    @CanIgnoreReturnValue
    public AgentExecutor build() {
      if (runner == null) {
        throw new IllegalStateException("Runner must be provided.");
      }
      return new AgentExecutor(runner);
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

    taskDisposables.add(
        prepareSession(ctx, runner.sessionService())
            .flatMapPublisher(
                session -> {
                  updater.startWork();
                  return runner.runAsync(getUserId(ctx), session.id(), content, DEFAULT_RUN_CONFIG);
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

  private Maybe<Session> prepareSession(RequestContext ctx, BaseSessionService service) {
    return service
        .getSession(runner.appName(), getUserId(ctx), ctx.getContextId(), Optional.empty())
        .switchIfEmpty(
            Maybe.defer(
                () -> {
                  return service.createSession(runner.appName(), getUserId(ctx)).toMaybe();
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

    // All artifacts related to the invocation should have the same artifact id.
    private EventProcessor() {
      artifactId = UUID.randomUUID().toString();
    }

    private final String artifactId;

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

      updater.addArtifact(parts, artifactId, null, ImmutableMap.of());
    }
  }
}
