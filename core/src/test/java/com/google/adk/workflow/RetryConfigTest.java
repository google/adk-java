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

package com.google.adk.workflow;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RetryConfigTest {

  @Test
  public void builder_startsAtTheDefaultsWithExceptionsUnset() {
    RetryConfig config = RetryConfig.builder().build();

    assertThat(config.maxAttempts()).isEqualTo(RetryConfig.DEFAULT_MAX_ATTEMPTS);
    assertThat(config.initialDelay()).isEqualTo(RetryConfig.DEFAULT_INITIAL_DELAY);
    assertThat(config.maxDelay()).isEqualTo(RetryConfig.DEFAULT_MAX_DELAY);
    assertThat(config.backoffFactor()).isEqualTo(RetryConfig.DEFAULT_BACKOFF_FACTOR);
    assertThat(config.jitter()).isEqualTo(RetryConfig.DEFAULT_JITTER);
    assertThat(config.exceptions()).isEmpty();
  }

  @Test
  public void toBuilder_copiesEveryProperty() {
    RetryConfig config =
        RetryConfig.builder()
            .maxAttempts(3)
            .initialDelay(Duration.ofMillis(10))
            .maxDelay(Duration.ofSeconds(2))
            .backoffFactor(1.5)
            .jitter(0.5)
            .exceptions(ImmutableList.of("TimeoutException"))
            .build();

    assertThat(config.toBuilder().build()).isEqualTo(config);
  }

  @Test
  public void exceptions_copiesAMutableList() {
    List<String> exceptions = new ArrayList<>();
    exceptions.add("TimeoutException");

    RetryConfig config = RetryConfig.builder().exceptions(exceptions).build();
    exceptions.add("IOException");

    assertThat(config.exceptions()).hasValue(ImmutableList.of("TimeoutException"));
  }

  @Test
  public void exceptions_emptyListIsDistinctFromUnset() {
    RetryConfig config = RetryConfig.builder().exceptions(ImmutableList.of()).build();

    assertThat(config.exceptions()).hasValue(ImmutableList.of());
  }

  @Test
  public void build_acceptsZeroValues() {
    RetryConfig config =
        RetryConfig.builder()
            .maxAttempts(0)
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .backoffFactor(0.0)
            .jitter(0.0)
            .build();

    assertThat(config.maxAttempts()).isEqualTo(0);
  }

  @Test
  public void build_rejectsNegativeMaxAttempts() {
    RetryConfig.Builder builder = RetryConfig.builder().maxAttempts(-1);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("maxAttempts");
  }

  @Test
  public void build_rejectsNegativeInitialDelay() {
    RetryConfig.Builder builder = RetryConfig.builder().initialDelay(Duration.ofMillis(-1));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("initialDelay");
  }

  @Test
  public void build_rejectsNegativeMaxDelay() {
    RetryConfig.Builder builder = RetryConfig.builder().maxDelay(Duration.ofMillis(-1));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("maxDelay");
  }

  @Test
  public void build_rejectsNegativeBackoffFactor() {
    RetryConfig.Builder builder = RetryConfig.builder().backoffFactor(-0.5);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("backoffFactor");
  }

  @Test
  public void build_rejectsInfiniteBackoffFactor() {
    RetryConfig.Builder builder = RetryConfig.builder().backoffFactor(Double.POSITIVE_INFINITY);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("backoffFactor");
  }

  @Test
  public void build_rejectsNaNJitter() {
    RetryConfig.Builder builder = RetryConfig.builder().jitter(Double.NaN);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("jitter");
  }

  @Test
  public void shouldRetry_withAttemptsLeftAndExceptionsUnset_retriesAnyFailure() {
    RetryConfig config = RetryConfig.builder().maxAttempts(3).build();

    assertThat(config.shouldRetry(new IllegalStateException(), /* attempt= */ 1)).isTrue();
    assertThat(config.shouldRetry(new IllegalArgumentException(), /* attempt= */ 2)).isTrue();
  }

  @Test
  public void shouldRetry_withNoAttemptLeft_stops() {
    Exception failure = new IllegalStateException();

    assertThat(withMaxAttempts(3).shouldRetry(failure, /* attempt= */ 3)).isFalse();
    assertThat(withMaxAttempts(1).shouldRetry(failure, /* attempt= */ 1)).isFalse();
    assertThat(withMaxAttempts(0).shouldRetry(failure, /* attempt= */ 1)).isFalse();
  }

  @Test
  public void shouldRetry_matchesTheSimpleTypeNameWithoutSubclasses() {
    RetryConfig config =
        RetryConfig.builder().exceptions(ImmutableList.of("RuntimeException")).build();

    assertThat(config.shouldRetry(new RuntimeException(), /* attempt= */ 1)).isTrue();
    assertThat(config.shouldRetry(new IllegalStateException(), /* attempt= */ 1)).isFalse();
  }

  @Test
  public void shouldRetry_withAnEmptyExceptionsList_retriesNothing() {
    RetryConfig config = RetryConfig.builder().exceptions(ImmutableList.of()).build();

    assertThat(config.shouldRetry(new IllegalStateException(), /* attempt= */ 1)).isFalse();
  }

  @Test
  public void delayFor_withoutJitter_growsByTheBackoffFactorUntilMaxDelay() {
    RetryConfig config =
        RetryConfig.builder()
            .initialDelay(Duration.ofSeconds(1))
            .maxDelay(Duration.ofSeconds(60))
            .backoffFactor(2)
            .jitter(0)
            .build();
    Random random = new Random(0);

    assertThat(config.delayFor(/* attempt= */ 1, random)).isEqualTo(Duration.ofSeconds(1));
    assertThat(config.delayFor(/* attempt= */ 2, random)).isEqualTo(Duration.ofSeconds(2));
    assertThat(config.delayFor(/* attempt= */ 3, random)).isEqualTo(Duration.ofSeconds(4));
    assertThat(config.delayFor(/* attempt= */ 10, random)).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  public void delayFor_withJitter_spreadsTheDelayAroundItsBase() {
    RetryConfig config =
        RetryConfig.builder().initialDelay(Duration.ofSeconds(1)).jitter(0.5).build();
    Random random = new Random(0);

    ImmutableList<Duration> delays =
        Stream.generate(() -> config.delayFor(/* attempt= */ 1, random))
            .limit(50)
            .collect(toImmutableList());

    assertThat(Collections.min(delays)).isAtLeast(Duration.ofMillis(500));
    assertThat(Collections.max(delays)).isAtMost(Duration.ofMillis(1500));
    assertThat(ImmutableSet.copyOf(delays).size()).isGreaterThan(1);
  }

  @Test
  public void delayFor_withJitterWhenTheBackoffOverflows_staysWithinMaxDelay() {
    RetryConfig config =
        RetryConfig.builder()
            .initialDelay(Duration.ofSeconds(1))
            .maxDelay(Duration.ofSeconds(3))
            .backoffFactor(2)
            .jitter(0.5)
            .build();
    Random random = new Random(0);

    ImmutableList<Duration> delays =
        Stream.generate(() -> config.delayFor(/* attempt= */ 5000, random))
            .limit(50)
            .collect(toImmutableList());

    // The delay is capped at maxDelay / (1 + jitter) = 2s before jitter spreads it.
    assertThat(Collections.min(delays)).isAtLeast(Duration.ofSeconds(1));
    assertThat(Collections.max(delays)).isAtMost(Duration.ofSeconds(3));
  }

  @Test
  public void delayFor_withAZeroInitialDelay_staysZeroWhenTheBackoffOverflows() {
    RetryConfig config =
        RetryConfig.builder().initialDelay(Duration.ZERO).backoffFactor(10).build();

    assertThat(config.delayFor(/* attempt= */ 5000, new Random(0))).isEqualTo(Duration.ZERO);
  }

  @Test
  public void delayFor_beyondTheNanosecondsALongHolds_saturates() {
    Duration forever = Duration.ofSeconds(Long.MAX_VALUE);
    RetryConfig config =
        RetryConfig.builder().initialDelay(forever).maxDelay(forever).jitter(0).build();

    assertThat(config.delayFor(/* attempt= */ 1, new Random(0)))
        .isEqualTo(Duration.ofNanos(Long.MAX_VALUE));
  }

  private static RetryConfig withMaxAttempts(int maxAttempts) {
    return RetryConfig.builder().maxAttempts(maxAttempts).build();
  }
}
