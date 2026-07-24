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
package com.google.adk.config;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central configuration provider for the ADK SDK.
 *
 * <p>Resolves configuration values using the following fallback chain (highest priority first):
 *
 * <ol>
 *   <li>Programmatic values set via {@link #set(String, String)}.
 *   <li>JVM system properties ({@link System#getProperty(String)}).
 *   <li>Operating system environment variables ({@link System#getenv(String)}) — preserved for
 *       backward compatibility.
 * </ol>
 *
 * <p>This abstraction allows applications (e.g. Spring Boot) to inject configuration that
 * originates from {@code application.yaml}, secret managers, or any other source, without relying
 * on the immutable OS environment.
 */
public final class AdkConfiguration {

  private static final ConcurrentMap<String, String> OVERRIDES = new ConcurrentHashMap<>();

  private AdkConfiguration() {}

  /**
   * Programmatically sets a configuration value. Takes precedence over system properties and
   * environment variables.
   *
   * @param key the configuration key (typically the same name as the legacy environment variable)
   * @param value the value to associate with the key; must not be {@code null}. Use {@link
   *     #clear(String)} to remove an entry.
   */
  public static void set(String key, String value) {
    if (key == null) {
      throw new IllegalArgumentException("key must not be null");
    }
    if (value == null) {
      throw new IllegalArgumentException("value must not be null; use clear(key) to remove");
    }
    OVERRIDES.put(key, value);
  }

  /** Removes a programmatic override for the given key, if any. */
  public static void clear(String key) {
    if (key != null) {
      OVERRIDES.remove(key);
    }
  }

  /** Removes all programmatic overrides. Primarily intended for tests. */
  public static void clearAll() {
    OVERRIDES.clear();
  }

  /**
   * Resolves a configuration value using the fallback chain described in the class javadoc.
   *
   * @param key the configuration key
   * @return an {@link Optional} containing the resolved value, or empty if no source provides one
   */
  public static Optional<String> get(String key) {
    if (key == null) {
      return Optional.empty();
    }
    String override = OVERRIDES.get(key);
    if (override != null) {
      return Optional.of(override);
    }
    String systemProperty = System.getProperty(key);
    if (systemProperty != null) {
      return Optional.of(systemProperty);
    }
    return Optional.ofNullable(System.getenv(key));
  }

  /**
   * Resolves a configuration value, returning the provided default if no source provides one.
   *
   * @param key the configuration key
   * @param defaultValue value to return when the key cannot be resolved
   */
  public static String getOrDefault(String key, String defaultValue) {
    return get(key).orElse(defaultValue);
  }
}
