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

package com.google.adk.telemetry;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.functions.Action;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for capturing and reporting telemetry data within the ADK. This class provides
 * methods to trace various aspects of the agent's execution, including tool calls, tool responses,
 * LLM interactions, and data handling. It leverages OpenTelemetry for tracing and logging for
 * detailed information. These traces can then be exported through the ADK Dev Server UI.
 */
public class Tracing {

  private static final Logger log = LoggerFactory.getLogger(Tracing.class);

  @SuppressWarnings("NonFinalStaticField")
  private static Tracer tracer = GlobalOpenTelemetry.getTracer("com.google.adk");

  private static final boolean CAPTURE_MESSAGE_CONTENT_IN_SPANS =
      Boolean.parseBoolean(
          System.getenv().getOrDefault("ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS", "true"));

  private Tracing() {}

  /** Sets the OpenTelemetry instance to be used for tracing. This is for testing purposes only. */
  public static void setTracerForTesting(Tracer tracer) {
    Tracing.tracer = tracer;
  }

  /** Starts a new span with common ADK attributes from the invocation context. */
  public static Span startSpan(String spanName, InvocationContext invocationContext) {
    Span span = tracer.spanBuilder(spanName).setParent(Context.current()).startSpan();
    span.setAttribute("gen_ai.system", "com.google.adk");
    if (invocationContext != null) {
      setInvocationAttributes(span, invocationContext);
    }
    return span;
  }

  private static void traceInSpan(Span span, Runnable runnable, boolean endSpan) {
    traceInSpan(
        span,
        () -> {
          runnable.run();
          return null;
        },
        endSpan);
  }

  @CanIgnoreReturnValue
  private static <T> T traceInSpan(Span span, Supplier<T> supplier, boolean endSpan) {
    try (Scope scope = span.makeCurrent()) {
      return supplier.get();
    } catch (Throwable t) {
      span.recordException(t);
      if (endSpan) {
        span.setStatus(StatusCode.ERROR);
      }
      throw t;
    } finally {
      if (endSpan) {
        span.end();
      }
    }
  }

  /**
   * Traces tool call arguments.
   *
   * @param args The arguments to the tool call.
   */
  public static void traceToolCall(Map<String, Object> args) {
    Span span = getValidSpan("traceToolCall");
    if (span == null) {
      return;
    }

    span.setAttribute("gen_ai.system", "com.google.adk");
    try {
      span.setAttribute("adk.tool_call_args", JsonBaseModel.getMapper().writeValueAsString(args));
    } catch (JsonProcessingException e) {
      log.warn("traceToolCall: Failed to serialize tool call args to JSON", e);
    }
  }

  /**
   * Traces tool response event.
   *
   * @param invocationContext The invocation context for the current agent run.
   * @param eventId The ID of the event.
   * @param functionResponseEvent The function response event.
   */
  public static void traceToolResponse(
      InvocationContext invocationContext, String eventId, Event functionResponseEvent) {
    Span span = getValidSpan("traceToolResponse");
    if (span == null) {
      return;
    }

    span.setAttribute("gen_ai.system", "com.google.adk");
    setInvocationAttributes(span, invocationContext);
    span.setAttribute("adk.event_id", eventId);
    span.setAttribute("adk.tool_response", functionResponseEvent.toJson());

    // Setting empty llm request and response (as the AdkDevServer UI expects these)
    span.setAttribute("adk.llm_request", "{}");
    span.setAttribute("adk.llm_response", "{}");
  }

  /**
   * Traces tool response event within a new span.
   *
   * @param spanName The name of the span to create.
   * @param invocationContext The invocation context for the current agent run.
   * @param eventId The ID of the event.
   * @param functionResponseEvent The function response event.
   */
  public static void traceToolResponse(
      String spanName,
      InvocationContext invocationContext,
      String eventId,
      Event functionResponseEvent) {
    Span span = startSpan(spanName, invocationContext);
    try (Scope scope = span.makeCurrent()) {
      traceToolResponse(invocationContext, eventId, functionResponseEvent);
    } catch (Throwable t) {
      span.recordException(t);
      span.setStatus(StatusCode.ERROR);
      throw t;
    } finally {
      span.end();
    }
  }

  /**
   * Traces a call to the LLM.
   *
   * @param invocationContext The invocation context.
   * @param eventId The ID of the event associated with this LLM call/response.
   * @param llmRequest The LLM request object.
   * @param llmResponse The LLM response object.
   */
  public static void traceCallLlm(
      InvocationContext invocationContext,
      String eventId,
      LlmRequest llmRequest,
      LlmResponse llmResponse) {
    Span span = getValidSpan("traceCallLlm");
    if (span == null) {
      return;
    }

    span.setAttribute("gen_ai.system", "com.google.adk");
    llmRequest.model().ifPresent(modelName -> span.setAttribute("gen_ai.request.model", modelName));
    setInvocationAttributes(span, invocationContext);
    span.setAttribute("adk.event_id", eventId);

    if (CAPTURE_MESSAGE_CONTENT_IN_SPANS) {
      try {
        span.setAttribute(
            "adk.llm_request",
            JsonBaseModel.getMapper().writeValueAsString(buildLlmRequestForTrace(llmRequest)));
        span.setAttribute("adk.llm_response", llmResponse.toJson());
      } catch (JsonProcessingException e) {
        log.warn("traceCallLlm: Failed to serialize LlmRequest or LlmResponse to JSON", e);
      }
    } else {
      span.setAttribute("adk.llm_request", "{}");
      span.setAttribute("adk.llm_response", "{}");
    }
  }

  /**
   * Traces a call to the LLM within an existing span.
   *
   * @param span The span to use for tracing.
   * @param invocationContext The invocation context.
   * @param eventId The ID of the event associated with this LLM call/response.
   * @param llmRequest The LLM request object.
   * @param llmResponse The LLM response object.
   */
  public static void traceCallLlm(
      Span span,
      InvocationContext invocationContext,
      String eventId,
      LlmRequest llmRequest,
      LlmResponse llmResponse) {
    traceInSpan(
        span, () -> traceCallLlm(invocationContext, eventId, llmRequest, llmResponse), false);
  }

  /**
   * Traces the sending of data within an existing span.
   *
   * @param span The span to use for tracing.
   * @param invocationContext The invocation context.
   * @param eventId The ID of the event, if applicable.
   * @param data A list of content objects being sent.
   */
  public static void traceSendData(
      Span span, InvocationContext invocationContext, String eventId, List<Content> data) {
    traceInSpan(span, () -> traceSendData(invocationContext, eventId, data), false);
  }

  /**
   * Traces the sending of data (history or new content) to the agent/model.
   *
   * @param invocationContext The invocation context.
   * @param eventId The ID of the event, if applicable.
   * @param data A list of content objects being sent.
   */
  public static void traceSendData(
      InvocationContext invocationContext, String eventId, List<Content> data) {
    Span span = getValidSpan("traceSendData");
    if (span == null) {
      return;
    }

    setInvocationAttributes(span, invocationContext);
    if (eventId != null && !eventId.isEmpty()) {
      span.setAttribute("adk.event_id", eventId);
    }

    try {
      List<Map<String, Object>> dataList = new ArrayList<>();
      if (data != null) {
        for (Content content : data) {
          if (content != null) {
            dataList.add(
                JsonBaseModel.getMapper()
                    .convertValue(content, new TypeReference<Map<String, Object>>() {}));
          }
        }
      }
      span.setAttribute("adk.data", JsonBaseModel.toJsonString(dataList));
    } catch (IllegalStateException e) {
      log.warn("traceSendData: Failed to serialize data to JSON", e);
    }
  }

  /**
   * Gets the tracer.
   *
   * @return The tracer.
   */
  public static Tracer getTracer() {
    return tracer;
  }

  /**
   * Traces a Flowable by starting a new span with the given invocation context.
   *
   * @param spanName The name of the span.
   * @param invocationContext The invocation context.
   * @param flowableSupplier Supplier that creates the Flowable.
   */
  @SuppressWarnings("MustBeClosedChecker")
  public static <T> Flowable<T> traceFlowable(
      String spanName,
      InvocationContext invocationContext,
      Supplier<Flowable<T>> flowableSupplier) {
    return Flowable.defer(
        () -> {
          TraceState state = TraceState.start(spanName, invocationContext);
          try {
            return flowableSupplier.get().doOnError(state).doFinally(state);
          } catch (Throwable t) {
            state.accept(t);
            state.run();
            return Flowable.error(t);
          }
        });
  }

  /** Similar to traceFlowable for Maybe. */
  @SuppressWarnings("MustBeClosedChecker")
  public static <T> Maybe<T> traceMaybe(
      String spanName, InvocationContext invocationContext, Supplier<Maybe<T>> maybeSupplier) {
    return traceFlowable(spanName, invocationContext, () -> maybeSupplier.get().toFlowable())
        .singleElement();
  }

  /** Similar to traceFlowable for Completable with the given invocation context. */
  @SuppressWarnings("MustBeClosedChecker")
  public static Completable traceCompletable(
      String spanName,
      InvocationContext invocationContext,
      Supplier<Completable> completableSupplier) {
    return traceFlowable(spanName, invocationContext, () -> completableSupplier.get().toFlowable())
        .ignoreElements();
  }

  private static @Nullable Span getValidSpan(String methodName) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("{}: No valid span in current context.", methodName);
      return null;
    }
    return span;
  }

  private static void setInvocationAttributes(Span span, InvocationContext invocationContext) {
    span.setAttribute("adk.invocation_id", invocationContext.invocationId());
    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("adk.session_id", invocationContext.session().id());
    }
  }

  /**
   * Builds a dictionary representation of the LLM request for tracing. {@code GenerationConfig} is
   * included as a whole. For other fields like {@code Content}, parts that cannot be easily
   * serialized or are not needed for the trace (e.g., inlineData) are excluded.
   *
   * @param llmRequest The LlmRequest object.
   * @return A Map representation of the LLM request for tracing.
   */
  private static Map<String, Object> buildLlmRequestForTrace(LlmRequest llmRequest) {
    Map<String, Object> result = new HashMap<>();
    result.put("model", llmRequest.model().orElse(null));
    llmRequest.config().ifPresent(config -> result.put("config", config));

    ImmutableList<Content> contentsList =
        llmRequest.contents().stream()
            .map(
                content -> {
                  ImmutableList<Part> filteredParts =
                      content.parts().orElse(ImmutableList.of()).stream()
                          .filter(part -> part.inlineData().isEmpty())
                          .collect(toImmutableList());
                  Content.Builder contentBuilder = Content.builder().parts(filteredParts);
                  content.role().ifPresent(contentBuilder::role);
                  return contentBuilder.build();
                })
            .collect(toImmutableList());
    result.put("contents", contentsList);
    return result;
  }

  /** Internal helper to manage span and scope for RxJava types. */
  private static class TraceState
      implements Action, io.reactivex.rxjava3.functions.Consumer<Throwable> {
    private final Span span;
    private final Scope scope;

    private TraceState(Span span, Scope scope) {
      this.span = span;
      this.scope = scope;
    }

    @Override
    public void run() {
      scope.close();
      span.end();
    }

    @Override
    public void accept(Throwable t) {
      span.recordException(t);
      span.setStatus(StatusCode.ERROR);
    }

    @SuppressWarnings("MustBeClosedChecker")
    static TraceState start(String spanName, InvocationContext invocationContext) {
      Span span = startSpan(spanName, invocationContext);
      Scope scope = Context.current().with(span).makeCurrent();
      return new TraceState(span, scope);
    }
  }
}
