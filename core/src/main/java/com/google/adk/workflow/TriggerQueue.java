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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The pending triggers of each node in a workflow run. Each node keeps its own queue, so the
 * triggers of parallel branches or loops are served in arrival order without affecting other nodes.
 */
@Experimental
final class TriggerQueue {

  private final Map<String, Deque<Trigger>> queues = new LinkedHashMap<>();

  /** Returns the names of the nodes with at least one pending trigger, in first-queued order. */
  ImmutableList<String> queuedNodeNames() {
    return ImmutableList.copyOf(queues.keySet());
  }

  /** Queues {@code trigger} for the node named {@code nodeName}. */
  void enqueue(String nodeName, Trigger trigger) {
    queues.computeIfAbsent(nodeName, unused -> new ArrayDeque<>()).addLast(trigger);
  }

  /** Removes and returns the next pending trigger of the node named {@code nodeName}, if any. */
  Optional<Trigger> poll(String nodeName) {
    Deque<Trigger> queue = queues.get(nodeName);
    if (queue == null) {
      return Optional.empty();
    }
    Trigger trigger = queue.removeFirst();
    if (queue.isEmpty()) {
      queues.remove(nodeName);
    }
    return Optional.of(trigger);
  }
}
