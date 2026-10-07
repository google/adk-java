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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GetSessionConfigTest {

  @Test
  public void build_negativeNumRecentEvents_throwsIllegalArgumentException() {
    GetSessionConfig.Builder builder = GetSessionConfig.builder().numRecentEvents(-1);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_zeroNumRecentEvents_isAccepted() {
    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(0).build();

    assertThat(config.numRecentEvents()).hasValue(0);
  }
}
