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

  static class TestAgent extends BaseAgent {
    private final long delayMillis;
    private final Scheduler scheduler;
    private final String content;
    private final EventActions actions;

    private TestAgent(String name, long delayMillis, Scheduler scheduler, String content) {
      this(name, delayMillis, scheduler, content, null);
    }

    private TestAgent(
        String name, long delayMillis, Scheduler scheduler, String content, EventActions actions) {
      super(name, "Test Agent", ImmutableList.of(), null, null);
      this.delayMillis = delayMillis;
      this.scheduler = scheduler;
      this.content = content;
      this.actions = actions;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      Flowable<Event> event =
          Flowable.fromCallable(
              () -> {
                Event.Builder builder =
                    Event.builder()
                        .author(name())
                        .branch(invocationContext.branch().orElse(null))
                        .invocationId(invocationContext.invocationId())
                        .content(Content.fromParts(Part.fromText(content)));

                if (actions != null) {
                  builder.actions(actions);
                }
                return builder.build();
              });

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

    TestAgent escalatingAgent =
        new TestAgent(
            "escalating_agent",
            100,
            testScheduler,
            "Escalating!",
            EventActions.builder().escalate(true).build());
    TestAgent slowAgent = new TestAgent("slow_agent", 500, testScheduler, "Finished");
    TestAgent fastAgent = new TestAgent("fast_agent", 50, testScheduler, "Finished");

    ParallelAgent parallelAgent =
        ParallelAgent.builder()
            .name("parallel_agent")
            .subAgents(fastAgent, escalatingAgent, slowAgent)
            .scheduler(testScheduler)
            .build();

    InvocationContext invocationContext = createInvocationContext(parallelAgent);

    var subscriber = parallelAgent.runAsync(invocationContext).test();

    // Fast agent completes at 50ms (before the escalation)
    testScheduler.advanceTimeBy(50, MILLISECONDS);
    subscriber.assertValueCount(1);
    assertThat(subscriber.values().get(0).author()).isEqualTo("fast_agent");

    // Escalating agent completes at 100ms
    testScheduler.advanceTimeBy(50, MILLISECONDS);
    subscriber.assertValueCount(2);

    Event event1 = subscriber.values().get(0);
    assertThat(event1.author()).isEqualTo("fast_agent");

    Event event2 = subscriber.values().get(1);
    assertThat(event2.author()).isEqualTo("escalating_agent");
    assertThat(event2.actions().escalate()).hasValue(true);

    subscriber.assertComplete();

    // Slow agent would complete at 500ms, but test scheduler advances time to prove
    // sequence was forcibly terminated!
    testScheduler.advanceTimeBy(400, MILLISECONDS);

    // Test RxJava Disposal behavior: SlowAgent won't emit anything
    subscriber.assertValueCount(2);
  }
}
