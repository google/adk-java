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

package com.google.adk.flows.llmflows;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PersistBarrierTest {

  private InvocationContext context;

  @Before
  public void setUp() {
    context =
        InvocationContext.builder()
            .sessionService(mock(BaseSessionService.class))
            .invocationId("inv-1")
            .agent(mock(BaseAgent.class))
            .session(Session.builder("s").build())
            .build();
  }

  private static Event event(String id) {
    return Event.builder().id(id).author("agent").build();
  }

  @Test
  public void awaitBeforeMark_completesOnMark_andDrainsPending() {
    PersistBarrier.enable(context);

    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test();

    observer.assertNotComplete();
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(1);

    PersistBarrier.markPersisted(context, "e1");

    observer.assertComplete();
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void markBeforeAwait_completesImmediately_noPending() {
    PersistBarrier.enable(context);

    PersistBarrier.markPersisted(context, "e1");
    PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test().assertComplete();

    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void sameEventAwaitedTwice_secondAwaitStillCompletes_andNothingLingers() {
    // Mirrors an agent transfer: a sub-agent event is awaited by both the sub-agent and parent
    // flows but persisted once; the second await must still complete.
    PersistBarrier.enable(context);

    TestObserver<Void> subLevel =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test();
    PersistBarrier.markPersisted(context, "e1");
    subLevel.assertComplete();
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);

    PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test().assertComplete();
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void multiEventStep_completesOnlyAfterAllMarked() {
    PersistBarrier.enable(context);

    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"), event("e2"))).test();

    PersistBarrier.markPersisted(context, "e1");
    observer.assertNotComplete();

    PersistBarrier.markPersisted(context, "e2");
    observer.assertComplete();
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void markFailedBeforeAwait_awaitFails() {
    PersistBarrier.enable(context);
    RuntimeException error = new RuntimeException("append failed");

    PersistBarrier.markFailed(context, "e1", error);
    PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test().assertError(error);

    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void awaitBeforeMarkFailed_awaitFails() {
    PersistBarrier.enable(context);
    RuntimeException error = new RuntimeException("append failed");

    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test();
    observer.assertNotComplete();

    PersistBarrier.markFailed(context, "e1", error);

    observer.assertError(error);
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void stepWithOneFailedEvent_awaitFails() {
    // A step's await fails if any of its events fails to persist, so the next step does not run.
    PersistBarrier.enable(context);
    RuntimeException error = new RuntimeException("append failed");

    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"), event("e2"))).test();

    PersistBarrier.markPersisted(context, "e1");
    PersistBarrier.markFailed(context, "e2", error);

    observer.assertError(error);
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void notEnabled_awaitIsNoOp() {
    // No enable(): flow runs without a Runner, so await must not block forever.
    PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test().assertComplete();
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  @Test
  public void concurrentAwaitAndMark_allComplete_andDrain() throws Exception {
    // awaitPersisted (flow thread) and markPersisted (async appendEvent thread) race on each id;
    // none may be stranded and every subject must be dropped.
    PersistBarrier.enable(context);
    int eventCount = 1000;
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < eventCount; i++) {
      ids.add("e" + i);
    }
    List<TestObserver<Void>> observers = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch start = new CountDownLatch(1);

    Thread awaiter =
        new Thread(
            () -> {
              awaitQuietly(start);
              for (String id : ids) {
                observers.add(
                    PersistBarrier.awaitPersisted(context, ImmutableList.of(event(id))).test());
              }
            });
    Thread marker =
        new Thread(
            () -> {
              awaitQuietly(start);
              for (String id : ids) {
                PersistBarrier.markPersisted(context, id);
              }
            });

    awaiter.start();
    marker.start();
    start.countDown();
    awaiter.join();
    marker.join();

    for (TestObserver<Void> observer : observers) {
      observer.assertComplete();
    }
    assertThat(PersistBarrier.pendingCount(context)).isEqualTo(0);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
