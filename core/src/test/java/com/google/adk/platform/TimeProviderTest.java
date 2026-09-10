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

package com.google.adk.platform;

import static com.google.common.truth.Truth.assertThat;

import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TimeProviderTest {

  @Test
  public void system_returnsWallClockTime() {
    Instant before = Instant.now();
    Instant now = TimeProvider.SYSTEM.now();
    Instant after = Instant.now();

    assertThat(now).isAtLeast(before);
    assertThat(now).isAtMost(after);
  }

  @Test
  public void customProvider_returnsFixedTime() {
    Instant fixed = Instant.ofEpochMilli(1234L);
    TimeProvider provider = () -> fixed;

    assertThat(provider.now()).isEqualTo(fixed);
    assertThat(provider.now()).isEqualTo(fixed);
  }
}
