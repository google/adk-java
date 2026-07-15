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

package com.google.adk.planner.goap;

/**
 * Policy governing how the planner reacts to missing expected outputs after an agent group
 * executes.
 */
public sealed interface ReplanPolicy
    permits ReplanPolicy.FailStop, ReplanPolicy.Replan, ReplanPolicy.Ignore {

  /** Stop immediately on failure with an error message. */
  record FailStop() implements ReplanPolicy {}

  /**
   * Attempt to recompute the remaining plan from current world state.
   *
   * @param maxAttempts maximum number of consecutive replan attempts before falling back to
   *     fail-stop. Must be {@code >= 1}.
   */
  record Replan(int maxAttempts) implements ReplanPolicy {
    public Replan {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
      }
    }
  }

  /** Ignore failures and proceed with the remaining plan as-is. */
  record Ignore() implements ReplanPolicy {}
}
