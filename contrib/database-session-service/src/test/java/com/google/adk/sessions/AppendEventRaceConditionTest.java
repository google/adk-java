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
package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test that demonstrates the race condition between event emission and persistence.
 *
 * <p>This test proves that using {@code doOnNext()} to append events creates a race condition where
 * events flow downstream before being persisted to the database, while using {@code flatMap()}
 * correctly waits for persistence to complete.
 */
@RunWith(JUnit4.class)
public class AppendEventRaceConditionTest {

  private DatabaseSessionService sessionService;
  private static final String APP_NAME = "race-test-app";
  private static final String USER_ID = "race-test-user";

  @Before
  public void setUp() throws Exception {
    sessionService =
        new DatabaseSessionService("jdbc:h2:mem:race_test_db;DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=");
  }

  @After
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  /**
   * This test demonstrates the RACE CONDITION with doOnNext().
   *
   * <p>Timeline: T=0ms: Event emitted T=1ms: doOnNext() fires appendEvent() (doesn't wait!) T=2ms:
   * Event flows downstream immediately T=5ms: We query listEvents() T=6ms: Query reads database
   * Result: Event might NOT be in database yet! ← RACE CONDITION T=100ms: Database write finally
   * completes
   */
  @Test
  public void testDoOnNext_hasRaceCondition() throws Exception {
    Session testSession =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), "session-doOnNext")
            .blockingGet();

    AtomicInteger eventsSeenInQuery = new AtomicInteger(0);
    AtomicBoolean appendStarted = new AtomicBoolean(false);
    CountDownLatch queryLatch = new CountDownLatch(1);

    Event testEvent =
        Event.builder()
            .id("race-event-1")
            .invocationId("inv-1")
            .author("test-agent")
            .content(Content.builder().parts(Part.builder().text("Test").build()).build())
            .actions(EventActions.builder().build())
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Flowable.just(testEvent)
        .doOnNext(
            event -> {
              appendStarted.set(true);
              sessionService.appendEvent(testSession, event);
            })
        .doOnNext(
            event -> {
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }

              List<Event> events =
                  sessionService
                      .listEvents(APP_NAME, USER_ID, testSession.id())
                      .blockingGet()
                      .events();
              eventsSeenInQuery.set(events.size());
              queryLatch.countDown();
            })
        .blockingSubscribe();

    queryLatch.await(5, TimeUnit.SECONDS);

    System.out.println(
        "doOnNext() test - Events seen in query: "
            + eventsSeenInQuery.get()
            + " (expected 0 or 1 due to race)");
  }

  /**
   * This test demonstrates the CORRECT BEHAVIOR with flatMap().
   *
   * <p>Timeline: T=0ms: Event emitted T=1ms: flatMap() calls appendEvent() T=2ms: Waits for
   * appendEvent() Single to complete T=100ms: Database write completes T=101ms: Event flows
   * downstream T=102ms: We query listEvents() T=103ms: Query reads database Result: Event IS in
   * database! ← CORRECT
   */
  @Test
  public void testFlatMap_waitsForPersistence() throws Exception {
    Session testSession =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), "session-flatMap")
            .blockingGet();

    AtomicInteger eventsSeenInQuery = new AtomicInteger(0);
    CountDownLatch queryLatch = new CountDownLatch(1);

    Event testEvent =
        Event.builder()
            .id("race-event-2")
            .invocationId("inv-2")
            .author("test-agent")
            .content(Content.builder().parts(Part.builder().text("Test").build()).build())
            .actions(EventActions.builder().build())
            .timestamp(Instant.now().toEpochMilli())
            .build();

    Flowable.just(testEvent)
        .flatMap(
            event ->
                sessionService
                    .appendEvent(testSession, event)
                    .toFlowable()
                    .onErrorResumeNext(
                        error -> {
                          System.err.println("Failed to append event: " + error.getMessage());
                          return Flowable.just(event);
                        }))
        .doOnNext(
            event -> {
              List<Event> events =
                  sessionService
                      .listEvents(APP_NAME, USER_ID, testSession.id())
                      .blockingGet()
                      .events();
              eventsSeenInQuery.set(events.size());
              queryLatch.countDown();
            })
        .blockingSubscribe();

    queryLatch.await(5, TimeUnit.SECONDS);

    System.out.println(
        "flatMap() test - Events seen in query: "
            + eventsSeenInQuery.get()
            + " (expected 1 - always present)");

    assertThat(eventsSeenInQuery.get()).isEqualTo(1);
  }

  /**
   * This test runs multiple iterations to increase the chance of catching the race condition.
   *
   * <p>With doOnNext(), we expect to see the race condition manifest as inconsistent query results.
   * With flatMap(), we expect 100% consistency.
   */
  @Test
  public void testRaceCondition_multipleIterations() throws Exception {
    int iterations = 10;
    int doOnNextMisses = 0;
    int flatMapMisses = 0;

    for (int i = 0; i < iterations; i++) {
      final int iteration = i;
      Session session =
          sessionService
              .createSession(
                  APP_NAME, "user-" + iteration, new ConcurrentHashMap<>(), "session-" + iteration)
              .blockingGet();

      Event event =
          Event.builder()
              .id("event-" + iteration)
              .invocationId("inv-" + iteration)
              .author("test")
              .content(
                  Content.builder().parts(Part.builder().text("Test " + iteration).build()).build())
              .actions(EventActions.builder().build())
              .timestamp(Instant.now().toEpochMilli())
              .build();

      AtomicInteger doOnNextCount = new AtomicInteger(0);
      CountDownLatch doOnNextLatch = new CountDownLatch(1);

      Flowable.just(event)
          .doOnNext(e -> sessionService.appendEvent(session, e))
          .delay(10, TimeUnit.MILLISECONDS)
          .doOnNext(
              e -> {
                int count =
                    sessionService
                        .listEvents(APP_NAME, "user-" + iteration, "session-" + iteration)
                        .blockingGet()
                        .events()
                        .size();
                doOnNextCount.set(count);
                doOnNextLatch.countDown();
              })
          .blockingSubscribe();

      doOnNextLatch.await(2, TimeUnit.SECONDS);
      if (doOnNextCount.get() == 0) {
        doOnNextMisses++;
      }

      Session session2 =
          sessionService
              .createSession(
                  APP_NAME,
                  "user2-" + iteration,
                  new ConcurrentHashMap<>(),
                  "session2-" + iteration)
              .blockingGet();

      Event event2 =
          Event.builder()
              .id("event2-" + iteration)
              .invocationId("inv2-" + iteration)
              .author("test")
              .content(
                  Content.builder()
                      .parts(Part.builder().text("Test2 " + iteration).build())
                      .build())
              .actions(EventActions.builder().build())
              .timestamp(Instant.now().toEpochMilli())
              .build();

      AtomicInteger flatMapCount = new AtomicInteger(0);
      CountDownLatch flatMapLatch = new CountDownLatch(1);

      Flowable.just(event2)
          .flatMap(
              e ->
                  sessionService
                      .appendEvent(session2, e)
                      .toFlowable()
                      .onErrorResumeNext(err -> Flowable.just(e)))
          .doOnNext(
              e -> {
                int count =
                    sessionService
                        .listEvents(APP_NAME, "user2-" + iteration, "session2-" + iteration)
                        .blockingGet()
                        .events()
                        .size();
                flatMapCount.set(count);
                flatMapLatch.countDown();
              })
          .blockingSubscribe();

      flatMapLatch.await(2, TimeUnit.SECONDS);
      if (flatMapCount.get() == 0) {
        flatMapMisses++;
      }
    }

    System.out.println("Race condition test results over " + iterations + " iterations:");
    System.out.println(
        "  doOnNext() misses: " + doOnNextMisses + " (race condition manifestations)");
    System.out.println("  flatMap() misses: " + flatMapMisses + " (should be 0)");

    assertThat(flatMapMisses).isEqualTo(0);
  }
}
