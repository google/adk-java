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

package com.google.adk.planner;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Planner;
import com.google.adk.agents.PlannerAction;
import com.google.adk.agents.PlanningContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.List;

/**
 * A planner that cycles through sub-agents repeatedly, stopping when an escalate event is detected
 * or the maximum number of cycles is reached.
 */
public final class LoopPlanner implements Planner {

  private final int maxCycles;
  // Mutable state — planners are used within a single reactive pipeline and are not thread-safe.
  private int cursor;
  private int cycleCount;
  private ImmutableList<BaseAgent> agents;

  public LoopPlanner(int maxCycles) {
    this.maxCycles = maxCycles;
  }

  @Override
  public void init(PlanningContext context) {
    agents = context.availableAgents();
    cursor = 0;
    cycleCount = 0;
  }

  @Override
  public Single<PlannerAction> firstAction(PlanningContext context) {
    cursor = 0;
    cycleCount = 0;
    return selectNext(context);
  }

  @Override
  public Single<PlannerAction> nextAction(PlanningContext context) {
    if (hasEscalateEvent(context.events())) {
      return Single.just(new PlannerAction.Done());
    }
    return selectNext(context);
  }

  private Single<PlannerAction> selectNext(PlanningContext context) {
    if (agents == null || agents.isEmpty()) {
      return Single.just(new PlannerAction.Done());
    }

    int idx = cursor++;
    if (idx >= agents.size()) {
      int cycle = ++cycleCount;
      if (cycle >= maxCycles) {
        return Single.just(new PlannerAction.Done());
      }
      cursor = 1;
      idx = 0;
    }
    return Single.just(new PlannerAction.RunAgents(agents.get(idx)));
  }

  private static boolean hasEscalateEvent(List<Event> events) {
    if (events.isEmpty()) {
      return false;
    }
    Event lastEvent = events.get(events.size() - 1);
    return lastEvent.actions().escalate().orElse(false);
  }
}
