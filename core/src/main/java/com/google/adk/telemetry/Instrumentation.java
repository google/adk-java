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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
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

    /**
     * Constructs a new {@code TelemetryContext} with the given OpenTelemetry context.
     *
     * @param otelContext The OpenTelemetry context to store.
     */
    public TelemetryContext(Context otelContext) {
      this.otelContext = otelContext;
    }

    /**
     * Retrieves the stored OpenTelemetry context.
     *
     * @return The OpenTelemetry {@link Context}.
     */
    public Context otelContext() {
      return otelContext;
    }

    /**
     * Retrieves the function response event associated with the execution, if available.
     *
     * @return The function response {@link Event}, or {@code null} if not set.
     */
    public @Nullable Event functionResponseEvent() {
      return functionResponseEvent;
    }

    /**
     * Sets the function response event associated with the execution.
     *
     * @param functionResponseEvent The function response {@link Event} to store.
     */
    public void setFunctionResponseEvent(@Nullable Event functionResponseEvent) {
      this.functionResponseEvent = functionResponseEvent;
    }
  }

  /** Base class for AutoCloseable telemetry tracking scopes. */
  public abstract static class ClosableTelemetryScope implements AutoCloseable {
    /** The start time of the scope in nanoseconds. */
    protected final long startTimeNanos;

    /** The OpenTelemetry span associated with this scope. */
    protected final Span span;

    /** The OpenTelemetry scope associated with this span. */
    protected final Scope scope;

    /** The telemetry context for this scope. */
    protected final TelemetryContext telemetryContext;

    /** The error caught during execution, if any. */
    protected @Nullable Throwable caughtError;

    /** Whether this scope has been closed. */
    protected final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructs a new {@code ClosableTelemetryScope} with the given span.
     *
     * @param span The OpenTelemetry span to manage.
     */
    @SuppressWarnings("MustBeClosedChecker")
    ClosableTelemetryScope(Span span) {
      this.startTimeNanos = System.nanoTime();
      this.span = span;
      this.scope = span.makeCurrent();
      this.telemetryContext = new TelemetryContext(Context.current());
    }

    /**
     * Retrieves the telemetry context associated with this scope.
     *
     * @return The {@link TelemetryContext}.
     */
    public TelemetryContext context() {
      return telemetryContext;
    }

    /**
     * Records an error on the span and sets its status to error.
     *
     * @param caughtError The throwable caught during execution.
     */
    public void setError(Throwable caughtError) {
      this.caughtError = caughtError;
      span.recordException(caughtError);
      span.setStatus(StatusCode.ERROR, caughtError.getMessage());
    }

    /** Closes the scope and ends the underlying span, recording any applicable metrics. */
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

    /**
     * Constructs a new {@code AgentInvocation} telemetry scope.
     *
     * @param ctx The invocation context of the agent execution.
     * @param agent The agent being invoked.
     * @param parentContext The OpenTelemetry parent context.
     */
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

    /**
     * Retrieves the invocation context associated with this agent invocation.
     *
     * @return The {@link InvocationContext}.
     */
    public InvocationContext getCtx() {
      return ctx;
    }

    /**
     * Adds an event to the list of events tracked during this agent invocation.
     *
     * @param event The {@link Event} to add.
     */
    public void addEvent(Event event) {
      events.add(event);
    }

    /**
     * Records metrics for the agent invocation including duration, request size, response size, and
     * workflow steps.
     *
     * @param elapsed The total execution duration.
     * @param error The exception thrown during execution, if any.
     */
    @Override
    protected void recordMetrics(Duration elapsed, @Nullable Throwable error) {
      Metrics.recordAgentInvocationDuration(agent.name(), elapsed, error);
      Metrics.recordAgentRequestSize(agent.name(), ctx.userContent().orElse(null));
      Metrics.recordAgentResponseSize(agent.name(), events);
      Metrics.recordAgentWorkflowSteps(agent.name(), events);
    }

    /**
     * Handles errors that occur while recording metrics for the agent invocation.
     *
     * @param e The runtime exception encountered during metrics recording.
     */
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

    /**
     * Constructs a new {@code ToolExecution} telemetry scope.
     *
     * @param tool The tool being executed.
     * @param agent The agent invoking the tool.
     * @param functionArgs The arguments passed to the tool.
     * @param parentContext The OpenTelemetry parent context.
     */
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

    /** Traces the tool execution attributes on the span before it ends. */
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

    /**
     * Records metrics for the tool execution including duration, request size, and response size.
     *
     * @param elapsed The total execution duration.
     * @param error The exception thrown during execution, if any.
     */
    @Override
    protected void recordMetrics(Duration elapsed, @Nullable Throwable error) {
      Metrics.recordToolExecutionDuration(tool.name(), agent.name(), elapsed, error);
      Metrics.recordToolRequestSize(tool.name(), agent.name(), functionArgs);
      Event responseEvent = error == null ? context().functionResponseEvent() : null;
      Metrics.recordToolResponseSize(tool.name(), agent.name(), responseEvent);
    }

    /**
     * Handles errors that occur while recording metrics for the tool execution.
     *
     * @param e The runtime exception encountered during metrics recording.
     */
    @Override
    protected void handleMetricsError(RuntimeException e) {
      logger.error("Failed to record tool execution duration for tool {}", tool.name(), e);
    }
  }

  /**
   * Creates an AgentInvocation context to record agent invocation telemetry.
   *
   * @deprecated Use the version with explicit parent context instead. This method will be removed
   *     once all callers are updated.
   */
  @Deprecated // Use the version with explicit parent context instead.
  public static AgentInvocation recordAgentInvocation(InvocationContext ctx, BaseAgent agent) {
    return recordAgentInvocation(ctx, agent, Context.current());
  }

  /**
   * Creates an {@link AgentInvocation} context to record agent invocation telemetry with an
   * explicit parent context.
   *
   * @param ctx The invocation context of the agent execution.
   * @param agent The agent being invoked.
   * @param parentContext The OpenTelemetry parent context.
   * @return A new {@link AgentInvocation} scope.
   */
  public static AgentInvocation recordAgentInvocation(
      InvocationContext ctx, BaseAgent agent, Context parentContext) {
    return new AgentInvocation(ctx, agent, parentContext);
  }

  /** Creates a ToolExecution context to record tool execution telemetry. */
  public static ToolExecution recordToolExecution(
      BaseTool tool, BaseAgent agent, Map<String, Object> functionArgs) {
    return recordToolExecution(tool, agent, functionArgs, Context.current());
  }

  /**
   * Creates a {@link ToolExecution} context to record tool execution telemetry with an explicit
   * parent context.
   *
   * @param tool The tool being executed.
   * @param agent The agent invoking the tool.
   * @param functionArgs The arguments passed to the tool.
   * @param parentContext The OpenTelemetry parent context.
   * @return A new {@link ToolExecution} scope.
   */
  public static ToolExecution recordToolExecution(
      BaseTool tool, BaseAgent agent, Map<String, Object> functionArgs, Context parentContext) {
    return new ToolExecution(tool, agent, functionArgs, parentContext);
  }
}
