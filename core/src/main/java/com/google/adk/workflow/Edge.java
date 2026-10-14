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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableList;

/**
 * A connection between two nodes in a workflow graph. An edge with no routes fires whenever its
 * source completes; any other edge fires only when one of its routes is selected, as {@link Route}
 * describes.
 *
 * @param from The node whose completion fires this edge.
 * @param to The node this edge triggers.
 * @param routes The routes that select this edge. An empty list makes the edge unconditional.
 */
@Experimental
public record Edge(Node from, Node to, ImmutableList<Route> routes) {

  public Edge {
    checkNotNull(from);
    checkNotNull(to);
    checkNotNull(routes);
  }

  /** Creates an unconditional edge. */
  public Edge(Node from, Node to) {
    this(from, to, ImmutableList.of());
  }

  /** Creates an edge selected by a single route. */
  public Edge(Node from, Node to, Route route) {
    this(from, to, ImmutableList.of(route));
  }

  /** Returns {@code true} if this edge fires regardless of the routes its source emits. */
  public boolean isUnconditional() {
    return routes.isEmpty();
  }
}
