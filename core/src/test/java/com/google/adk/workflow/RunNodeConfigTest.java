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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RunNodeConfigTest {

  @Test
  public void builder_defaultsEveryProperty() {
    RunNodeConfig config = RunNodeConfig.builder().build();

    assertThat(config.runId()).isEmpty();
    assertThat(config.useAsOutput()).isFalse();
    assertThat(config.useSubBranch()).isFalse();
    assertThat(config.overrideBranch()).isEmpty();
    assertThat(config.raiseOnWait()).isFalse();
  }

  @Test
  public void builder_setsEveryProperty() {
    RunNodeConfig config =
        RunNodeConfig.builder()
            .runId("lookup")
            .useAsOutput(true)
            .useSubBranch(true)
            .overrideBranch("tool.call@1")
            .raiseOnWait(true)
            .build();

    assertThat(config.runId()).hasValue("lookup");
    assertThat(config.useAsOutput()).isTrue();
    assertThat(config.useSubBranch()).isTrue();
    assertThat(config.overrideBranch()).hasValue("tool.call@1");
    assertThat(config.raiseOnWait()).isTrue();
  }

  @Test
  public void toBuilder_copiesEveryProperty() {
    RunNodeConfig config =
        RunNodeConfig.builder()
            .runId("lookup")
            .useAsOutput(true)
            .useSubBranch(true)
            .overrideBranch("tool.call@1")
            .raiseOnWait(true)
            .build();

    assertThat(config.toBuilder().build()).isEqualTo(config);
  }

  @Test
  public void runId_nullClearsIt() {
    RunNodeConfig config = RunNodeConfig.builder().runId("lookup").build();

    assertThat(config.toBuilder().runId(null).build().runId()).isEmpty();
  }

  @Test
  public void build_treatsAnEmptyRunIdAsNone() {
    RunNodeConfig config = RunNodeConfig.builder().runId("").build();

    assertThat(config.runId()).isEmpty();
  }

  @Test
  public void build_rejectsAnAllDigitRunId() {
    RunNodeConfig.Builder builder = RunNodeConfig.builder().runId("12");

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(thrown).hasMessageThat().contains("runId \"12\" must contain a non-digit character");
  }

  @Test
  public void build_rejectsARunIdWithASlash() {
    assertThat(buildFailure("a/b")).contains("runId \"a/b\" must not contain '/', '@', or '.'");
  }

  @Test
  public void build_rejectsARunIdWithAnAtSign() {
    assertThat(buildFailure("a@b")).contains("runId \"a@b\" must not contain '/', '@', or '.'");
  }

  @Test
  public void build_rejectsARunIdWithADot() {
    assertThat(buildFailure("a.b")).contains("runId \"a.b\" must not contain '/', '@', or '.'");
  }

  /** Returns the message of the exception that building a config with {@code runId} throws. */
  private static String buildFailure(String runId) {
    RunNodeConfig.Builder builder = RunNodeConfig.builder().runId(runId);
    return assertThrows(IllegalArgumentException.class, builder::build).getMessage();
  }
}
