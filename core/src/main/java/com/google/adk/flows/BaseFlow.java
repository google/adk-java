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

package com.google.adk.flows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

/** Interface for the execution flows to run a group of agents. */
public interface BaseFlow {

  /**
   * Run this flow.
   *
   * <p>To implement this method, the flow should follow the below requirements:
   *
   * <ol>
   *   <li>1. `session` should be treated as immutable, DO NOT change it.
   *   <li>2. The caller who trigger the flow is responsible for updating the session as the events
   *       being generated. The subclass implementation will assume session is updated after each
   *       yield event statement.
   *   <li>3. A flow may spawn sub-agent flows depending on the agent definition.
   * </ol>
   */
  Flowable<Event> run(InvocationContext invocationContext);

  default Flowable<Event> runLive(InvocationContext invocationContext) {
    throw new UnsupportedOperationException("Not implemented");
  }
}
