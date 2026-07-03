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
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ParallelAgentTest {

  static class TestingAgent extends BaseAgent {
    private final long delayMillis;
    private final Scheduler scheduler;

    private TestingAgent(String name, String description, long delayMillis) {
      this(name, description, delayMillis, Schedulers.computation());
    }

    private TestingAgent(String name, String description, long delayMillis, Scheduler scheduler) {
      super(name, description, ImmutableList.of(), null, null);
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
                      .branch(invocationContext.branch().orElse(null))
                      .invocationId(invocationContext.invocationId())
                      .content(Content.fromParts(Part.fromText("Hello, async " + name() + "!")))
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
  public void runAsync_subAgentsExecuteInParallel_eventsOrderedByCompletion() {
    String agent1Name = "test_agent_1_delayed";
    String agent2Name = "test_agent_2_fast";
    String parallelAgentName = "test_parallel_agent";

    TestingAgent agent1 = new TestingAgent(agent1Name, "Delayed Agent", 500);
    TestingAgent agent2 = new TestingAgent(agent2Name, "Fast Agent", 0);

    ParallelAgent parallelAgent =
        ParallelAgent.builder().name(parallelAgentName).subAgents(agent1, agent2).build();

    InvocationContext invocationContext = createInvocationContext(parallelAgent);

    List<Event> events = parallelAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(2);

    // Agent2 (without a delay) should complete first
    Event firstEvent = events.get(0);
    assertThat(firstEvent.author()).isEqualTo(agent2Name);
    assertThat(firstEvent.content().get().parts().get().get(0).text())
        .hasValue("Hello, async " + agent2Name + "!");
    assertThat(firstEvent.branch().get()).endsWith(agent2Name);

    // Agent1 (with a delay) should complete second
    Event secondEvent = events.get(1);
    assertThat(secondEvent.author()).isEqualTo(agent1Name);
    assertThat(secondEvent.content().get().parts().get().get(0).text())
        .hasValue("Hello, async " + agent1Name + "!");
    assertThat(secondEvent.branch().get()).endsWith(agent1Name);
  }

  @Test
  public void runAsync_noSubAgents_returnsEmptyFlowable() {
    String parallelAgentName = "empty_parallel_agent";
    ParallelAgent parallelAgent =
        ParallelAgent.builder().name(parallelAgentName).subAgents(ImmutableList.of()).build();

    InvocationContext invocationContext = createInvocationContext(parallelAgent);
    List<Event> events = parallelAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).isEmpty();
  }

  static class BlockingAgent extends BaseAgent {
    private final long sleepMillis;

    private BlockingAgent(String name, long sleepMillis) {
      super(name, "Blocking Agent", ImmutableList.of(), null, null);
      this.sleepMillis = sleepMillis;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.fromCallable(
          () -> {
            Thread.sleep(sleepMillis);
            return Event.builder()
                .author(name())
                .branch(invocationContext.branch().orElse(null))
                .invocationId(invocationContext.invocationId())
                .content(Content.fromParts(Part.fromText("Done")))
                .build();
          });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      throw new UnsupportedOperationException("Not implemented");
    }
  }

  @Test
  public void runAsync_blockingSubAgents_shouldExecuteInParallel() {
    long sleepTime = 1000;
    BlockingAgent agent1 = new BlockingAgent("agent1", sleepTime);
    BlockingAgent agent2 = new BlockingAgent("agent2", sleepTime);

    ParallelAgent parallelAgent =
        ParallelAgent.builder().name("parallel_agent").subAgents(agent1, agent2).build();

    InvocationContext invocationContext = createInvocationContext(parallelAgent);

    long startTime = System.currentTimeMillis();
    List<Event> events = parallelAgent.runAsync(invocationContext).toList().blockingGet();
    long duration = System.currentTimeMillis() - startTime;

    assertThat(events).hasSize(2);
    // If parallel, duration should be less than 1.5 * sleepTime (1500ms).
    assertThat(duration).isAtLeast(sleepTime);
    assertThat(duration).isLessThan((long) (1.5 * sleepTime));
  }

  @Test
  public void runAsync_withTestScheduler_usesVirtualTime() {
    TestScheduler testScheduler = new TestScheduler();
    long delayMillis = 1000;
    TestingAgent agent =
        new TestingAgent("delayed_agent", "Delayed Agent", delayMillis, testScheduler);

    ParallelAgent parallelAgent =
        ParallelAgent.builder()
            .name("parallel_agent")
            .subAgents(agent)
            .scheduler(testScheduler)
            .build();

    InvocationContext invocationContext = createInvocationContext(parallelAgent);

    TestSubscriber<Event> testSubscriber = parallelAgent.runAsync(invocationContext).test();

    testScheduler.advanceTimeBy(delayMillis - 100, MILLISECONDS);
    testSubscriber.assertNoValues();
    testSubscriber.assertNotComplete();
    testScheduler.advanceTimeBy(200, MILLISECONDS);
    testSubscriber.assertValueCount(1);
    testSubscriber.assertComplete();
  }
}
