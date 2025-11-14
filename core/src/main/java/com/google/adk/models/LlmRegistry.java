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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Central registry for managing Large Language Model (LLM) instances. */
public final class LlmRegistry {

  /** A thread-safe cache mapping model names to LLM instances. */
  private static final Map<String, BaseLlm> instances = new ConcurrentHashMap<>();

  /** The factory interface for creating LLM instances. */
  @FunctionalInterface
  public interface LlmFactory {
    BaseLlm create(String modelName);
  }

  /** Map of model name patterns regex to factories. */
  private static final Map<String, LlmFactory> llmFactories = new ConcurrentHashMap<>();

  /** Registers default LLM factories, e.g. for Gemini models. */
  static {
    registerLlm("gemini-.*", modelName -> Gemini.builder().modelName(modelName).build());
    registerLlm("apigee/.*", modelName -> ApigeeLlm.builder().modelName(modelName).build());
  }

  /**
   * Registers a factory for model names matching the given regex pattern.
   *
   * @param modelNamePattern Regex pattern for matching model names.
   * @param factory Factory to create LLM instances.
   */
  public static void registerLlm(String modelNamePattern, LlmFactory factory) {
    llmFactories.put(modelNamePattern, factory);
  }

  /**
   * Returns an LLM instance for the given model name, using a cached or new factory-created
   * instance.
   *
   * @param modelName Model name to look up.
   * @return Matching {@link BaseLlm} instance.
   * @throws IllegalArgumentException If no factory matches the model name.
   */
  public static BaseLlm getLlm(String modelName) {
    return instances.computeIfAbsent(modelName, LlmRegistry::createLlm);
  }

  /**
   * Creates a {@link BaseLlm} by matching the model name against registered factories.
   *
   * @param modelName Model name to match.
   * @return A new {@link BaseLlm} instance.
   * @throws IllegalArgumentException If no factory matches the model name.
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
   * Registers an LLM factory for testing purposes. Clears cached instances matching the given
   * pattern to ensure test isolation.
   *
   * @param modelNamePattern Regex pattern for matching model names.
   * @param factory The {@link LlmFactory} to register.
   */
  static void registerTestLlm(String modelNamePattern, LlmFactory factory) {
    llmFactories.put(modelNamePattern, factory);
    // Clear any cached instances that match this pattern to ensure test isolation.
    instances.keySet().removeIf(modelName -> modelName.matches(modelNamePattern));
  }

  private LlmRegistry() {}
}
