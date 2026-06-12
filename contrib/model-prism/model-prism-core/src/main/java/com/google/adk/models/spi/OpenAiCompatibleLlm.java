package com.google.adk.models.spi;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.base.http.DefaultOpenAiHttpClient;
import com.google.adk.base.http.OpenAiHttpClient;
import com.google.adk.base.serializer.DefaultOpenAiMessageSerializer;
import com.google.adk.base.serializer.OpenAiMessageSerializer;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Base class for any OpenAI-compatible chat completion API (Groq, Ollama, OpenRouter, Together AI,
 * Fireworks, ...).
 *
 * <p>Orchestrates two collaborators:
 *
 * <ul>
 *   <li>{@link OpenAiHttpClient} - handles HTTP transport
 *   <li>{@link OpenAiMessageSerializer} - handles JSON serialization / deserialization
 * </ul>
 *
 * <p>A new provider needs only a one-liner subclass:
 *
 * <pre>
 * public class GroqLlm extends OpenAiCompatibleLlm {
 *   public GroqLlm(String modelName, String apiKey) {
 *     super(modelName, "https://api.groq.com/openai/v1/chat/completions", apiKey);
 *   }
 * }
 * </pre>
 *
 * <p>The {@code protected} injection constructor accepts custom collaborators, which is useful for
 * subclasses and unit tests.
 */
public class OpenAiCompatibleLlm extends BaseLlm {

  public OpenAiCompatibleLlm(String modelName, String apiUrl, Optional<String> apiKey) {
    this(
        modelName,
        new DefaultOpenAiHttpClient(apiUrl, apiKey),
        new DefaultOpenAiMessageSerializer());
  }

  protected OpenAiCompatibleLlm(
      String modelName, OpenAiHttpClient httpClient, OpenAiMessageSerializer serializer) {
    super(checkNotNull(modelName, "modelName"));
    this.httpClient = checkNotNull(httpClient, "httpClient");
    this.serializer = checkNotNull(serializer, "serializer");
  }

  private final OpenAiHttpClient httpClient;
  private final OpenAiMessageSerializer serializer;

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
    if (stream) {
      return streamContent(request);
    }
    return Flowable.fromCallable(
        () -> {
          String requestBody = serializer.serializeRequest(request, model(), false);
          return serializer.deserializeResponse(httpClient.post(requestBody));
        });
  }

  private Flowable<LlmResponse> streamContent(LlmRequest request) {
    return Flowable.create(
        emitter -> {
          try {
            String requestBody = serializer.serializeRequest(request, model(), true);
            try (Stream<String> lines = httpClient.postStream(requestBody)) {
              serializer.processStreamLines(lines, emitter);
            }
            emitter.onComplete();
          } catch (Exception e) {
            emitter.onError(e);
          }
        },
        BackpressureStrategy.BUFFER);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException(
        getClass().getSimpleName() + " does not support live streaming connections");
  }
}
