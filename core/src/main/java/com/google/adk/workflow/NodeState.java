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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** A node's progress within one run of a workflow graph. */
@Experimental
final class NodeState {

  /** The key under which a workflow's checkpoint holds the checkpoints of its nodes. */
  static final String NODES_KEY = "nodes";

  /** Where the node stands in the current run. */
  NodeStatus status;

  /** The IDs of the interrupts the node is waiting on, empty unless it is waiting. */
  ImmutableList<String> interrupts = ImmutableList.of();

  NodeState(NodeStatus status) {
    this.status = status;
  }

  /**
   * Returns what a resumable session records for the node. Its keys and numeric status are shared
   * with the other ADK languages. The node's answers are left out: they can hold a credential the
   * user sent, and a resume reads them from the session's events.
   */
  ImmutableMap<String, Object> toCheckpoint() {
    return ImmutableMap.of("status", status.code(), "interrupts", interrupts);
  }
}
