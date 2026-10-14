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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeConfigTest {

  @Test
  public void builder_defaultsToNoRetryAndNoTimeout() {
    NodeConfig config = NodeConfig.builder().build();

    assertThat(config.retryConfig()).isEmpty();
    assertThat(config.timeout()).isEmpty();
  }

  @Test
  public void toBuilder_copiesEveryProperty() {
    NodeConfig config =
        NodeConfig.builder()
            .retryConfig(RetryConfig.builder().maxAttempts(3).build())
            .timeout(Duration.ofMillis(1500))
            .build();

    assertThat(config.toBuilder().build()).isEqualTo(config);
  }

  @Test
  public void timeout_nullClearsIt() {
    NodeConfig config = NodeConfig.builder().timeout(Duration.ofSeconds(1)).build();

    assertThat(config.toBuilder().timeout(null).build().timeout()).isEmpty();
  }

  @Test
  public void build_rejectsAZeroTimeout() {
    NodeConfig.Builder builder = NodeConfig.builder().timeout(Duration.ZERO);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("timeout must be positive");
  }

  @Test
  public void build_rejectsANegativeTimeout() {
    NodeConfig.Builder builder = NodeConfig.builder().timeout(Duration.ofSeconds(-1));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("timeout must be positive");
  }
}
