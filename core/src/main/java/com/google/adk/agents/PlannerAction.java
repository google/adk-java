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

import com.google.common.collect.ImmutableList;

/**
 * Represents the next action a {@link Planner} wants the {@link PlannerAgent} to take.
 *
 * <p>This is a sealed interface with four variants:
 *
 * <ul>
 *   <li>{@link RunAgents} — execute one or more sub-agents (multiple agents run in parallel)
 *   <li>{@link Done} — planning is complete, no result to emit
 *   <li>{@link DoneWithResult} — planning is complete with a final text result
 *   <li>{@link NoOp} — skip this iteration (no-op), then ask the planner for the next action
 * </ul>
 */
public sealed interface PlannerAction
    permits PlannerAction.RunAgents,
        PlannerAction.Done,
        PlannerAction.DoneWithResult,
        PlannerAction.NoOp {

  /** Run the specified sub-agent(s). Multiple agents are run in parallel. */
  record RunAgents(ImmutableList<BaseAgent> agents) implements PlannerAction {
    public RunAgents(BaseAgent singleAgent) {
      this(ImmutableList.of(singleAgent));
    }
  }

  /** Plan is complete, no result to emit. */
  record Done() implements PlannerAction {}

  /** Plan is complete with a final text result. */
  record DoneWithResult(String result) implements PlannerAction {}

  /** Skip this iteration (no-op). */
  record NoOp() implements PlannerAction {}
}
