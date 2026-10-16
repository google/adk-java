/*
 * Copyright 2026 Google LLC
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

package com.google.adk.plugins.reflectandretry;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the guarantee {@link ToolFailureTracker} makes in place of adk-python's {@code
 * asyncio.Lock}: parallel failures of one tool are each counted exactly once.
 */
@RunWith(JUnit4.class)
public class ToolFailureTrackerTest {

  private static final String SCOPE = "invocation-1";
  private static final String OTHER_SCOPE = "invocation-2";
  private static final String TOOL_NAME = "flaky_tool";
  private static final String OTHER_TOOL_NAME = "other_tool";
  private static final int THREADS = 8;
  private static final int FAILURES_PER_THREAD = 2_000;
  private static final int TOTAL_FAILURES = THREADS * FAILURES_PER_THREAD;
  private static final int TIMEOUT_SECONDS = 30;

  /**
   * Every thread hammers the same tool's counter from a common start. A lost update repeats a count
   * and a dropped one skips it, so the counts handed back across all threads are exactly {@code
   * 1..n} if and only if every update was atomic.
   *
   * <p>Contention has to be <em>sustained</em> to be meaningful: with one call per thread the
   * critical section is far shorter than the spread in thread wake-up, and a plain {@link
   * java.util.HashMap} passes.
   */
  @Test
  public void recordFailure_inParallel_countsEachFailureExactlyOnce() throws Exception {
    ToolFailureTracker tracker = new ToolFailureTracker();
    CyclicBarrier startTogether = new CyclicBarrier(THREADS);
    ExecutorService threads = Executors.newFixedThreadPool(THREADS);

    List<Future<ImmutableList<Integer>>> counts =
        threads.invokeAll(Collections.nCopies(THREADS, recordRepeatedlyAt(tracker, startTogether)));
    threads.shutdown();

    assertThat(threads.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(resolve(counts)).containsExactlyElementsIn(oneTo(TOTAL_FAILURES));
  }

  @Test
  public void recordFailure_countsEachToolSeparately() {
    ToolFailureTracker tracker = new ToolFailureTracker();

    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(1);
    assertThat(tracker.recordFailure(SCOPE, OTHER_TOOL_NAME)).isEqualTo(1);
    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(2);
  }

  @Test
  public void recordFailure_countsEachScopeSeparately() {
    ToolFailureTracker tracker = new ToolFailureTracker();

    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(1);
    assertThat(tracker.recordFailure(OTHER_SCOPE, TOOL_NAME)).isEqualTo(1);
    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(2);
  }

  @Test
  public void reset_clearsOnlyThatScope() {
    ToolFailureTracker tracker = new ToolFailureTracker();
    tracker.recordFailure(SCOPE, TOOL_NAME);
    tracker.recordFailure(OTHER_SCOPE, TOOL_NAME);

    tracker.reset(SCOPE, TOOL_NAME);

    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(1);
    assertThat(tracker.recordFailure(OTHER_SCOPE, TOOL_NAME)).isEqualTo(2);
  }

  @Test
  public void reset_clearsOnlyThatTool() {
    ToolFailureTracker tracker = new ToolFailureTracker();
    tracker.recordFailure(SCOPE, TOOL_NAME);
    tracker.recordFailure(SCOPE, OTHER_TOOL_NAME);

    tracker.reset(SCOPE, TOOL_NAME);

    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(1);
    assertThat(tracker.recordFailure(SCOPE, OTHER_TOOL_NAME)).isEqualTo(2);
  }

  @Test
  public void reset_onUnknownScope_isASilentNoOp() {
    ToolFailureTracker tracker = new ToolFailureTracker();

    tracker.reset("never-seen", TOOL_NAME);

    assertThat(tracker.recordFailure(SCOPE, TOOL_NAME)).isEqualTo(1);
  }

  private static Callable<ImmutableList<Integer>> recordRepeatedlyAt(
      ToolFailureTracker tracker, CyclicBarrier barrier) {
    return () -> recordRepeatedlyAfterBarrier(tracker, barrier);
  }

  private static ImmutableList<Integer> recordRepeatedlyAfterBarrier(
      ToolFailureTracker tracker, CyclicBarrier barrier) throws Exception {
    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return IntStream.range(0, FAILURES_PER_THREAD)
        .mapToObj(unused -> tracker.recordFailure(SCOPE, TOOL_NAME))
        .collect(ImmutableList.toImmutableList());
  }

  private static ImmutableList<Integer> resolve(List<Future<ImmutableList<Integer>>> futures)
      throws Exception {
    ImmutableList.Builder<Integer> counts = ImmutableList.builder();
    for (Future<ImmutableList<Integer>> future : futures) {
      counts.addAll(future.get());
    }
    return counts.build();
  }

  private static ImmutableList<Integer> oneTo(int last) {
    return IntStream.rangeClosed(1, last).boxed().collect(ImmutableList.toImmutableList());
  }
}
