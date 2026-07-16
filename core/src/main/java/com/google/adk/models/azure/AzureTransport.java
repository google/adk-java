package com.google.adk.models.azure;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Strategy interface for Azure LLM transport protocols.
 *
 * <p>Each implementation handles a specific Azure API surface (REST Responses API, WebSocket
 * Realtime API, etc.) while sharing common configuration and request conversion via {@link
 * AzureConfig} and {@link AzureRequestConverter}.
 */
public interface AzureTransport {

  /** Returns true if this transport can handle the given model name. */
  boolean supports(String modelName);

  /**
   * Generates content using this transport's protocol.
   *
   * @param request the ADK LLM request
   * @param config shared Azure configuration
   * @param stream whether to stream the response
   * @return a Flowable of LLM responses
   */
  Flowable<LlmResponse> generateContent(LlmRequest request, AzureConfig config, boolean stream);

  /**
   * Opens a persistent bidirectional connection using this transport's protocol.
   *
   * @param request the ADK LLM request (tools, instructions, etc.)
   * @param config shared Azure configuration
   * @return a live connection
   */
  BaseLlmConnection connect(LlmRequest request, AzureConfig config);
}
