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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PersistBarrierTest {

  private final TestScheduler scheduler = new TestScheduler();
  // The stall warning's timer is the only computation work these tests schedule.
  private final AtomicInteger stallTimers = new AtomicInteger();
  private final AtomicInteger stallWarnings = new AtomicInteger();
  private final Scheduler countingScheduler =
      new Scheduler() {
        @Override
        public Worker createWorker() {
          return scheduler.createWorker();
        }

        @Override
        public Disposable scheduleDirect(Runnable run, long delay, TimeUnit unit) {
          stallTimers.incrementAndGet();
          return scheduler.scheduleDirect(
              () -> {
                stallWarnings.incrementAndGet();
                run.run();
              },
              delay,
              unit);
        }
      };
  private Function<? super Scheduler, ? extends Scheduler> previousComputationHandler;
  private InvocationContext context;

  @Before
  public void setUp() {
    previousComputationHandler = RxJavaPlugins.getComputationSchedulerHandler();
    RxJavaPlugins.setComputationSchedulerHandler(unused -> countingScheduler);
    context =
        InvocationContext.builder()
            .sessionService(mock(BaseSessionService.class))
            .invocationId("inv-1")
            .agent(mock(BaseAgent.class))
            .session(Session.builder("s").build())
            // Ends a pending wait on the marking thread, so tests can assert right after a mark.
            .scheduler(Schedulers.trampoline())
            .build();
  }

  @After
  public void tearDown() {
    RxJavaPlugins.setComputationSchedulerHandler(previousComputationHandler);
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
  public void largeStep_awaitsAllWithoutStackOverflow() {
    // A single step's event list can be large (e.g. an agent transfer folds the sub-agent's events
    // into the parent step). Awaiting them must not build a deeply nested chain that overflows the
    // stack when the returned Completable is subscribed or completed.
    PersistBarrier.enable(context);
    int eventCount = 50_000;
    List<Event> events = new ArrayList<>(eventCount);
    for (int i = 0; i < eventCount; i++) {
      events.add(event("e" + i));
    }

    TestObserver<Void> observer = PersistBarrier.awaitPersisted(context, events).test();
    observer.assertNotComplete();

    for (Event event : events) {
      PersistBarrier.markPersisted(context, event.id());
    }

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
  public void stalledAwait_warnsOnceAndKeepsWaiting() {
    PersistBarrier.enable(context);
    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test();

    scheduler.advanceTimeBy(
        PersistBarrier.STALL_WARNING_DELAY.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);

    assertThat(stallWarnings.get()).isEqualTo(1);
    observer.assertNotComplete().assertNoErrors();
    PersistBarrier.markPersisted(context, "e1");
    observer.assertComplete();
  }

  @Test
  public void completedAwait_followedByLongStep_doesNotWarn() {
    // After a transfer, the next agent runs inside the await's onComplete.
    PersistBarrier.enable(context);
    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1")))
            .andThen(
                Completable.fromAction(() ->
                    scheduler.advanceTimeBy(
                        PersistBarrier.STALL_WARNING_DELAY.toMillis(), TimeUnit.MILLISECONDS)))
            .test();

    PersistBarrier.markPersisted(context, "e1");

    observer.assertComplete();
    assertThat(stallWarnings.get()).isEqualTo(0);
  }

  @Test
  public void alreadyPersisted_awaitCompletesOnCallingThreadWithoutStallTimer() {
    // The usual case: flows and workflow agents await events the Runner has already saved.
    // Nothing runs this scheduler, so a hop to it would leave the await pending.
    InvocationContext idleContext = context.toBuilder().scheduler(new TestScheduler()).build();
    PersistBarrier.enable(idleContext);
    PersistBarrier.markPersisted(idleContext, "e1");

    PersistBarrier.awaitPersisted(idleContext, ImmutableList.of(event("e1")))
        .test()
        .assertComplete();

    assertThat(stallTimers.get()).isEqualTo(0);
  }

  @Test
  public void disposedAwait_doesNotWarn() {
    PersistBarrier.enable(context);
    PersistBarrier.awaitPersisted(context, ImmutableList.of(event("e1"))).test().dispose();

    scheduler.advanceTimeBy(PersistBarrier.STALL_WARNING_DELAY.toMillis(), TimeUnit.MILLISECONDS);

    assertThat(stallWarnings.get()).isEqualTo(0);
  }

  @Test
  public void copiedContextData_awaitCompletesWhenOriginalMarks() {
    // A copy of callbackContextData made after enable() must see the Runner's marks.
    PersistBarrier.enable(context);
    InvocationContext copy =
        context.toBuilder()
            .callbackContextData(new ConcurrentHashMap<>(context.callbackContextData()))
            .build();

    TestObserver<Void> observer =
        PersistBarrier.awaitPersisted(copy, ImmutableList.of(event("e1"))).test();
    observer.assertNotComplete();

    PersistBarrier.markPersisted(context, "e1");

    observer.assertComplete();
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
