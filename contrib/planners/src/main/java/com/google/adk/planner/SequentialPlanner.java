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
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.atomic.AtomicInteger;

/** A planner that runs sub-agents one at a time in order. */
public final class SequentialPlanner implements Planner {

  private final AtomicInteger cursor = new AtomicInteger(0);
  private ImmutableList<BaseAgent> agents;

  @Override
  public void init(PlanningContext context) {
    agents = context.availableAgents();
    cursor.set(0);
  }

  @Override
  public Single<PlannerAction> firstAction(PlanningContext context) {
    cursor.set(0);
    return selectNext();
  }

  @Override
  public Single<PlannerAction> nextAction(PlanningContext context) {
    return selectNext();
  }

  private Single<PlannerAction> selectNext() {
    int idx = cursor.getAndIncrement();
    if (agents == null || idx >= agents.size()) {
      return Single.just(new PlannerAction.Done());
    }
    return Single.just(new PlannerAction.RunAgents(agents.get(idx)));
  }
}
