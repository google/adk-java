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

package com.google.adk.workflow;

import com.google.adk.annotations.Experimental;
import org.jspecify.annotations.Nullable;

/**
 * A signal that ends a node's run while it waits for input: thrown by {@link
 * FunctionNodes#requestInput} with its request, or when a child the node ran with {@link
 * NodeRunner#runChild} waits, so the node waits too. A pause is not an {@link Exception}, so {@code
 * catch (Exception e)} in a body does not stop it; {@code catch (Throwable t)} still does.
 */
@Experimental
final class NodeInterruptedException extends Error {
  private final @Nullable RequestInput request;

  NodeInterruptedException() {
    this(null);
  }

  NodeInterruptedException(@Nullable RequestInput request) {
    super("Node interrupted.");
    this.request = request;
  }

  /** Returns the request for input to send, or null when a child waits. */
  @Nullable RequestInput request() {
    return request;
  }
}
