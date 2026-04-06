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
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Context provided to a {@link Planner} during the planning loop.
 *
 * <p>Wraps an {@link InvocationContext} to expose the session state (world state), events, and
 * available sub-agents. Planners use this to inspect the current state and decide which agent(s) to
 * run next.
 */
public class PlanningContext {

  private final InvocationContext invocationContext;
  private final ImmutableList<BaseAgent> availableAgents;

  public PlanningContext(
      InvocationContext invocationContext, ImmutableList<BaseAgent> availableAgents) {
    this.invocationContext = invocationContext;
    this.availableAgents = availableAgents;
  }

  /** Returns the session state — the shared "world state" that agents read and write. */
  public Map<String, Object> state() {
    return invocationContext.session().state();
  }

  /** Returns all events in the current session. */
  public List<Event> events() {
    return invocationContext.session().events();
  }

  /** Returns the sub-agents available for the planner to select from. */
  public ImmutableList<BaseAgent> availableAgents() {
    return availableAgents;
  }

  /** Returns the user content that initiated this invocation, if any. */
  public Optional<Content> userContent() {
    return invocationContext.userContent();
  }

  /**
   * Finds an available agent by name.
   *
   * @throws IllegalArgumentException if no agent with the given name is found.
   */
  public BaseAgent findAgent(String name) {
    return availableAgents.stream()
        .filter(agent -> agent.name().equals(name))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No available agent with name: "
                        + name
                        + ". Available: "
                        + availableAgents.stream().map(BaseAgent::name).toList()));
  }

  /** Returns the full {@link InvocationContext} for advanced use cases. */
  public InvocationContext invocationContext() {
    return invocationContext;
  }
}
