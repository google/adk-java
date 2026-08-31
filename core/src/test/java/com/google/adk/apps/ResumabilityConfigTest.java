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

package com.google.adk.apps;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ResumabilityConfig}. */
@RunWith(JUnit4.class)
@SuppressWarnings("deprecation") // Exercises the deprecated plainTextContinuationAutoResume shim.
public final class ResumabilityConfigTest {

  @Test
  public void build_defaults_selectNoResumption() {
    ResumabilityConfig config = ResumabilityConfig.builder().build();

    assertThat(config.isResumable()).isFalse();
    assertThat(config.isPlainTextContinuationAutoResume()).isFalse();
  }

  @Test
  public void build_resumableOnly_succeeds() {
    ResumabilityConfig config = ResumabilityConfig.builder().resumable(true).build();

    assertThat(config.isResumable()).isTrue();
    assertThat(config.isPlainTextContinuationAutoResume()).isFalse();
  }

  @Test
  public void build_legacyShimOnly_succeeds() {
    ResumabilityConfig config =
        ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build();

    assertThat(config.isResumable()).isFalse();
    assertThat(config.isPlainTextContinuationAutoResume()).isTrue();
  }

  @Test
  public void build_bothFlags_throws() {
    ResumabilityConfig.Builder builder =
        ResumabilityConfig.builder().resumable(true).plainTextContinuationAutoResume(true);

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(thrown).hasMessageThat().contains("mutually exclusive");
  }
}
