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

package com.google.adk.plugins.agentanalytics;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BigQueryLoggerConfigTest {

  private static BigQueryLoggerConfig.Builder validBuilder() {
    return BigQueryLoggerConfig.builder().projectId("test-project").datasetId("test-dataset");
  }

  @Test
  public void build_validConfig_succeeds() {
    BigQueryLoggerConfig config = validBuilder().build();
    assertEquals("test-project", config.projectId());
    assertEquals(1, config.batchSize());
    assertEquals(10000, config.queueMaxSize());
  }

  @Test
  public void build_defaults_matchCrossLanguageContract() {
    BigQueryLoggerConfig config = validBuilder().build();
    assertThat(config.tableName()).isEqualTo("agent_events");
  }

  @Test
  public void build_missingDatasetId_throws() {
    BigQueryLoggerConfig.Builder builder = BigQueryLoggerConfig.builder().projectId("test-project");
    assertThrows(IllegalStateException.class, () -> builder.build());
  }

  @Test
  public void build_nonPositiveBatchSize_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().batchSize(0);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_nonPositiveQueueMaxSize_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().queueMaxSize(0);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_nonPositiveMaxContentLength_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().maxContentLength(-1);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_zeroBatchFlushInterval_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().batchFlushInterval(Duration.ZERO);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_subMillisecondBatchFlushInterval_throws() {
    BigQueryLoggerConfig.Builder builder =
        validBuilder().batchFlushInterval(Duration.ofNanos(500_000));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_negativeShutdownTimeout_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().shutdownTimeout(Duration.ofSeconds(-1));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_overflowingBatchFlushInterval_throwsIllegalArgumentException() {
    BigQueryLoggerConfig.Builder builder =
        validBuilder().batchFlushInterval(Duration.ofSeconds(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_overflowingShutdownTimeout_throwsIllegalArgumentException() {
    BigQueryLoggerConfig.Builder builder =
        validBuilder().shutdownTimeout(Duration.ofSeconds(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_negativeMaxRetries_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder().maxRetries(-1);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_negativeInitialDelay_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder().initialDelay(Duration.ofMillis(-1));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_subMillisecondInitialDelay_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder().initialDelay(Duration.ofNanos(500_000));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_negativeMaxDelay_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder()
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ofMillis(-1));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_subMillisecondMaxDelay_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder()
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ofNanos(500_000));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_overflowingDelay_throwsIllegalArgumentException() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder()
            .initialDelay(Duration.ofSeconds(Long.MAX_VALUE))
            .maxDelay(Duration.ofSeconds(Long.MAX_VALUE));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_maxDelayBelowInitialDelay_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder()
            .initialDelay(Duration.ofSeconds(2))
            .maxDelay(Duration.ofSeconds(1));
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_multiplierBelowOne_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder().multiplier(0.5);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_nanMultiplier_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder().multiplier(Double.NaN);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_infiniteMultiplier_throws() {
    BigQueryLoggerConfig.RetryConfig.Builder builder =
        BigQueryLoggerConfig.RetryConfig.builder().multiplier(Double.POSITIVE_INFINITY);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void retryConfigBuild_zeroDelaysAndUnitMultiplier_succeeds() {
    BigQueryLoggerConfig.RetryConfig retry =
        BigQueryLoggerConfig.RetryConfig.builder()
            .initialDelay(Duration.ZERO)
            .maxDelay(Duration.ZERO)
            .multiplier(1.0)
            .build();
    assertThat(retry.initialDelay()).isEqualTo(Duration.ZERO);
    assertThat(retry.maxDelay()).isEqualTo(Duration.ZERO);
    assertThat(retry.multiplier()).isEqualTo(1.0);
  }
}
