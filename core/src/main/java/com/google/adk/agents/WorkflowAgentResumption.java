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

package com.google.adk.agents;

import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import java.util.List;
import java.util.Optional;

/**
 * Helpers for resuming workflow agents from session events. Temporary until session resumption
 * (persisted agent state) is available.
 */
final class WorkflowAgentResumption {

  /**
   * Index of the direct sub-agent whose subtree authored the call the latest event resumes, or
   * empty when not resuming into this workflow.
   */
  static Optional<Integer> resumeSubAgentIndex(
      InvocationContext invocationContext, List<? extends BaseAgent> subAgents) {
    Optional<String> author =
        Functions.findMatchingFunctionCallEvent(invocationContext.session().events())
            .map(Event::author);
    if (author.isEmpty()) {
      return Optional.empty();
    }
    for (int i = 0; i < subAgents.size(); i++) {
      // findAgent matches the sub-agent itself or a descendant.
      if (subAgents.get(i).findAgent(author.get()).isPresent()) {
        return Optional.of(i);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the event emits a long-running call still awaiting a response (e.g. a HITL request).
   */
  static boolean hasPendingLongRunningCall(Event event) {
    return Functions.hasPendingLongRunningCall(event);
  }

  private WorkflowAgentResumption() {}
}
