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

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ParallelAgentEscalationTest {

  static class EscalatingAgent extends BaseAgent {
    private final long delayMillis;
    private final Scheduler scheduler;

    private EscalatingAgent(String name, long delayMillis, Scheduler scheduler) {
      super(name, "Escalating Agent", ImmutableList.of(), null, null);
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      Flowable<Event> event =
          Flowable.fromCallable(
              () ->
                  Event.builder()
                      .author(name())
                      .branch(invocationContext.branch())
                      .invocationId(invocationContext.invocationId())
                      .content(Content.fromParts(Part.fromText("Escalating!")))
                      .actions(EventActions.builder().escalate(true).build())
                      .build());

      if (delayMillis > 0) {
        return event.delay(delayMillis, MILLISECONDS, scheduler);
      }
      return event;
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      throw new UnsupportedOperationException("Not implemented");
    }
  }

  static class SlowAgent extends BaseAgent {
    private final long delayMillis;
    private final Scheduler scheduler;

    private SlowAgent(String name, long delayMillis, Scheduler scheduler) {
      super(name, "Slow Agent", ImmutableList.of(), null, null);
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      Flowable<Event> event =
          Flowable.fromCallable(
              () ->
                  Event.builder()
                      .author(name())
                      .branch(invocationContext.branch())
                      .invocationId(invocationContext.invocationId())
                      .content(Content.fromParts(Part.fromText("Finished")))
                      .build());

      if (delayMillis > 0) {
        return event.delay(delayMillis, MILLISECONDS, scheduler);
      }
      return event;
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      throw new UnsupportedOperationException("Not implemented");
    }
  }

  @Test
  public void runAsync_escalationEvent_shortCircuitsOtherAgents() {
    TestScheduler testScheduler = new TestScheduler();

    EscalatingAgent agent1 = new EscalatingAgent("agent1", 100, testScheduler);
    SlowAgent agent2 = new SlowAgent("agent2", 500, testScheduler);

    ParallelAgent parallelAgent =
        ParallelAgent.builder()
            .name("parallel_agent")
            .subAgents(agent1, agent2)
            .scheduler(testScheduler)
            .build();

    InvocationContext invocationContext = createInvocationContext(parallelAgent);

    var subscriber = parallelAgent.runAsync(invocationContext).test();

    testScheduler.advanceTimeBy(200, MILLISECONDS);

    subscriber.assertValueCount(1);
    Event event = subscriber.values().get(0);
    assertThat(event.author()).isEqualTo("agent1");
    assertThat(event.actions().escalate()).hasValue(true);

    subscriber.assertComplete();
    testScheduler.advanceTimeBy(1000, MILLISECONDS);
    subscriber.assertValueCount(1);
  }
}
