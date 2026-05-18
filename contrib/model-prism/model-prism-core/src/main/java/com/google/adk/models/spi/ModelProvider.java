package com.google.adk.models.spi;

import com.google.adk.models.BaseLlm;

/**
 * SPI (Service Provider Interface) for pluggable LLM backends in Google ADK Java.
 *
 * <h2>Purpose</h2>
 *
 * <p>This interface is the proposed extension point that would allow third-party providers (Groq,
 * Ollama, OpenRouter, etc.) to register themselves with {@link com.google.adk.models.LlmRegistry}
 * automatically - without any changes to {@code App.java} or ADK core.
 *
 * <h2>How it works</h2>
 *
 * <ol>
 *   <li>A provider library implements this interface.
 *   <li>It declares the implementation in {@code
 *       META-INF/services/com.google.adk.models.spi.ModelProvider}.
 *   <li>At startup, {@link ModelProviderRegistry#registerAll()} uses {@link
 *       java.util.ServiceLoader} to discover and register all providers on the classpath.
 * </ol>
 *
 * <h2>Proposed location in ADK core</h2>
 *
 * <p>This interface would live in {@code google-adk.jar} under {@code com.google.adk.models.spi},
 * making it part of ADK's public API surface
 */
public interface ModelProvider {

  /**
   * Returns the regex pattern this provider handles.
   *
   * <p>The pattern is matched against the full {@code agent.model} value. Convention: prefix with
   * provider name followed by {@code /} to avoid collisions.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "groq/.*"} - matches {@code groq/llama-3.1-8b-instant}
   *   <li>{@code "ollama/.*"} - matches {@code ollama/llama3}
   *   <li>{@code "openrouter/.*} - matches {@code openrouter/auto}
   * </ul>
   */
  String modelPattern();

  /**
   * Creates a {@link BaseLlm} instance for the given model name.
   *
   * <p>Called once per unique model name when first needed - not at startup. The full model name
   * (including prefix) is passed; implementation should strip the prefix before passing to the
   * underlying API.
   *
   * @param modelName the full model name, e.g. {@code "groq/llama-3.1-8b-instant"}
   */
  BaseLlm create(String modelName);
}
