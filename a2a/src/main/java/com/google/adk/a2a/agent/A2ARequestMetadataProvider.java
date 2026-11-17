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
package com.google.adk.a2a.agent;

import com.google.adk.agents.InvocationContext;
import io.a2a.spec.Message;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Supplies application-selected metadata for an outgoing A2A message. */
@FunctionalInterface
public interface A2ARequestMetadataProvider {

  /**
   * Returns JSON-serializable metadata after the message's parts and identifiers have been set.
   * Returning {@code null} leaves the message unchanged. An exception aborts sending the message.
   *
   * <p>The result is attached to {@link Message#getMetadata()}, not request-level metadata or
   * authentication headers. On an ADK receiver it is available through the incoming A2A message; it
   * is not automatically copied into session state or {@code RunConfig.customMetadata()}.
   */
  @Nullable Map<String, Object> provide(
      InvocationContext invocationContext, Message outgoingMessage);
}
