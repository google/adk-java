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
 * Thrown when a node's input does not fit the node, such as a value of the wrong type for its input
 * port or one the node cannot work on. The message never quotes the value.
 */
@Experimental
public final class NodeInputValidationException extends WorkflowDataException {

  public NodeInputValidationException(String message) {
    super(message);
  }

  public NodeInputValidationException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
