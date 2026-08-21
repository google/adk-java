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

package com.google.adk.agents; // Changed package

import static com.google.adk.testing.TestUtils.createEscalateEvent;
import static com.google.adk.testing.TestUtils.createEvent;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createSubAgent;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.events.Event;
import com.google.adk.testing.TestBaseAgent;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LoopAgent}. */
@RunWith(JUnit4.class)
public final class LoopAgentTest {

  @Test
  public void runAsync_withNoAgents_returnsEmptyEvents() {
    LoopAgent loopAgent =
        LoopAgent.builder().name("loopAgent").subAgents(ImmutableList.of()).build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).isEmpty();
  }

  @Test
  public void runAsync_withSingleAgent_singleIteration_returnsEvents() {
    Event event1 = createEvent("event1");
    Event event2 = createEvent("event2");
    TestBaseAgent subAgent = createSubAgent("subAgent", Flowable.just(event1, event2));
    LoopAgent loopAgent =
        LoopAgent.builder()
            .name("loopAgent")
            .subAgents(ImmutableList.of(subAgent))
            .maxIterations(1)
            .build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1, event2).inOrder();
  }

  @Test
  public void runAsync_withSingleAgent_multipleIterations_returnsEvents() {
    Event event1 = createEvent("event1");
    Event event2 = createEvent("event2");
    Event event3 = createEvent("event3");
    Event event4 = createEvent("event4");
    TestBaseAgent subAgent =
        createSubAgent("subAgent", Flowable.just(event1, event2), Flowable.just(event3, event4));
    LoopAgent loopAgent =
        LoopAgent.builder()
            .name("loopAgent")
            .subAgents(ImmutableList.of(subAgent))
            .maxIterations(2)
            .build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1, event2, event3, event4).inOrder();
  }

  @Test
  public void runAsync_withMultipleAgents_loopsAndReturnsEvents() {
    Event event1 = createEvent("event1");
    Event event2 = createEvent("event2");
    Event event3 = createEvent("event3");
    Event event4 = createEvent("event4");
    TestBaseAgent subAgent1 =
        createSubAgent("subAgent1", Flowable.just(event1), Flowable.just(event3));
    TestBaseAgent subAgent2 =
        createSubAgent("subAgent2", Flowable.just(event2), Flowable.just(event4));
    LoopAgent loopAgent =
        LoopAgent.builder()
            .name("loopAgent")
            .subAgents(ImmutableList.of(subAgent1, subAgent2))
            .maxIterations(2)
            .build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1, event2, event3, event4).inOrder();
  }

  @Test
  public void runAsync_withEscalateAction_returnsEventsUpToEscalateAndStops() {
    Event event1 = createEvent("event1");
    Event escalateEvent2 = createEscalateEvent("escalate2");
    Event event3 = createEvent("event3");
    Event event4 = createEvent("event4");
    Flowable<Event> subAgent1Events = Flowable.just(event1, escalateEvent2, event3);
    Flowable<Event> subAgent2Events = Flowable.just(event4);
    TestBaseAgent subAgent1 = createSubAgent("subAgent1", subAgent1Events);
    TestBaseAgent subAgent2 = createSubAgent("subAgent2", subAgent2Events);
    LoopAgent loopAgent =
        LoopAgent.builder()
            .name("loopAgent")
            .subAgents(ImmutableList.of(subAgent1, subAgent2))
            .maxIterations(1)
            .build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1, escalateEvent2).inOrder();
  }

  @Test
  public void runAsync_withEscalateAction_loopsAndReturnsEventsUpToEscalateAndStops() {
    Event event1 = createEvent("event1");
    Event event2 = createEvent("event2");
    Event event3 = createEvent("event3");
    Event event4 = createEvent("event4");
    Event escalateEvent5 = createEscalateEvent("escalate5");
    Event escalateEvent6 = createEscalateEvent("escalate6");
    TestBaseAgent subAgent1 =
        createSubAgent("subAgent1", Flowable.just(event1, event2), Flowable.just(event4));
    TestBaseAgent subAgent2 =
        createSubAgent(
            "subAgent2", Flowable.just(event3), Flowable.just(escalateEvent5, escalateEvent6));
    LoopAgent loopAgent =
        LoopAgent.builder()
            .name("loopAgent")
            .subAgents(ImmutableList.of(subAgent1, subAgent2))
            .maxIterations(3)
            .build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).containsExactly(event1, event2, event3, event4, escalateEvent5).inOrder();
  }

  @Test
  public void runAsync_withNoMaxIterations_keepsLooping() {
    Event event1 = createEvent("event1");
    Event event2 = createEvent("event2");
    TestBaseAgent subAgent = createSubAgent("subAgent", () -> Flowable.just(event1, event2));
    LoopAgent loopAgent =
        LoopAgent.builder().name("loopAgent").subAgents(ImmutableList.of(subAgent)).build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);
    Iterable<Event> result = loopAgent.runAsync(invocationContext).blockingIterable();

    Iterable<Event> first10Events = Iterables.limit(result, 10);
    assertThat(first10Events)
        .containsExactly(
            event1, event2, event1, event2, event1, event2, event1, event2, event1, event2)
        .inOrder();
  }

  @Test
  public void runAsync_withEndInvocationInSubAgentCallback_stopsSubAgentButLoopContinues() {
    AtomicInteger normalAgentRunCount = new AtomicInteger(0);
    TestBaseAgent normalAgent =
        createSubAgent(
            "NormalAgent",
            () -> {
              int count = normalAgentRunCount.incrementAndGet();
              return Flowable.just(createEvent("Normal Agent Run " + count));
            });

    AtomicInteger subAgent2CallbackCount = new AtomicInteger(0);
    AtomicInteger subAgent2RunCount = new AtomicInteger(0);

    BeforeAgentCallback subAgent2BeforeCallback =
        (callbackContext) -> {
          int callbackCount = subAgent2CallbackCount.incrementAndGet();
          if (callbackCount > 1) {
            return Maybe.just(
                Content.fromParts(
                    Part.fromText("Exit Callback Triggered After " + callbackCount + " Runs")));
          }
          return Maybe.empty();
        };

    TestBaseAgent earlyExitAgent =
        new TestBaseAgent(
            "EarlyExitAgent",
            "An agent that exits early after its callback is called once",
            () -> {
              int count = subAgent2RunCount.incrementAndGet();
              return Flowable.just(createEvent("Early Exit Agent Run " + count));
            },
            /* subAgents= */ ImmutableList.of(),
            /* beforeAgentCallbacks= */ ImmutableList.of(subAgent2BeforeCallback),
            /* afterAgentCallbacks= */ ImmutableList.of());
    LoopAgent loopAgent =
        LoopAgent.builder()
            .name("loopAgent")
            .subAgents(ImmutableList.of(normalAgent, earlyExitAgent))
            .maxIterations(3)
            .build();
    InvocationContext invocationContext = createInvocationContext(loopAgent);

    List<Event> events = loopAgent.runAsync(invocationContext).toList().blockingGet();

    ImmutableList<String> eventTexts =
        events.stream()
            .filter(e -> e.content().isPresent())
            .map(e -> e.content().get().text())
            .collect(toImmutableList());

    assertThat(eventTexts)
        .containsExactly(
            "content for event Normal Agent Run 1",
            "content for event Early Exit Agent Run 1",
            "content for event Normal Agent Run 2",
            "Exit Callback Triggered After 2 Runs",
            "content for event Normal Agent Run 3",
            "Exit Callback Triggered After 3 Runs")
        .inOrder();

    assertThat(normalAgentRunCount.get()).isEqualTo(3);
    assertThat(subAgent2RunCount.get()).isEqualTo(1);
  }
}
