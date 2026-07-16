package com.google.adk.models.azure;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Flowable;

/**
 * Azure transport for GPT Realtime Translate ({@code gpt-realtime-translate}).
 *
 * <p>Uses the {@code /openai/v1/realtime/translations} WebSocket endpoint and continuous
 * translation events — not the bidirectional voice-agent protocol.
 */
public final class AzureRealtimeTranslateTransport implements AzureTransport {

  @Override
  public boolean supports(String modelName) {
    return AzureModelUtils.isTranslateModel(modelName);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request, AzureConfig config) {
    return new AzureRealtimeTranslateLlmConnection(config, request);
  }

  @Override
  public Flowable<LlmResponse> generateContent(
      LlmRequest request, AzureConfig config, boolean stream) {
    return Flowable.error(
        new UnsupportedOperationException(
            "gpt-realtime-translate requires a live WebSocket connection; use connect() instead."));
  }
}
