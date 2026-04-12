/*
 * Copyright 2025 Google LLC
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

package com.google.adk.planner.p2p;

import com.google.adk.planner.goap.AgentMetadata;
import java.util.Map;

/**
 * Tracks activation state for a single agent in P2P planning.
 *
 * <p>An agent can activate when: it is not currently executing, it is marked as should-execute, and
 * all its input keys are present in the session state.
 */
final class AgentActivator {

  private final AgentMetadata metadata;
  private boolean executing = false;
  private boolean shouldExecute = true;

  AgentActivator(AgentMetadata metadata) {
    this.metadata = metadata;
  }

  /** Returns the agent name this activator manages. */
  String agentName() {
    return metadata.agentName();
  }

  /** Returns true if the agent can be activated given the current state. */
  boolean canActivate(Map<String, Object> state) {
    return !executing
        && shouldExecute
        && metadata.inputKeys().stream().allMatch(state::containsKey);
  }

  /** Marks the agent as currently executing. */
  void startExecution() {
    executing = true;
    shouldExecute = false;
  }

  /** Marks the agent as finished executing. */
  void finishExecution() {
    executing = false;
  }

  /**
   * Called when another agent produces output. If the produced key is one of this agent's inputs,
   * marks this agent for re-execution.
   */
  void onStateChanged(String producedKey) {
    if (metadata.inputKeys().contains(producedKey)) {
      shouldExecute = true;
    }
  }
}
