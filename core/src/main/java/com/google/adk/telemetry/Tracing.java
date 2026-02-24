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
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableSource;
import io.reactivex.rxjava3.core.CompletableTransformer;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableTransformer;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeSource;
import io.reactivex.rxjava3.core.MaybeTransformer;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleSource;
import io.reactivex.rxjava3.core.SingleTransformer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.reactivestreams.Publisher;
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

  @SuppressWarnings("NonFinalStaticField")
  private static Tracer tracer = GlobalOpenTelemetry.getTracer("gcp.vertex.agent");

  private static final boolean CAPTURE_MESSAGE_CONTENT_IN_SPANS =
      Boolean.parseBoolean(
          System.getenv().getOrDefault("ADK_CAPTURE_MESSAGE_CONTENT_IN_SPANS", "true"));

  private Tracing() {}

  private static Optional<Span> getValidCurrentSpan(String methodName) {
    Span span = Span.current();
    if (!span.getSpanContext().isValid()) {
      log.trace("{}: No valid span in current context.", methodName);
      return Optional.empty();
    }
    return Optional.of(span);
  }

  private static void setInvocationAttributes(
      Span span, InvocationContext invocationContext, String eventId) {
    span.setAttribute(ADK_INVOCATION_ID, invocationContext.invocationId());
    if (eventId != null && !eventId.isEmpty()) {
      span.setAttribute(ADK_EVENT_ID, eventId);
    }

    if (invocationContext.session() != null && invocationContext.session().id() != null) {
      span.setAttribute(ADK_SESSION_ID, invocationContext.session().id());
    } else {
      log.trace(
          "InvocationContext session or session ID is null, cannot set {}",
          ADK_SESSION_ID.getKey());
    }
  }

  private static void setToolExecutionAttributes(Span span) {
    span.setAttribute(GEN_AI_OPERATION_NAME, "execute_tool");
    span.setAttribute(ADK_LLM_REQUEST, "{}");
    span.setAttribute(ADK_LLM_RESPONSE, "{}");
  }

  private static void setJsonAttribute(Span span, AttributeKey<String> key, Object value) {
    if (!CAPTURE_MESSAGE_CONTENT_IN_SPANS) {
      span.setAttribute(key, "{}");
      return;
    }
    try {
      String json =
          (value instanceof String stringValue)
              ? stringValue
              : JsonBaseModel.getMapper().writeValueAsString(value);
      span.setAttribute(key, json);
    } catch (JsonProcessingException | RuntimeException e) {
      log.warn("Failed to serialize {} to JSON", key.getKey(), e);
      span.setAttribute(key, "{\"error\": \"serialization failed\"}");
    }
  }

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
    getValidCurrentSpan("traceToolCall")
        .ifPresent(
            span -> {
              setToolExecutionAttributes(span);
              span.setAttribute(GEN_AI_TOOL_NAME, toolName);
              span.setAttribute(GEN_AI_TOOL_DESCRIPTION, toolDescription);
              span.setAttribute(GEN_AI_TOOL_TYPE, toolType);

              setJsonAttribute(span, ADK_TOOL_CALL_ARGS, args);
            });
  }

  /**
   * Traces tool response event.
   *
   * @param eventId The ID of the event.
   * @param functionResponseEvent The function response event.
   */
  public static void traceToolResponse(String eventId, Event functionResponseEvent) {
    getValidCurrentSpan("traceToolResponse")
        .ifPresent(
            span -> {
              setToolExecutionAttributes(span);
              span.setAttribute(ADK_EVENT_ID, eventId);

              FunctionResponse functionResponse =
                  functionResponseEvent.functionResponses().stream().findFirst().orElse(null);

              String toolCallId = "<not specified>";
              Object toolResponse = "<not specified>";
              if (functionResponse != null) {
                toolCallId = functionResponse.id().orElse(toolCallId);
                if (functionResponse.response().isPresent()) {
                  toolResponse = functionResponse.response().get();
                }
              }

              span.setAttribute(GEN_AI_TOOL_CALL_ID, toolCallId);

              Object finalToolResponse =
                  (toolResponse instanceof Map)
                      ? toolResponse
                      : ImmutableMap.of("result", toolResponse);

              setJsonAttribute(span, ADK_TOOL_RESPONSE, finalToolResponse);
            });
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
    getValidCurrentSpan("traceCallLlm")
        .ifPresent(
            span -> {
              span.setAttribute(GEN_AI_SYSTEM, "gcp.vertex.agent");
              llmRequest
                  .model()
                  .ifPresent(modelName -> span.setAttribute(GEN_AI_REQUEST_MODEL, modelName));

              setInvocationAttributes(span, invocationContext, eventId);

              setJsonAttribute(span, ADK_LLM_REQUEST, buildLlmRequestForTrace(llmRequest));
              setJsonAttribute(span, ADK_LLM_RESPONSE, llmResponse);

              llmRequest
                  .config()
                  .ifPresent(
                      config -> {
                        config
                            .topP()
                            .ifPresent(
                                topP ->
                                    span.setAttribute(GEN_AI_REQUEST_TOP_P, topP.doubleValue()));
                        config
                            .maxOutputTokens()
                            .ifPresent(
                                maxTokens ->
                                    span.setAttribute(
                                        GEN_AI_REQUEST_MAX_TOKENS, maxTokens.longValue()));
                      });
              llmResponse
                  .usageMetadata()
                  .ifPresent(
                      usage -> {
                        usage
                            .promptTokenCount()
                            .ifPresent(
                                tokens ->
                                    span.setAttribute(GEN_AI_USAGE_INPUT_TOKENS, (long) tokens));
                        usage
                            .candidatesTokenCount()
                            .ifPresent(
                                tokens ->
                                    span.setAttribute(GEN_AI_USAGE_OUTPUT_TOKENS, (long) tokens));
                      });
              llmResponse
                  .finishReason()
                  .map(reason -> reason.knownEnum().name().toLowerCase(Locale.ROOT))
                  .ifPresent(
                      reason ->
                          span.setAttribute(
                              GEN_AI_RESPONSE_FINISH_REASONS, ImmutableList.of(reason)));
            });
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
    getValidCurrentSpan("traceSendData")
        .ifPresent(
            span -> {
              setInvocationAttributes(span, invocationContext, eventId);

              ImmutableList<Content> safeData =
                  Optional.ofNullable(data).orElse(ImmutableList.of()).stream()
                      .filter(Objects::nonNull)
                      .collect(toImmutableList());
              setJsonAttribute(span, ADK_DATA, safeData);
            });
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

  /**
   * Returns a transformer that traces the execution of an RxJava stream.
   *
   * @param spanName The name of the span to create.
   * @param <T> The type of the stream.
   * @return A TracerProvider that can be used with .compose().
   */
  public static <T> TracerProvider<T> trace(String spanName) {
    return new TracerProvider<>(spanName);
  }

  /**
   * Returns a transformer that traces the execution of an RxJava stream with an explicit parent
   * context.
   *
   * @param spanName The name of the span to create.
   * @param parentContext The explicit parent context for the span.
   * @param <T> The type of the stream.
   * @return A TracerProvider that can be used with .compose().
   */
  public static <T> TracerProvider<T> trace(String spanName, Context parentContext) {
    return new TracerProvider<T>(spanName).setParent(parentContext);
  }

  /**
   * Returns a transformer that traces an agent invocation.
   *
   * @param spanName The name of the span to create.
   * @param agentName The name of the agent.
   * @param agentDescription The description of the agent.
   * @param invocationContext The invocation context.
   * @param <T> The type of the stream.
   * @return A TracerProvider configured for agent invocation.
   */
  public static <T> TracerProvider<T> traceAgent(
      String spanName,
      String agentName,
      String agentDescription,
      InvocationContext invocationContext) {
    return new TracerProvider<T>(spanName)
        .configure(
            span -> traceAgentInvocation(span, agentName, agentDescription, invocationContext));
  }

  /**
   * A transformer that manages an OpenTelemetry span and scope for RxJava streams.
   *
   * @param <T> The type of the stream.
   */
  public static final class TracerProvider<T>
      implements FlowableTransformer<T, T>,
          SingleTransformer<T, T>,
          MaybeTransformer<T, T>,
          CompletableTransformer {
    private final String spanName;
    private Context explicitParentContext;
    private final List<Consumer<Span>> spanConfigurers = new ArrayList<>();

    private TracerProvider(String spanName) {
      this.spanName = spanName;
    }

    /** Configures the span created by this transformer. */
    @CanIgnoreReturnValue
    public TracerProvider<T> configure(Consumer<Span> configurer) {
      spanConfigurers.add(configurer);
      return this;
    }

    /** Sets an explicit parent context for the span created by this transformer. */
    @CanIgnoreReturnValue
    public TracerProvider<T> setParent(Context parentContext) {
      this.explicitParentContext = parentContext;
      return this;
    }

    private Context getParentContext() {
      return explicitParentContext != null ? explicitParentContext : Context.current();
    }

    private final class TracingLifecycle {
      private Span span;
      private Scope scope;

      @SuppressWarnings("MustBeClosedChecker")
      void start() {
        span = tracer.spanBuilder(spanName).setParent(getParentContext()).startSpan();
        spanConfigurers.forEach(c -> c.accept(span));
        scope = span.makeCurrent();
      }

      void end() {
        if (scope != null) {
          scope.close();
        }
        if (span != null) {
          span.end();
        }
      }
    }

    @Override
    public Publisher<T> apply(Flowable<T> upstream) {
      return Flowable.defer(
          () -> {
            TracingLifecycle lifecycle = new TracingLifecycle();
            return upstream.doOnSubscribe(s -> lifecycle.start()).doFinally(lifecycle::end);
          });
    }

    @Override
    public SingleSource<T> apply(Single<T> upstream) {
      return Single.defer(
          () -> {
            TracingLifecycle lifecycle = new TracingLifecycle();
            return upstream.doOnSubscribe(s -> lifecycle.start()).doFinally(lifecycle::end);
          });
    }

    @Override
    public MaybeSource<T> apply(Maybe<T> upstream) {
      return Maybe.defer(
          () -> {
            TracingLifecycle lifecycle = new TracingLifecycle();
            return upstream.doOnSubscribe(s -> lifecycle.start()).doFinally(lifecycle::end);
          });
    }

    @Override
    public CompletableSource apply(Completable upstream) {
      return Completable.defer(
          () -> {
            TracingLifecycle lifecycle = new TracingLifecycle();
            return upstream.doOnSubscribe(s -> lifecycle.start()).doFinally(lifecycle::end);
          });
    }
  }
}
