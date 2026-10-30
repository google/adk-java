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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;

/**
 * Service Provider Interface (SPI) for pluggable LLM backends.
 *
 * <p>Implementations let third-party model providers (Groq, Ollama, OpenRouter, etc.) register
 * themselves with {@link LlmRegistry} automatically, so agents can reference models by prefixed
 * name strings such as {@code "groq/<model-id>"} without per-application registration code — adding
 * the provider dependency and its configuration (e.g. an API-key environment variable) is all an
 * application needs.
 *
 * <h2>How it works</h2>
 *
 * <ol>
 *   <li>A provider library implements this interface.
 *   <li>It declares the implementation class in {@code
 *       META-INF/services/com.google.adk.models.ModelProvider}.
 *   <li>The application calls {@link ModelProviderRegistry#registerAll()} once at startup, which
 *       uses {@link java.util.ServiceLoader} to discover and register every provider on the
 *       classpath.
 * </ol>
 *
 * <h2>Why the provider receives a bare model name</h2>
 *
 * <p>{@link LlmRegistry} resolves a model string by invoking the registered factory with the
 * requested name, and the resolved {@link BaseLlm}'s own model name is what is ultimately sent to
 * the backend as the wire-format model identifier. The {@code "groq/"} prefix is purely a routing
 * namespace, so the default {@link #create(String)} strips it before delegating to {@link
 * #createFromBareModelName(String)} — ensuring the backend receives a bare model identifier it
 * actually recognizes.
 */
public interface ModelProvider {

  /**
   * Returns the provider prefix without the trailing slash, e.g. {@code "groq"}.
   *
   * <p>The prefix is used to derive the {@link #modelPattern()} and is stripped from the model name
   * before delegating to {@link #createFromBareModelName(String)}.
   *
   * @return the provider prefix, must not be blank
   */
  String prefix();

  /**
   * Returns the regex pattern of model names this provider handles.
   *
   * <p>The default implementation derives the pattern from {@link #prefix()}, e.g. {@code
   * "groq/.*"}.
   */
  default String modelPattern() {
    String prefix = prefix();
    checkState(isNotNullOrBlank(prefix), "Provider prefix cannot be blank");
    return prefix + "/.*";
  }

  /**
   * Creates a {@link BaseLlm} instance for the given model name.
   *
   * <p>The default implementation strips the {@link #prefix()} and separator slash from the model
   * name and delegates to {@link #createFromBareModelName(String)}.
   *
   * @param modelName the full model name, e.g. {@code "groq/some-model"}
   */
  default BaseLlm create(String modelName) {
    checkArgument(isNotNullOrBlank(modelName), "modelName cannot be blank");
    String prefixWithSlash = prefix() + "/";
    String bareModelName =
        modelName.startsWith(prefixWithSlash)
            ? modelName.substring(prefixWithSlash.length())
            : modelName;
    return createFromBareModelName(bareModelName);
  }

  /**
   * Creates a {@link BaseLlm} for the given bare model identifier.
   *
   * <p>The {@code bareModelName} has already had the provider prefix removed and can be passed
   * directly to the underlying API.
   *
   * @param bareModelName the model name without prefix, e.g. {@code "some-model"}
   */
  BaseLlm createFromBareModelName(String bareModelName);

  private static boolean isNotNullOrBlank(String s) {
    return !Strings.nullToEmpty(s).isBlank();
  }
}
