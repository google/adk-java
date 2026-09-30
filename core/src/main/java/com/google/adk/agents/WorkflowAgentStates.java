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
import java.util.Map;
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
   * Returns the index of the sub-agent to resume from by name, or 0 when the name is null or (with
   * a warning) when it is no longer present in the sub-agents list.
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
    // Agent names are developer-assigned identifiers, not user data, so log the missing name.
    logger.warn("Restored sub-agent '{}' not found; resuming from index 0.", agentName);
    return 0;
  }

  /** Returned by {@link #resumeIndex} when the restored checkpoint names no sub-agent. */
  static final int NO_SUB_AGENT_NAMED = -1;

  /**
   * Returns the index of the sub-agent to resume from, or {@link #NO_SUB_AGENT_NAMED} when the
   * checkpoint names none -- an absent or empty name, which Python treats as "already finished".
   * Any other unreadable value throws rather than being read as finished, so a session written by
   * another runtime cannot silently skip the whole workflow.
   */
  static int resumeIndex(
      @Nullable Map<String, Object> state, List<? extends BaseAgent> subAgents, Logger logger) {
    if (state == null) {
      return 0;
    }
    if (!state.containsKey(CURRENT_SUB_AGENT)) {
      return NO_SUB_AGENT_NAMED;
    }
    Object current = state.get(CURRENT_SUB_AGENT);
    // Restarting the workflow would re-run side-effecting sub-agents, so fail the resume instead.
    if (!(current instanceof String name)) {
      throw new IllegalStateException(rejection(CURRENT_SUB_AGENT, current, "a string"));
    }
    return name.isEmpty() ? NO_SUB_AGENT_NAMED : findIndexForResumption(subAgents, name, logger);
  }

  /**
   * Returns the completed-iteration count from a Loop checkpoint, or 0 when it holds none. As with
   * {@link #resumeIndex}, an unreadable value throws instead of silently restarting the count.
   */
  static int timesLooped(@Nullable Map<String, Object> state) {
    if (state == null || !state.containsKey(TIMES_LOOPED)) {
      return 0;
    }
    Object value = state.get(TIMES_LOOPED);
    if (!(value instanceof Number number)) {
      throw new IllegalStateException(rejection(TIMES_LOOPED, value, "a number"));
    }
    return number.intValue();
  }

  /** Message for a checkpoint field that cannot be read; names the type, never the value. */
  private static String rejection(String field, @Nullable Object value, String expected) {
    return String.format(
        "Cannot resume: checkpoint field %s is %s, not %s.",
        field, value == null ? "null" : "a " + value.getClass().getSimpleName(), expected);
  }

  private WorkflowAgentStates() {}
}
