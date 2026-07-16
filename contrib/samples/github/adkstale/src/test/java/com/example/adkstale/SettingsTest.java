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
package com.example.adkstale;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for the pure helpers in {@link Settings}. */
final class SettingsTest {

  @ParameterizedTest
  @ValueSource(strings = {"1", "true", "TRUE", "True", "yes", "on", "ON"})
  void parseTruthy_recognizesTruthyTokens(String value) {
    assertThat(Settings.parseTruthy(value)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "false", "no", "off", "", "maybe", "2"})
  void parseTruthy_rejectsNonTruthyTokens(String value) {
    assertThat(Settings.parseTruthy(value)).isFalse();
  }

  @Test
  void parseTruthy_nullIsFalse() {
    assertThat(Settings.parseTruthy(null)).isFalse();
  }

  @Test
  void parseNumberString_validNumber() {
    assertThat(Settings.parseNumberString("5", 0)).isEqualTo(5);
  }

  @Test
  void parseNumberString_trimsWhitespace() {
    assertThat(Settings.parseNumberString("  7  ", 0)).isEqualTo(7);
  }

  @Test
  void parseNumberString_nullUsesDefault() {
    assertThat(Settings.parseNumberString(null, 3)).isEqualTo(3);
  }

  @Test
  void parseNumberString_blankUsesDefault() {
    assertThat(Settings.parseNumberString("   ", 3)).isEqualTo(3);
  }

  @Test
  void parseNumberString_invalidUsesDefault() {
    assertThat(Settings.parseNumberString("not-a-number", 9)).isEqualTo(9);
  }

  @Test
  void parseDouble_validNumber() {
    assertThat(Settings.parseDouble("168", 0.0)).isEqualTo(168.0);
  }

  @Test
  void parseDouble_fractional() {
    assertThat(Settings.parseDouble("0.5", 0.0)).isEqualTo(0.5);
  }

  @Test
  void parseDouble_nullOrBlankOrInvalidUsesDefault() {
    assertThat(Settings.parseDouble(null, 168.0)).isEqualTo(168.0);
    assertThat(Settings.parseDouble("  ", 168.0)).isEqualTo(168.0);
    assertThat(Settings.parseDouble("nope", 7.0)).isEqualTo(7.0);
  }

  @Test
  void defaults_matchPythonSample() {
    // None of these env vars are set in the unit-test environment, so the accessors return the
    // documented defaults.
    assertThat(Settings.owner()).isEqualTo("google");
    assertThat(Settings.repo()).isEqualTo("adk-java");
    assertThat(Settings.staleLabel()).isEqualTo("stale");
    assertThat(Settings.requestClarificationLabel()).isEqualTo("request clarification");
    assertThat(Settings.staleHoursThreshold()).isEqualTo(168.0);
    assertThat(Settings.closeHoursAfterStaleThreshold()).isEqualTo(168.0);
    assertThat(Settings.isInteractive()).isTrue();
    assertThat(Settings.isDryRun()).isFalse();
  }
}
