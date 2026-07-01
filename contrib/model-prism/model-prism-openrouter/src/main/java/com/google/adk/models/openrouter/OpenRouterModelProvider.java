package com.google.adk.models.openrouter;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.OpenAiCompatibleLlm;
import java.util.Optional;

/**
 * ModelProvider for the OpenRouter - aggregates many models through one OpenAI-compatible API.
 *
 * <p>Activated when {@code agent.model} starts with {@code openrouter/}. Reads the API key from the
 * {@code OPENROUTER_API_KEY} environment variable.
 *
 * <p>Example: {@code agent.model=openrouter/auto}
 *
 * <p>Free tier models (suffix {@code :free}) require no credits. See <a
 * href="https://openrouter.ai/models">openrouter.ai/models</a> for the full list.
 */
public class OpenRouterModelProvider implements ModelProvider {

  public OpenRouterModelProvider() {}

  private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
  private static final String PREFIX = "openrouter/";

  @Override
  public String modelPattern() {
    return "openrouter/.*";
  }

  @Override
  public BaseLlm create(String modelName) {
    Optional<String> apiKey = Optional.ofNullable(System.getenv("OPENROUTER_API_KEY"));
    String model = modelName.startsWith(PREFIX) ? modelName.substring(PREFIX.length()) : modelName;
    return new OpenAiCompatibleLlm(model, API_URL, apiKey);
  }
}
