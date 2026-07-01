package com.google.adk.base.serializer;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.FlowableEmitter;
import java.util.stream.Stream;

/**
 * Contract for converting between ADK domain objects and the OpenAI chat-completions JSON wire
 * format.
 *
 * <p>Separating this from HTTP transport means the two can evolve and be tested independently.
 */
public interface OpenAiMessageSerializer {

  /**
   * Serializes an {@link LlmRequest} to an OpenAI-format JSON request body
   *
   * @param request the ADK request
   * @param modelName bare model name to embed in the payload
   * @param stream whether to request SSE streaming from the API
   */
  String serializeRequest(LlmRequest request, String modelName, boolean stream) throws Exception;

  /**
   * Deserializes a full (non-streaming) OpenAI chat-completions response body into a single {@link
   * LlmResponse}
   */
  LlmResponse deserializeResponse(String responseBody) throws Exception;

  /**
   * Processes an SSE line stream, emitting partial and final {@link LlmResponse} events onto the
   * supplied {@link FlowableEmitter}.
   *
   * <p>Does <em>not</em> call {@link FlowableEmitter#onComplete()} - the caller is responsible for
   * that so it can manage the stream lifecycle.
   */
  void processStreamLines(Stream<String> lines, FlowableEmitter<LlmResponse> emitter)
      throws Exception;
}
