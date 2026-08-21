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

import io.reactivex.rxjava3.core.Single;

/**
 * Strategy interface for planning which sub-agent(s) to execute next.
 *
 * <p>A {@code Planner} is used by {@link PlannerAgent} to dynamically determine execution order at
 * runtime. The planning loop works as follows:
 *
 * <ol>
 *   <li>{@link #init} is called once before the loop starts
 *   <li>{@link #firstAction} returns the first action to execute
 *   <li>The selected agent(s) execute, producing events and updating session state
 *   <li>{@link #nextAction} is called with updated context to decide what to do next
 *   <li>Steps 3-4 repeat until {@link PlannerAction.Done} or max iterations
 * </ol>
 *
 * <p>Returns {@link Single}{@code <PlannerAction>} to support both synchronous planners (wrap in
 * {@code Single.just()}) and asynchronous planners that call an LLM.
 */
public interface Planner {

  /**
   * Initialize the planner with context and available agents. Called once before the planning loop
   * starts.
   *
   * <p>Default implementation is a no-op. Override to perform setup like building dependency
   * graphs.
   */
  default void init(PlanningContext context) {}

  /** Select the first action to execute. */
  Single<PlannerAction> firstAction(PlanningContext context);

  /** Select the next action based on updated state and events. */
  Single<PlannerAction> nextAction(PlanningContext context);
}
