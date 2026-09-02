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

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Wire-format keys and helpers for workflow-agent resumability checkpoints. The keys match Python
 * and Kotlin ADK so persisted state is portable across languages.
 */
final class WorkflowAgentStates {

  /** Key holding the name of the current/next sub-agent in a Sequential or Loop checkpoint. */
  static final String CURRENT_SUB_AGENT = "current_sub_agent";

  /** Key holding the completed-iteration count in a Loop checkpoint. */
  static final String TIMES_LOOPED = "times_looped";

  /**
   * Returns the index of the sub-agent to resume from by name, or 0 (with a warning) when the name
   * is null or no longer present. Mirrors the Kotlin {@code findIndexForResumption}.
   */
  static int findIndexForResumption(
      List<? extends BaseAgent> subAgents, @Nullable String agentName, Logger logger) {
    if (agentName == null) {
      return 0;
    }
    for (int i = 0; i < subAgents.size(); i++) {
      if (agentName.equals(subAgents.get(i).name())) {
        return i;
      }
    }
    // Do not log the agent name (treat names as potentially sensitive); log only the shape.
    logger.warn("Restored sub-agent not found in current sub-agents list; resuming from index 0.");
    return 0;
  }

  private WorkflowAgentStates() {}
}
