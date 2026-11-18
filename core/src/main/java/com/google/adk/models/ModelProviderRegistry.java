/*
 * Copyright 2026 Google LLC
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

package com.google.adk.models;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and registers all {@link ModelProvider} implementations on the classpath using Java's
 * {@link ServiceLoader} mechanism.
 *
 * <p>Without this class, model name strings such as {@code "groq/<model-id>"} cannot be passed to
 * {@code LlmAgent.builder().model(String)} unless application code has first called {@link
 * LlmRegistry#registerLlm} for the matching pattern. Calling {@link #registerAll()} once at startup
 * replaces all such manual registration: any provider JAR on the classpath that declares an
 * implementation in {@code META-INF/services/com.google.adk.models.ModelProvider} is picked up
 * automatically.
 *
 * <pre>{@code
 * ModelProviderRegistry.registerAll();
 *
 * LlmAgent agent =
 *     LlmAgent.builder()
 *         .name("assistant")
 *         .model("groq/some-model")
 *         .build();
 * }</pre>
 */
public final class ModelProviderRegistry {

  private static final Logger logger = LoggerFactory.getLogger(ModelProviderRegistry.class);

  private ModelProviderRegistry() {}

  /**
   * Loads all {@link ModelProvider} implementations via {@link ServiceLoader} and registers each
   * one with {@link LlmRegistry}.
   *
   * @return an immutable list of registered providers (useful for logging and diagnostics)
   */
  public static ImmutableList<ModelProvider> registerAll() {
    return registerAll(ModelProviderRegistry.class.getClassLoader());
  }

  /**
   * Same as {@link #registerAll()} but discovers providers through the given {@link ClassLoader}.
   *
   * <p>Each provider is discovered and instantiated in isolation: a provider that fails to
   * instantiate (e.g. a throwing constructor) is logged at {@code WARN} and skipped without
   * preventing the remaining providers on the classpath from registering.
   */
  public static ImmutableList<ModelProvider> registerAll(ClassLoader classLoader) {
    ImmutableList<ModelProvider> providers =
        ServiceLoader.load(ModelProvider.class, classLoader).stream()
            .flatMap(descriptor -> tryInstantiate(descriptor).stream())
            .collect(toImmutableList());
    providers.forEach(ModelProviderRegistry::registerProvider);
    return providers;
  }

  private static void registerProvider(ModelProvider provider) {
    String pattern = provider.modelPattern();
    String className = provider.getClass().getName();
    LlmRegistry.registerLlm(pattern, provider::create);
    logger.info("Registered model provider '{}' for pattern '{}'", className, pattern);
  }

  /**
   * Attempts to instantiate a {@link ModelProvider} from its {@link ServiceLoader.Provider}
   * descriptor.
   *
   * @return the provider instance, or empty if instantiation failed
   */
  private static Optional<ModelProvider> tryInstantiate(
      ServiceLoader.Provider<ModelProvider> descriptor) {
    try {
      return Optional.of(descriptor.get());
    } catch (ServiceConfigurationError e) {
      String type = descriptor.type().getName();
      logger.warn("Skipping ModelProvider {} - failed to instantiate: {}", type, e.getMessage(), e);
      return Optional.empty();
    }
  }
}
