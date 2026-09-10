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

package com.google.adk.plugins.reflectandretry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consecutive-failure counts, one per tool per tracking scope.
 *
 * <p>Kept apart from {@link ReflectAndRetryToolPlugin} so the plugin decides what a failure means
 * and this class decides only how the counts are held.
 *
 * <p>Nothing ever enumerates the tools within one scope, so the pair is simply the key, and {@link
 * Map#merge} and {@link Map#remove} each apply atomically on a {@link ConcurrentHashMap} — parallel
 * tool failures cannot lose a count.
 */
final class ToolFailureTracker {

  private final ConcurrentHashMap<ToolInScope, Integer> counts = new ConcurrentHashMap<>();

  /** Records one more consecutive failure and returns the new count, atomically. */
  int recordFailure(String scopeKey, String toolName) {
    return counts.merge(new ToolInScope(scopeKey, toolName), 1, Integer::sum);
  }

  /**
   * Clears one tool's failure count, leaving every other tool's untouched — a success with one tool
   * must not forgive another's failures.
   */
  void reset(String scopeKey, String toolName) {
    counts.remove(new ToolInScope(scopeKey, toolName));
  }

  /** The counter key: one tool, within one tracking scope. */
  private record ToolInScope(String scopeKey, String toolName) {}
}
