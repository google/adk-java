package com.google.adk.models.ollama;

import static java.util.Optional.empty;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.OpenAiCompatibleLlm;

/**
 * ModelProvider for Ollama - fully local interface, no API key required.
 *
 * <p>Activated when {@code agent.model} starts with {@code ollama/}. Connects to a locally running
 * Ollama server (default: {@code http://localhost:11434}). Override the base URL via the {@code
 * OLLAMA_BASE_URL} environment variables.
 *
 * <p>Example: {@code agent.model=ollama/llama3}
 *
 * <p>Setup: install from <a href="https://ollama.com">ollama.com</a>, then run {@code ollama pull
 * llama3}.
 */
public class OllamaModelProvider implements ModelProvider {

  public OllamaModelProvider() {}

  private static final String DEFAULT_BASE_URL = "http://localhost:11434";
  private static final String PREFIX = "ollama/";

  @Override
  public String modelPattern() {
    return "ollama/.*";
  }

  @Override
  public BaseLlm create(String modelName) {
    String model = modelName.startsWith(PREFIX) ? modelName.substring(PREFIX.length()) : modelName;
    String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL);
    String apiUrl = baseUrl.replaceAll("/$", "") + "/v1/chat/completions";
    return new OpenAiCompatibleLlm(model, apiUrl, empty());
  }
}
