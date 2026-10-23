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
 * The status of a node within one run of a workflow graph. A resumable session records its {@link
 * #code}, which the other ADK languages share, so the codes must not change.
 */
@Experimental
enum NodeStatus {
  /** Running now. */
  RUNNING(2),

  /** Finished; a node that waits for output finishes only once it produces an output or routes. */
  COMPLETED(3),

  /** Paused, either awaiting a user's answers or awaiting another trigger. */
  WAITING(4);

  private final int code;

  NodeStatus(int code) {
    this.code = code;
  }

  /** Returns the number that a resumable session records for this status. */
  int code() {
    return code;
  }
}
