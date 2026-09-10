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

package com.google.adk.platform;

import java.time.Instant;

/**
 * Supplies the current time for ADK-generated timestamps.
 *
 * <p>The default {@link #SYSTEM} provider reads the wall clock. Integrations that need custom
 * timestamps can install a custom provider on an invocation; see {@code InvocationContext}.
 */
@FunctionalInterface
public interface TimeProvider {

  /** A provider backed by the system wall clock ({@link Instant#now()}). */
  TimeProvider SYSTEM = Instant::now;

  /** Returns the current time. */
  Instant now();
}
