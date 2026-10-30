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
import java.time.Duration;

/** Thrown when a node's attempt outlasts the timeout that {@link NodeConfig#timeout()} sets. */
@Experimental
public final class NodeTimeoutException extends NodeExecutionException {

  private final String nodeName;
  private final Duration timeout;

  NodeTimeoutException(String nodeName, Duration timeout) {
    super(String.format("Node '%s' timed out after %d ms.", nodeName, timeout.toMillis()));
    this.nodeName = nodeName;
    this.timeout = timeout;
  }

  /** Returns the name of the node that timed out. */
  public String nodeName() {
    return nodeName;
  }

  /** Returns the timeout that the node's attempt outlasted. */
  public Duration timeout() {
    return timeout;
  }
}
