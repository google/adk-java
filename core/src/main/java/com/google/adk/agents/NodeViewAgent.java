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

package com.google.adk.agents;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.workflow.Node;
import io.reactivex.rxjava3.core.Flowable;

/**
 * A read-only agent representing any workflow node, including an agent, so code reading the agent
 * of an app, runner, or invocation still works when it runs a node. This agent never runs; the
 * runner runs the node directly.
 */
@Experimental
public final class NodeViewAgent extends BaseAgent {

  private final Node node;

  /**
   * Creates an agent that stands in for {@code node}, using its name and description.
   *
   * @throws IllegalArgumentException if {@code node} is a {@code NodeViewAgent} or its name is
   *     invalid for an agent.
   */
  public NodeViewAgent(Node node) {
    super(
        node.name(),
        node.description(),
        /* subAgents= */ null,
        /* beforeAgentCallback= */ null,
        /* afterAgentCallback= */ null);
    checkArgument(!(node instanceof NodeViewAgent), "A node view agent needs no node view.");
    this.node = node;
  }

  /** Returns the node this agent stands in for. */
  public Node node() {
    return node;
  }

  // Callers get this error instead of the context check in BaseAgent.runAsync and runLive.
  @Override
  public Flowable<Event> runAsync(InvocationContext parentContext) {
    return Flowable.error(neverRuns());
  }

  @Override
  public Flowable<Event> runLive(InvocationContext parentContext) {
    return Flowable.error(neverRuns());
  }

  @Override
  protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    return Flowable.error(neverRuns());
  }

  @Override
  protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    return Flowable.error(neverRuns());
  }

  private UnsupportedOperationException neverRuns() {
    return new UnsupportedOperationException(
        "A node view agent never runs; it stands in for node '" + name() + "'.");
  }
}
