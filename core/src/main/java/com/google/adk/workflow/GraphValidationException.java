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

/**
 * Thrown when a workflow graph is malformed, such as when {@link Start} cannot reach a node or a
 * cycle has no routed edge. Building the workflow throws it, so a malformed graph never runs.
 */
@Experimental
public final class GraphValidationException extends IllegalArgumentException {

  GraphValidationException(String message) {
    super(message);
  }
}
