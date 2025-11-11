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

package com.google.adk;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for capturing and reporting telemetry data within the ADK. This class provides
 * methods to trace various aspects of the agent's execution, including tool calls, tool responses,
 * LLM interactions, and data handling. It leverages OpenTelemetry for tracing and logging for
 * detailed information. These traces can then be exported through the ADK Dev Server UI.
 */
public class Telemetry {

  private static final Logger log = LoggerFactory.getLogger(Telemetry.class);

  @SuppressWarnings("NonFinalStaticField")
  private static Tracer tracer = GlobalOpenTelemetry.getTracer("gcp.vertex.agent");

  private Telemetry() {}

  /** Sets the OpenTelemetry instance to be used for tracing. This is for testing purposes only. */
  public static void setTracerForTesting(Tracer tracer) {
    Telemetry.tracer = tracer;
  }

  /**
   * Traces tool call arguments.
   *
   * @param args The arguments to the tool call.
   */
  public static void traceToolCall(Map<String, Object> args) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceToolCall: No valid span in current context.");
      return;
    }

    span.setAttribute("gen_ai.system", "gcp.vertex.agent");
    try {
      span.setAttribute(
          "gcp.vertex.agent.tool_call_args", JsonBaseModel.getMapper().writeValueAsString(args));
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
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceToolResponse: No valid span in current context.");
      return;
    }

    span.setAttribute("gen_ai.system", "gcp.vertex.agent");
    span.setAttribute("gcp.vertex.agent.invocation_id", invocationContext.invocationId());
    span.setAttribute("gcp.vertex.agent.event_id", eventId);
    span.setAttribute("gcp.vertex.agent.tool_response", functionResponseEvent.toJson());

    // Setting empty llm request and response (as the AdkDevServer UI expects these)
    span.setAttribute("gcp.vertex.agent.llm_request", "{}");
    span.setAttribute("gcp.vertex.agent.llm_response", "{}");
    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("gcp.vertex.agent.session_id", invocationContext.session().id());
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

    List<Content> contentsList = new ArrayList<>();
    for (Content content : llmRequest.contents()) {
      ImmutableList<Part> filteredParts =
          content.parts().orElse(ImmutableList.of()).stream()
              .filter(part -> part.inlineData().isEmpty())
              .collect(toImmutableList());

      Content.Builder contentBuilder = Content.builder();
      content.role().ifPresent(contentBuilder::role);
      contentBuilder.parts(filteredParts);
      contentsList.add(contentBuilder.build());
    }
    result.put("contents", contentsList);
    return result;
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
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceCallLlm: No valid span in current context.");
      return;
    }

    span.setAttribute("gen_ai.system", "gcp.vertex.agent");
    llmRequest.model().ifPresent(modelName -> span.setAttribute("gen_ai.request.model", modelName));
    span.setAttribute("gcp.vertex.agent.invocation_id", invocationContext.invocationId());
    span.setAttribute("gcp.vertex.agent.event_id", eventId);

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("gcp.vertex.agent.session_id", invocationContext.session().id());
    } else {
      log.trace(
          "traceCallLlm: InvocationContext session or session ID is null, cannot set"
              + " gcp.vertex.agent.session_id");
    }

    try {
      span.setAttribute(
          "gcp.vertex.agent.llm_request",
          JsonBaseModel.getMapper().writeValueAsString(buildLlmRequestForTrace(llmRequest)));
      span.setAttribute("gcp.vertex.agent.llm_response", llmResponse.toJson());
    } catch (JsonProcessingException e) {
      log.warn("traceCallLlm: Failed to serialize LlmRequest or LlmResponse to JSON", e);
    }
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
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceSendData: No valid span in current context.");
      return;
    }

    span.setAttribute("gcp.vertex.agent.invocation_id", invocationContext.invocationId());
    if (eventId != null && !eventId.isEmpty()) {
      span.setAttribute("gcp.vertex.agent.event_id", eventId);
    }

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute("gcp.vertex.agent.session_id", invocationContext.session().id());
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
      span.setAttribute("gcp.vertex.agent.data", JsonBaseModel.toJsonString(dataList));
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
   * Executes a Flowable with an OpenTelemetry Scope active for its entire lifecycle.
   *
   * <p>This helper manages the OpenTelemetry Scope lifecycle for RxJava Flowables to ensure proper
   * context propagation across async boundaries. The scope remains active from when the Flowable is
   * returned through all operators until stream completion (onComplete, onError, or cancel).
   *
   * <p><b>Why not try-with-resources?</b> RxJava Flowables execute lazily - operators run at
   * subscription time, not at chain construction time. Using try-with-resources would close the
   * scope before the Flowable subscribes, causing Context.current() to return ROOT in nested
   * operations and breaking parent-child span relationships (fragmenting traces).
   *
   * <p>The scope is properly closed via doFinally when the stream terminates, ensuring no resource
   * leaks regardless of completion mode (success, error, or cancellation).
   *
   * @param spanContext The context containing the span to activate
   * @param span The span to end when the stream completes
   * @param flowableSupplier Supplier that creates the Flowable to execute with active scope
   * @param <T> The type of items emitted by the Flowable
   * @return Flowable with OpenTelemetry scope lifecycle management
   */
  @SuppressWarnings("MustBeClosedChecker") // Scope lifecycle managed by RxJava doFinally
  public static <T> Flowable<T> traceFlowable(
      Context spanContext, Span span, Supplier<Flowable<T>> flowableSupplier) {
    Scope scope = spanContext.makeCurrent();
    return flowableSupplier
        .get()
        .doFinally(
            () -> {
              scope.close();
              span.end();
            });
  }
}
