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

package com.google.adk.testing;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.function.Supplier;

/** A test agent that returns events from a supplier. */
public class TestBaseAgent extends BaseAgent {
  private final Supplier<Flowable<Event>> eventSupplier;
  private int invocationCount = 0;
  private InvocationContext lastInvocationContext;

  public TestBaseAgent(
      String name,
      String description,
      Supplier<Flowable<Event>> eventSupplier,
      List<? extends BaseAgent> subAgents,
      List<BeforeAgentCallback> beforeAgentCallbacks,
      List<AfterAgentCallback> afterAgentCallbacks) {
    super(name, description, subAgents, beforeAgentCallbacks, afterAgentCallbacks);
    this.eventSupplier = eventSupplier;
  }

  TestBaseAgent(
      String name, Supplier<Flowable<Event>> eventSupplier, List<? extends BaseAgent> subAgents) {
    this(name, "description", eventSupplier, subAgents, null, null);
  }

  public TestBaseAgent(
      String name,
      String description,
      List<BeforeAgentCallback> beforeAgentCallbacks,
      List<AfterAgentCallback> afterAgentCallbacks,
      Supplier<Flowable<Event>> eventSupplier) {
    this(name, description, eventSupplier, null, beforeAgentCallbacks, afterAgentCallbacks);
  }

  @Override
  public Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
    lastInvocationContext = invocationContext.toBuilder().build();
    invocationCount++;
    return eventSupplier.get();
  }

  @Override
  public Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
    lastInvocationContext = invocationContext.toBuilder().build();
    invocationCount++;
    return eventSupplier.get();
  }

  public int getInvocationCount() {
    return invocationCount;
  }

  public InvocationContext getLastInvocationContext() {
    return lastInvocationContext;
  }
}
