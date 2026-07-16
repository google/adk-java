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

package com.google.adk.models.sarvamai;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/**
 * @author Sandeep Belgavi
 */
class SarvamRetryInterceptorTest {

  @Test
  void calculateDelay_exponentiallyIncreases() {
    long delay0 = SarvamRetryInterceptor.calculateDelay(0);
    long delay1 = SarvamRetryInterceptor.calculateDelay(1);
    long delay2 = SarvamRetryInterceptor.calculateDelay(2);

    assertThat(delay0).isAtLeast(500);
    assertThat(delay0).isAtMost(700);

    assertThat(delay1).isAtLeast(1000);
    assertThat(delay1).isAtMost(1400);

    assertThat(delay2).isAtLeast(2000);
    assertThat(delay2).isAtMost(2800);
  }

  @Test
  void calculateDelay_respectsMaxCap() {
    long delay10 = SarvamRetryInterceptor.calculateDelay(10);
    assertThat(delay10).isAtMost(36_000);
  }
}
