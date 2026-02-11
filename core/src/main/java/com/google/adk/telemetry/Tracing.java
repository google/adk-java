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
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
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

  private static final AttributeKey<List<String>> GEN_AI_RESPONSE_FINISH_REASONS =
      AttributeKey.stringArrayKey("gen_ai.response.finish_reasons");

  private static final AttributeKey<String> GEN_AI_OPERATION_NAME =
      AttributeKey.stringKey("gen_ai.operation.name");
  private static final AttributeKey<String> GEN_AI_AGENT_DESCRIPTION =
      AttributeKey.stringKey("gen_ai.agent.description");
  private static final AttributeKey<String> GEN_AI_AGENT_NAME =
      AttributeKey.stringKey("gen_ai.agent.name");
  private static final AttributeKey<String> GEN_AI_CONVERSATION_ID =
      AttributeKey.stringKey("gen_ai.conversation.id");
  private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
  private static final AttributeKey<String> GEN_AI_TOOL_CALL_ID =
      AttributeKey.stringKey("gen_ai.tool_call.id");
  private static final AttributeKey<String> GEN_AI_TOOL_DESCRIPTION =
      AttributeKey.stringKey("gen_ai.tool.description");
  private static final AttributeKey<String> GEN_AI_TOOL_NAME =
      AttributeKey.stringKey("gen_ai.tool.name");
  private static final AttributeKey<String> GEN_AI_TOOL_TYPE =
      AttributeKey.stringKey("gen_ai.tool.type");
  private static final AttributeKey<String> GEN_AI_REQUEST_MODEL =
      AttributeKey.stringKey("gen_ai.request.model");
  private static final AttributeKey<Double> GEN_AI_REQUEST_TOP_P =
      AttributeKey.doubleKey("gen_ai.request.top_p");
  private static final AttributeKey<Long> GEN_AI_REQUEST_MAX_TOKENS =
      AttributeKey.longKey("gen_ai.request.max_tokens");
  private static final AttributeKey<Long> GEN_AI_USAGE_INPUT_TOKENS =
      AttributeKey.longKey("gen_ai.usage.input_tokens");
  private static final AttributeKey<Long> GEN_AI_USAGE_OUTPUT_TOKENS =
      AttributeKey.longKey("gen_ai.usage.output_tokens");

  private static final AttributeKey<String> ADK_TOOL_CALL_ARGS =
      AttributeKey.stringKey("gcp.vertex.agent.tool_call_args");
  private static final AttributeKey<String> ADK_LLM_REQUEST =
      AttributeKey.stringKey("gcp.vertex.agent.llm_request");
  private static final AttributeKey<String> ADK_LLM_RESPONSE =
      AttributeKey.stringKey("gcp.vertex.agent.llm_response");
  private static final AttributeKey<String> ADK_INVOCATION_ID =
      AttributeKey.stringKey("gcp.vertex.agent.invocation_id");
  private static final AttributeKey<String> ADK_EVENT_ID =
      AttributeKey.stringKey("gcp.vertex.agent.event_id");
  private static final AttributeKey<String> ADK_TOOL_RESPONSE =
      AttributeKey.stringKey("gcp.vertex.agent.tool_response");
  private static final AttributeKey<String> ADK_SESSION_ID =
      AttributeKey.stringKey("gcp.vertex.agent.session_id");
  private static final AttributeKey<String> ADK_DATA =
      AttributeKey.stringKey("gcp.vertex.agent.data");

  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<Map<String, Object>>() {};

  @SuppressWarnings("NonFinalStaticField")
  private static Tracer tracer = GlobalOpenTelemetry.getTracer("gcp.vertex.agent");

  private static final boolean CAPTURE_MESSAGE_CONTENT_IN_SPANS =
      Boolean.parseBoolean(
          System.getenv().getOrDefault("ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS", "true"));

  private Tracing() {}

  /** Sets the OpenTelemetry instance to be used for tracing. This is for testing purposes only. */
  public static void setTracerForTesting(Tracer tracer) {
    Tracing.tracer = tracer;
  }

  /**
   * Sets span attributes immediately available on agent invocation according to OTEL semconv
   * version 1.37.
   *
   * @param span Span on which attributes are set.
   * @param agentName Agent name from which attributes are gathered.
   * @param agentDescription Agent description from which attributes are gathered.
   * @param invocationContext InvocationContext from which attributes are gathered.
   */
  public static void traceAgentInvocation(
      Span span, String agentName, String agentDescription, InvocationContext invocationContext) {
    span.setAttribute(GEN_AI_OPERATION_NAME, "invoke_agent");
    span.setAttribute(GEN_AI_AGENT_DESCRIPTION, agentDescription);
    span.setAttribute(GEN_AI_AGENT_NAME, agentName);
    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute(GEN_AI_CONVERSATION_ID, invocationContext.session().id());
    }
  }

  /**
   * Traces tool call arguments.
   *
   * @param args The arguments to the tool call.
   */
  public static void traceToolCall(
      String toolName, String toolDescription, String toolType, Map<String, Object> args) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceToolCall: No valid span in current context.");
      return;
    }

    span.setAttribute(GEN_AI_OPERATION_NAME, "execute_tool");
    span.setAttribute(GEN_AI_TOOL_NAME, toolName);
    span.setAttribute(GEN_AI_TOOL_DESCRIPTION, toolDescription);
    span.setAttribute(GEN_AI_TOOL_TYPE, toolType);
    if (CAPTURE_MESSAGE_CONTENT_IN_SPANS) {
      try {
        span.setAttribute(ADK_TOOL_CALL_ARGS, JsonBaseModel.getMapper().writeValueAsString(args));
      } catch (JsonProcessingException e) {
        log.warn("traceToolCall: Failed to serialize tool call args to JSON", e);
      }
    } else {
      span.setAttribute(ADK_TOOL_CALL_ARGS, "{}");
    }
    span.setAttribute(ADK_LLM_REQUEST, "{}");
    span.setAttribute(ADK_LLM_RESPONSE, "{}");
  }

  /**
   * Traces tool response event.
   *
   * @param eventId The ID of the event.
   * @param functionResponseEvent The function response event.
   */
  public static void traceToolResponse(String eventId, Event functionResponseEvent) {
    Span span = Span.current();
    if (span == null || !span.getSpanContext().isValid()) {
      log.trace("traceToolResponse: No valid span in current context.");
      return;
    }

    span.setAttribute(GEN_AI_OPERATION_NAME, "execute_tool");
    span.setAttribute(ADK_EVENT_ID, eventId);

    String toolCallId = "<not specified>";
    Object toolResponse = "<not specified>";

    Optional<FunctionResponse> optionalFunctionResponse =
        functionResponseEvent.functionResponses().stream().findFirst();

    if (optionalFunctionResponse.isPresent()) {
      FunctionResponse functionResponse = optionalFunctionResponse.get();
      toolCallId = functionResponse.id().orElse(toolCallId);
      if (functionResponse.response().isPresent()) {
        toolResponse = functionResponse.response().get();
      }
    }
    span.setAttribute(GEN_AI_TOOL_CALL_ID, toolCallId);

    if (!(toolResponse instanceof Map)) {
      toolResponse = ImmutableMap.of("result", toolResponse);
    }

    if (CAPTURE_MESSAGE_CONTENT_IN_SPANS) {
      try {
        span.setAttribute(
            ADK_TOOL_RESPONSE, JsonBaseModel.getMapper().writeValueAsString(toolResponse));
      } catch (JsonProcessingException e) {
        log.warn("traceToolResponse: Failed to serialize tool response to JSON", e);
        span.setAttribute(ADK_TOOL_RESPONSE, "{\"error\": \"serialization failed\"}");
      }
    } else {
      span.setAttribute(ADK_TOOL_RESPONSE, "{}");
    }

    // Setting empty llm request and response (as the AdkDevServer UI expects these)
    span.setAttribute(ADK_LLM_REQUEST, "{}");
    span.setAttribute(ADK_LLM_RESPONSE, "{}");
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

    span.setAttribute(GEN_AI_SYSTEM, "gcp.vertex.agent");
    llmRequest.model().ifPresent(modelName -> span.setAttribute(GEN_AI_REQUEST_MODEL, modelName));
    span.setAttribute(ADK_INVOCATION_ID, invocationContext.invocationId());
    span.setAttribute(ADK_EVENT_ID, eventId);

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute(ADK_SESSION_ID, invocationContext.session().id());
    } else {
      log.trace(
          "traceCallLlm: InvocationContext session or session ID is null, cannot set"
              + " gcp.vertex.agent.session_id");
    }

    if (CAPTURE_MESSAGE_CONTENT_IN_SPANS) {
      try {
        span.setAttribute(
            ADK_LLM_REQUEST,
            JsonBaseModel.getMapper().writeValueAsString(buildLlmRequestForTrace(llmRequest)));
        span.setAttribute(ADK_LLM_RESPONSE, llmResponse.toJson());
      } catch (JsonProcessingException e) {
        log.warn("traceCallLlm: Failed to serialize LlmRequest or LlmResponse to JSON", e);
      }
    } else {
      span.setAttribute(ADK_LLM_REQUEST, "{}");
      span.setAttribute(ADK_LLM_RESPONSE, "{}");
    }
    llmRequest
        .config()
        .ifPresent(
            config -> {
              config
                  .topP()
                  .ifPresent(topP -> span.setAttribute(GEN_AI_REQUEST_TOP_P, topP.doubleValue()));
              config
                  .maxOutputTokens()
                  .ifPresent(
                      maxTokens ->
                          span.setAttribute(GEN_AI_REQUEST_MAX_TOKENS, maxTokens.longValue()));
            });
    llmResponse
        .usageMetadata()
        .ifPresent(
            usage -> {
              usage
                  .promptTokenCount()
                  .ifPresent(tokens -> span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) tokens));
              usage
                  .candidatesTokenCount()
                  .ifPresent(
                      tokens -> span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) tokens));
            });
    llmResponse
        .finishReason()
        .map(reason -> reason.knownEnum().name().toLowerCase(Locale.ROOT))
        .ifPresent(
            reason -> span.setAttribute(GEN_AI_RESPONSE_FINISH_REASONS, ImmutableList.of(reason)));
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

    span.setAttribute(ADK_INVOCATION_ID, invocationContext.invocationId());
    if (eventId != null && !eventId.isEmpty()) {
      span.setAttribute(ADK_EVENT_ID, eventId);
    }

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute(ADK_SESSION_ID, invocationContext.session().id());
    }
    if (CAPTURE_MESSAGE_CONTENT_IN_SPANS) {
      try {
        ImmutableList<Map<String, Object>> dataList =
            Optional.ofNullable(data).orElse(ImmutableList.of()).stream()
                .filter(content -> content != null)
                .map(content -> JsonBaseModel.getMapper().convertValue(content, MAP_TYPE_REFERENCE))
                .collect(toImmutableList());
        span.setAttribute(ADK_DATA, JsonBaseModel.toJsonString(dataList));
      } catch (IllegalStateException e) {
        log.warn("traceSendData: Failed to serialize data to JSON", e);
      }
    } else {
      span.setAttribute(ADK_DATA, "{}");
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
