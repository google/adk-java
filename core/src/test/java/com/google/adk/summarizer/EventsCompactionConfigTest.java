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

package com.google.adk.summarizer;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class EventsCompactionConfigTest {

  @Test
  public void builder_buildsConfig() {
    EventsCompactionConfig config =
        EventsCompactionConfig.builder()
            .compactionInterval(10)
            .overlapSize(2)
            .tokenThreshold(100)
            .eventRetentionSize(5)
            .build();

    assertThat(config.compactionInterval()).isEqualTo(10);
    assertThat(config.overlapSize()).isEqualTo(2);
    assertThat(config.tokenThreshold()).isEqualTo(100);
    assertThat(config.eventRetentionSize()).isEqualTo(5);
    assertThat(config.summarizer()).isNull();
  }

  @Test
  public void toBuilder_rebuildsConfig() {
    EventsCompactionConfig config =
        EventsCompactionConfig.builder().compactionInterval(10).overlapSize(2).build();

    EventsCompactionConfig rebuilt = config.toBuilder().compactionInterval(20).build();

    assertThat(rebuilt.compactionInterval()).isEqualTo(20);
    assertThat(rebuilt.overlapSize()).isEqualTo(2);
  }
}
