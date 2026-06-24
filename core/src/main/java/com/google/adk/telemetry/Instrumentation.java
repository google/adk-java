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

package com.google.adk.telemetry;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.tools.BaseTool;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableTransformer;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeSource;
import io.reactivex.rxjava3.core.MaybeTransformer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Unified context manager utility class for agent and tool execution telemetry in ADK. */
public final class Instrumentation {

  private static final Logger logger = LoggerFactory.getLogger(Instrumentation.class);

  private Instrumentation() {}

  /** Stores all telemetry related state. */
  public static final class TelemetryContext {
    private final Context otelContext;
    private @Nullable Event functionResponseEvent;

    public TelemetryContext(Context otelContext) {
      this.otelContext = otelContext;
    }

    public Context otelContext() {
      return otelContext;
    }

    public @Nullable Event functionResponseEvent() {
      return functionResponseEvent;
    }

    public void setFunctionResponseEvent(@Nullable Event functionResponseEvent) {
      this.functionResponseEvent = functionResponseEvent;
    }
  }

  /** Base class for AutoCloseable telemetry tracking scopes. */
  public abstract static class ClosableTelemetryScope implements AutoCloseable {
    protected final long startTimeNanos;
    protected final Span span;
    protected Scope scope;
    protected final TelemetryContext telemetryContext;
    protected @Nullable Throwable caughtError;
    protected final AtomicBoolean closed = new AtomicBoolean(false);

    @SuppressWarnings("MustBeClosedChecker")
    ClosableTelemetryScope(Span span) {
      this.startTimeNanos = System.nanoTime();
      this.span = span;
      this.scope = span.makeCurrent();
      this.telemetryContext = new TelemetryContext(Context.current());
    }

    @SuppressWarnings("MustBeClosedChecker")
    public void makeCurrent() {
      this.scope = span.makeCurrent();
    }

    public TelemetryContext context() {
      return telemetryContext;
    }

    public void setError(Throwable caughtError) {
      this.caughtError = caughtError;
      span.recordException(caughtError);
      span.setStatus(StatusCode.ERROR, caughtError.getMessage());
    }

    @Override
    public final void close() {
      if (closed.getAndSet(true)) {
        return;
      }
      try {
        beforeSpanEnd();
        span.end();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startTimeNanos);
        try {
          recordMetrics(elapsed, caughtError);
        } catch (RuntimeException e) {
          handleMetricsError(e);
        }
      } finally {
        scope.close();
      }
    }

    /** Hook for subclasses to run code before span ends. */
    protected void beforeSpanEnd() {}

    /** Hook for subclasses to record metrics. */
    protected abstract void recordMetrics(Duration elapsed, @Nullable Throwable error);

    /** Hook for subclasses to handle metrics recording errors. */
    protected abstract void handleMetricsError(RuntimeException e);
  }

  /** AutoCloseable telemetry tracking scope for agent invocations. */
  public static final class AgentInvocation extends ClosableTelemetryScope {
    private final BaseAgent agent;
    private final InvocationContext ctx;
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

    public AgentInvocation(InvocationContext ctx, BaseAgent agent, Context parentContext) {
      super(
          Tracing.getTracer()
              .spanBuilder("invoke_agent " + agent.name())
              .setParent(parentContext)
              .startSpan());
      this.agent = agent;
      this.ctx = ctx;
      Tracing.traceAgentInvocation(span, agent.name(), agent.description(), ctx);
    }

    public void addEvent(Event event) {
      events.add(event);
    }

    @Override
    protected void recordMetrics(Duration elapsed, @Nullable Throwable error) {
      Metrics.recordAgentInvocationDuration(agent.name(), elapsed, error);
      Metrics.recordAgentRequestSize(agent.name(), ctx.userContent().orElse(null));
      Metrics.recordAgentResponseSize(agent.name(), events);
      Metrics.recordAgentWorkflowSteps(agent.name(), events);
    }

    @Override
    protected void handleMetricsError(RuntimeException e) {
      logger.error("Failed to record agent metrics for agent {}", agent.name(), e);
    }
  }

  /** AutoCloseable telemetry tracking scope for tool executions. */
  public static final class ToolExecution extends ClosableTelemetryScope {
    private final BaseTool tool;
    private final BaseAgent agent;
    private final Map<String, Object> functionArgs;

    public ToolExecution(
        BaseTool tool, BaseAgent agent, Map<String, Object> functionArgs, Context parentContext) {
      super(
          Tracing.getTracer()
              .spanBuilder("execute_tool " + tool.name())
              .setParent(parentContext)
              .startSpan());
      this.tool = tool;
      this.agent = agent;
      this.functionArgs = functionArgs;
    }

    @Override
    protected void beforeSpanEnd() {
      Event responseEvent = caughtError == null ? context().functionResponseEvent() : null;
      Tracing.traceToolExecution(
          span,
          tool.name(),
          tool.description(),
          tool.getClass().getSimpleName(),
          functionArgs,
          responseEvent,
          caughtError);
    }

    @Override
    protected void recordMetrics(Duration elapsed, @Nullable Throwable error) {
      Metrics.recordToolExecutionDuration(tool.name(), agent.name(), elapsed, error);
      Metrics.recordToolRequestSize(tool.name(), agent.name(), functionArgs);
      Event responseEvent = error == null ? context().functionResponseEvent() : null;
      Metrics.recordToolResponseSize(tool.name(), agent.name(), responseEvent);
    }

    @Override
    protected void handleMetricsError(RuntimeException e) {
      logger.error("Failed to record tool execution duration for tool {}", tool.name(), e);
    }
  }

  /** A transformer that manages an AgentInvocation telemetry scope for RxJava streams. */
  public static final class AgentInvocationTransformer
      implements FlowableTransformer<Event, Event> {
    private final AgentInvocation agentInvocation;

    public AgentInvocationTransformer(
        InvocationContext ctx, BaseAgent agent, Context parentContext) {
      this.agentInvocation = new AgentInvocation(ctx, agent, parentContext);
    }

    @Override
    public Publisher<Event> apply(Flowable<Event> upstream) {
      return Flowable.using(
          () -> {
            agentInvocation.makeCurrent();
            return agentInvocation;
          },
          agentInvocation ->
              upstream.doOnNext(agentInvocation::addEvent).doOnError(agentInvocation::setError),
          AgentInvocation::close);
    }
  }

  /** A transformer that manages a ToolExecution telemetry scope for RxJava Maybe streams. */
  public static final class ToolExecutionTransformer implements MaybeTransformer<Event, Event> {
    private final BaseTool tool;
    private final BaseAgent agent;
    private final Map<String, Object> functionArgs;
    private final Context parentContext;

    public ToolExecutionTransformer(
        BaseTool tool, BaseAgent agent, Map<String, Object> functionArgs, Context parentContext) {
      this.tool = tool;
      this.agent = agent;
      this.functionArgs = functionArgs;
      this.parentContext = parentContext;
    }

    @Override
    public MaybeSource<Event> apply(Maybe<Event> upstream) {
      return Maybe.using(
          () -> new ToolExecution(tool, agent, functionArgs, parentContext),
          toolExecution ->
              upstream
                  .doOnSuccess(event -> toolExecution.context().setFunctionResponseEvent(event))
                  .doOnError(toolExecution::setError),
          ToolExecution::close);
    }
  }
}
