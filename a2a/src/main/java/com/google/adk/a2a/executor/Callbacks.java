package com.google.adk.a2a.executor;

import com.google.adk.events.Event;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

/** Functional interfaces for agent executor lifecycle callbacks. */
public final class Callbacks {

  private Callbacks() {}

  interface BeforeExecuteCallbackBase {}

  /** Async callback interface for actions to be performed before an execution is started. */
  @FunctionalInterface
  public interface BeforeExecuteCallback extends BeforeExecuteCallbackBase {
    /**
     * Callback which will be called before an execution is started. It can be used to instrument a
     * context or prevent the execution by returning an error.
     *
     * @param ctx the request context
     * @return a {@link Single} that completes with a boolean indicating whether the execution
     *     should be prevented
     */
    Single<Boolean> call(RequestContext ctx);
  }

  interface AfterExecuteCallbackBase {}

  /**
   * Async callback interface for actions to be performed after an execution is completed or failed.
   */
  @FunctionalInterface
  public interface AfterExecuteCallback extends AfterExecuteCallbackBase {
    /**
     * Callback which will be called after an execution resolved into a completed or failed task.
     * This gives an opportunity to enrich the event with additional metadata or log it.
     *
     * @param ctx the request context
     * @param finalUpdateEvent the final update event
     * @return a {@link Maybe} that completes when the callback is done
     */
    Maybe<TaskStatusUpdateEvent> call(RequestContext ctx, TaskStatusUpdateEvent finalUpdateEvent);
  }

  interface AfterEventCallbackBase {}

  /** Async callback interface for actions to be performed after an event is processed. */
  @FunctionalInterface
  public interface AfterEventCallback extends AfterEventCallbackBase {
    /**
     * Callback which will be called after an ADK event is successfully converted to an A2A event.
     * This gives an opportunity to enrich the event with additional metadata or abort the execution
     * by returning an error. The callback is not invoked for errors originating from ADK or event
     * processing.
     *
     * @param ctx the request context
     * @param processedEvent the processed task artifact update event
     * @param event the ADK event
     * @return a {@link Maybe} that completes when the callback is done
     */
    Maybe<TaskArtifactUpdateEvent> call(
        RequestContext ctx, TaskArtifactUpdateEvent processedEvent, Event event);
  }
}
