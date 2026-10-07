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
package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.genai.types.HttpOptions;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ContextCacheConfigTest {

  private static final Duration TTL = Duration.ofMinutes(30);

  @Test
  public void defaultConstructor_usesDefaults() {
    ContextCacheConfig config = new ContextCacheConfig();

    assertThat(config.cacheIntervals()).isEqualTo(10);
    assertThat(config.ttl()).isEqualTo(Duration.ofMinutes(30));
    assertThat(config.minTokens()).isEqualTo(0);
    assertThat(config.createHttpOptions()).isNull();
  }

  @Test
  public void threeArgConstructor_leavesHttpOptionsUnset() {
    ContextCacheConfig config = new ContextCacheConfig(5, TTL, 100);

    assertThat(config.cacheIntervals()).isEqualTo(5);
    assertThat(config.createHttpOptions()).isNull();
  }

  @Test
  public void constructor_keepsHttpOptions() {
    HttpOptions httpOptions = HttpOptions.builder().timeout(10_000).build();

    assertThat(new ContextCacheConfig(5, TTL, 100, httpOptions).createHttpOptions())
        .isEqualTo(httpOptions);
  }

  @Test
  @SuppressWarnings({"deprecation", "InlineMeInliner"}) // Covers the deprecated alias.
  public void maxInvocations_returnsCacheIntervals() {
    assertThat(new ContextCacheConfig(7, TTL, 0).maxInvocations()).isEqualTo(7);
  }

  @Test
  public void constructor_cacheIntervalsAtBounds_isAccepted() {
    assertThat(new ContextCacheConfig(1, TTL, 0).cacheIntervals()).isEqualTo(1);
    assertThat(new ContextCacheConfig(100, TTL, 0).cacheIntervals()).isEqualTo(100);
  }

  @Test
  public void constructor_cacheIntervalsBelowOne_throws() {
    assertThrows(IllegalArgumentException.class, () -> new ContextCacheConfig(0, TTL, 0));
  }

  @Test
  public void constructor_cacheIntervalsAboveHundred_throws() {
    assertThrows(IllegalArgumentException.class, () -> new ContextCacheConfig(101, TTL, 0));
  }

  @Test
  public void constructor_nullTtl_throws() {
    assertThrows(NullPointerException.class, () -> new ContextCacheConfig(10, null, 0));
  }

  @Test
  public void constructor_zeroTtl_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new ContextCacheConfig(10, Duration.ZERO, 0));
  }

  @Test
  public void constructor_negativeTtl_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextCacheConfig(10, Duration.ofSeconds(-1), 0));
  }

  @Test
  public void constructor_oneSecondTtl_isAccepted() {
    assertThat(new ContextCacheConfig(10, Duration.ofSeconds(1), 0).ttl())
        .isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  public void constructor_ttlUnderOneSecond_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextCacheConfig(10, Duration.ofMillis(500), 0));
  }

  @Test
  public void constructor_fractionalTtl_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ContextCacheConfig(10, Duration.ofMillis(1500), 0));
  }

  @Test
  public void constructor_negativeMinTokens_throws() {
    assertThrows(IllegalArgumentException.class, () -> new ContextCacheConfig(10, TTL, -1));
  }

  @Test
  public void getTtlString_returnsSeconds() {
    assertThat(new ContextCacheConfig(10, Duration.ofMinutes(5), 0).getTtlString())
        .isEqualTo("300s");
  }

  @Test
  public void toString_withoutHttpOptions_saysNull() {
    assertThat(new ContextCacheConfig().toString())
        .isEqualTo(
            "ContextCacheConfig(cacheIntervals=10, ttl=1800s, minTokens=0,"
                + " createHttpOptions=null)");
  }

  @Test
  public void toString_hidesHttpOptionValues() {
    HttpOptions httpOptions =
        HttpOptions.builder().headers(ImmutableMap.of("x-goog-api-key", "secret")).build();

    assertThat(new ContextCacheConfig(5, TTL, 100, httpOptions).toString())
        .isEqualTo(
            "ContextCacheConfig(cacheIntervals=5, ttl=1800s, minTokens=100,"
                + " createHttpOptions=set)");
  }
}
