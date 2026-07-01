package com.google.adk.models.spi;

import com.google.adk.models.LlmRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Discovers and registers all {@link ModelProvider} implementations on the classpath using Java's
 * {@link ServiceLoader} mechanism.
 *
 * <h2>Context</h2>
 *
 * <p>ADK Java ships build-in support for Gemini. For other providers ADK already offers the
 * official {@code google-adk-langchain4j} contrib bridge, which wraps any LangChain4j {@code
 * ChatModel} as a {@code BaseLlm} and is passed directly to {@code
 * LlmAgent.builder().model(langChain4jWrapper)}. That approach requires explicit per-agent
 * construction code and does not use {@code LlmRegistry} at all.
 *
 * <p>This class addresses a different gap: there is no <em>convention-based, zero-code
 * discovery</em> mechanism. Without it, model name strings such as {@code "groq/llama3} cannot be
 * used in {@code .model("groq/...")} unless something has first called {@code
 * LlmRegistry.registerLlm("groq/.*", ...)} in application code.
 *
 * <h2>Proposed usage in ADK core</h2>
 *
 * <p>ADK's startup sequence would call {@link #registerAll()} once before the agent server starts.
 * This single call replaces all manual registration calls that applications currently must make.
 *
 * <pre>
 *  ModelProvderRegistry.registerAll();
 *  </pre>
 *
 * <h2>How providers are discovered</h2>
 *
 * <p>Any jar on the classpath that contains a {@code
 * META-INF/services/com.google.adk.models.spi.ModelProvider} file listing its implementation class
 * will be automatically picked up. No configuration needed.
 *
 * <h2>Proposed location in ADK core</h2>
 *
 * <p>This class would live in {@code google-adk.jar}, called internally by {@code Runner}. {@code
 * AdkWebServer.start()} in {@code google-adk-dev} would call {@code registerAll()} once, so
 * application code never needs to call it directly.
 */
public final class ModelProviderRegistry {

  private ModelProviderRegistry() {}

  /**
   * Loads all {@link ModelProvider} implementations via {@link ServiceLoader} and registers each
   * one with {@link LlmRegistry}.
   *
   * @return the list of registered providers (useful for logging/diagnostics)
   */
  public static List<ModelProvider> registerAll() {
    return registerAll(ModelProviderRegistry.class.getClassLoader());
  }

  /** Same as {@link #registerAll()} but uses a specific {@link ClassLoader}. */
  public static List<ModelProvider> registerAll(ClassLoader classLoader) {
    List<ModelProvider> registered = new ArrayList<>();
    ServiceLoader<ModelProvider> loader = ServiceLoader.load(ModelProvider.class, classLoader);
    for (ModelProvider provider : loader) {
      LlmRegistry.registerLlm(provider.modelPattern(), provider::create);
      registered.add(provider);
    }
    return registered;
  }
}
