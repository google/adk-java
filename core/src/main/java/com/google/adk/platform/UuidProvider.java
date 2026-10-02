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

import java.util.UUID;

/**
 * Supplies new unique identifiers for ADK-generated IDs (events, invocations, function calls).
 *
 * <p>The default {@link #SYSTEM} provider returns random UUIDs. Integrations that need customized
 * identifiers configure a provider once on the runner ({@code Runner.Builder#uuidProvider}), which
 * carries it on every {@code InvocationContext} it creates. Identifiers that a model adapter
 * assigns itself while aggregating a streamed response (for example the client function-call ids
 * the Gemini adapter adds in streaming mode) are not drawn from the provider.
 *
 * <p>Implementations must be thread-safe and must return a distinct value on every call: ADK uses
 * the values as event, invocation, function-call, and session identifiers, and may call a provider
 * concurrently from several RxJava worker threads, for example while running parallel tool calls or
 * a {@code ParallelAgent}.
 */
@FunctionalInterface
public interface UuidProvider {

  /** A provider backed by {@link UUID#randomUUID()}. */
  UuidProvider SYSTEM = () -> UUID.randomUUID().toString();

  /** Returns a new unique identifier. */
  String newUuid();
}
