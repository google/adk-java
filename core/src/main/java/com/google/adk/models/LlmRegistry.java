/*
 * Copyright 2025 Google LLC
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

import com.google.genai.Client;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A registry for managing and providing access to Large Language Model (LLM) instances.
 * This class provides a centralized way to register different LLM factories based on
 * model name patterns and to retrieve LLM instances, caching them for efficient reuse.
 * It ensures thread-safe access to LLM instances and their factories.
 */
public final class LlmRegistry {

  /** A thread-safe cache mapping model names to LLM instances. */
  private static final Map<String, BaseLlm> instances = new ConcurrentHashMap<>();

  /** The factory interface for creating LLM instances. */
  @FunctionalInterface
  public interface LlmFactory {
    /**
     * Creates a new instance of {@link BaseLlm} for the given model name.
     *
     * @param modelName The specific name of the LLM to create.
     * @return A new {@link BaseLlm} instance.
     */
    BaseLlm create(String modelName);
  }

  /** The API client for interacting with the Gemini model. */
  private static final Client geminiApiClient = Client.builder().build();

  /** Map of model name patterns regex to factories. */
  private static final Map<String, LlmFactory> llmFactories = new ConcurrentHashMap<>();

  /**
   * Returns the singleton instance of the Gemini API client.
   *
   * @return The {@link Client} instance used for Gemini API calls.
   */
  private static Client getGeminiApiClient() {
    return geminiApiClient;
  }

  /**
   * Static initializer block to register default LLM factories.
   * Registers a factory for Gemini models using the "gemini-.*" pattern.
   */
  static {
    registerLlm("gemini-.*", modelName -> new Gemini(modelName, getGeminiApiClient()));
  }

  /**
   * Registers an {@link LlmFactory} for a given model name pattern.
   * When {@link #getLlm(String)} is called with a model name that matches the
   * {@code modelNamePattern}, the provided factory will be used to create the LLM instance.
   *
   * @param modelNamePattern A regex pattern that matches model names this factory can create.
   * @param factory The {@link LlmFactory} instance responsible for creating LLM instances
   * for matching model names.
   */
  public static void registerLlm(String modelNamePattern, LlmFactory factory) {
    llmFactories.put(modelNamePattern, factory);
  }

  /**
   * Retrieves an LLM instance for the specified model name.
   * If an instance for the given model name is already cached, it is returned.
   * Otherwise, an appropriate {@link LlmFactory} is used to create a new instance,
   * which is then cached and returned.
   *
   * @param modelName The name of the LLM to retrieve.
   * @return A {@link BaseLlm} instance corresponding to the given model name.
   * @throws IllegalArgumentException if no registered factory supports the given model name.
   */
  public static BaseLlm getLlm(String modelName) {
    return instances.computeIfAbsent(modelName, LlmRegistry::createLlm);
  }

  /**
   * Creates a new {@link BaseLlm} instance by matching the model name against
   * registered factories.
   * This private helper method is used internally by {@link #getLlm(String)}.
   *
   * @param modelName The name of the LLM to create.
   * @return A newly created {@link BaseLlm} instance.
   * @throws IllegalArgumentException if no registered factory supports the given model name.
   */
  private static BaseLlm createLlm(String modelName) {
    for (Map.Entry<String, LlmFactory> entry : llmFactories.entrySet()) {
      if (modelName.matches(entry.getKey())) {
        return entry.getValue().create(modelName);
      }
    }
    throw new IllegalArgumentException("Unsupported model: " + modelName);
  }

  /**
   * Registers an LLM factory specifically for testing purposes.
   * This method also clears any cached instances that match the provided pattern
   * to ensure test isolation and prevent stale data.
   *
   * @param modelNamePattern A regex pattern for the test model name.
   * @param factory The {@link LlmFactory} to register for testing.
   */
  static void registerTestLlm(String modelNamePattern, LlmFactory factory) {
    llmFactories.put(modelNamePattern, factory);
    // Clear any cached instances that match this pattern to ensure test isolation.
    instances.keySet().removeIf(modelName -> modelName.matches(modelNamePattern));
  }

  /**
   * Private constructor to prevent instantiation of this utility class.
   * This class provides only static methods and should not be instantiated.
   */
  private LlmRegistry() {}
}
