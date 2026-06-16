// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.example.adk_team.adk_issue_monitoring_agent;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure env-parsing helpers in {@link Settings}. */
final class SettingsTest {

  @Test
  void parseTruthy_recognizesTruthyTokens() {
    assertThat(Settings.parseTruthy("1")).isTrue();
    assertThat(Settings.parseTruthy("true")).isTrue();
    assertThat(Settings.parseTruthy("TRUE")).isTrue();
    assertThat(Settings.parseTruthy("Yes")).isTrue();
    assertThat(Settings.parseTruthy(" on ")).isTrue();
  }

  @Test
  void parseTruthy_rejectsOtherValues() {
    assertThat(Settings.parseTruthy(null)).isFalse();
    assertThat(Settings.parseTruthy("")).isFalse();
    assertThat(Settings.parseTruthy("0")).isFalse();
    assertThat(Settings.parseTruthy("false")).isFalse();
    assertThat(Settings.parseTruthy("nope")).isFalse();
  }

  @Test
  void parsePositiveInt_parsesPositive() {
    assertThat(Settings.parsePositiveInt("5", 3)).isEqualTo(5);
    assertThat(Settings.parsePositiveInt("  7 ", 3)).isEqualTo(7);
  }

  @Test
  void parsePositiveInt_fallsBackOnInvalidOrNonPositive() {
    assertThat(Settings.parsePositiveInt(null, 3)).isEqualTo(3);
    assertThat(Settings.parsePositiveInt("", 3)).isEqualTo(3);
    assertThat(Settings.parsePositiveInt("0", 3)).isEqualTo(3);
    assertThat(Settings.parsePositiveInt("-2", 3)).isEqualTo(3);
    assertThat(Settings.parsePositiveInt("abc", 3)).isEqualTo(3);
  }
}
