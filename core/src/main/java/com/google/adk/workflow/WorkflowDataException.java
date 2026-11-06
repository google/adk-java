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
 * Thrown when data arriving during a run does not fit the receiving node, such as an input of a
 * type the node cannot handle. Because the graph is wired correctly and only this run's data is
 * wrong, another run of the same workflow can succeed.
 */
@Experimental
public final class WorkflowDataException extends IllegalArgumentException {

  public WorkflowDataException(String message) {
    super(message);
  }

  public WorkflowDataException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
