package com.google.adk.models.groq;

import static java.util.Optional.ofNullable;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.OpenAiCompatibleLlm;
import java.util.Optional;

/**
 * ModelProvider for the Groq API.
 *
 * <p>Activated when {@code agent.model} starts with {@code groq/}. Reads the API key from the
 * {@code GROQ_API_KEY} environment variable.
 *
 * <p>Example: {@code agent.model=groq/llama-3.1-8b-instant}
 *
 * <p>Free tier available at <a href="https://console.groq.com">console.groq.com</a>.
 *
 * <p>Models with tool calling support: {@code llama-3.1-8b-instant}, {@code mixtral-8x7b-32768}
 */
public class GroqModelProvider implements ModelProvider {

  public GroqModelProvider() {}

  private static final String API_URL = "https://api.groq.com/openai/v1/chat/completions";
  private static final String PREFIX = "groq/";

  @Override
  public String modelPattern() {
    return "groq/.*";
  }

  @Override
  public BaseLlm create(String modelName) {
    Optional<String> apiKey = ofNullable(System.getenv("GROQ_API_KEY"));
    String model = modelName.startsWith(PREFIX) ? modelName.substring(PREFIX.length()) : modelName;
    return new OpenAiCompatibleLlm(model, API_URL, apiKey);
  }
}
