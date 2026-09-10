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
package com.google.adk.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AdkConfigurationTest {

  private static final String KEY = "ADK_CONFIG_TEST_KEY";

  @BeforeEach
  void setUp() {
    AdkConfiguration.clearAll();
    System.clearProperty(KEY);
  }

  @AfterEach
  void tearDown() {
    AdkConfiguration.clearAll();
    System.clearProperty(KEY);
  }

  @Test
  void get_returnsEmpty_whenNoSourceProvidesValue() {
    assertThat(AdkConfiguration.get(KEY)).isEmpty();
  }

  @Test
  void get_returnsSystemProperty_whenSet() {
    System.setProperty(KEY, "from-sysprop");
    assertThat(AdkConfiguration.get(KEY)).hasValue("from-sysprop");
  }

  @Test
  void set_takesPrecedenceOverSystemProperty() {
    System.setProperty(KEY, "from-sysprop");
    AdkConfiguration.set(KEY, "from-programmatic");
    assertThat(AdkConfiguration.get(KEY)).hasValue("from-programmatic");
  }

  @Test
  void clear_removesProgrammaticOverride_andFallsBackToSystemProperty() {
    System.setProperty(KEY, "from-sysprop");
    AdkConfiguration.set(KEY, "from-programmatic");
    AdkConfiguration.clear(KEY);
    assertThat(AdkConfiguration.get(KEY)).hasValue("from-sysprop");
  }

  @Test
  void getOrDefault_returnsDefault_whenUnset() {
    assertThat(AdkConfiguration.getOrDefault(KEY, "fallback")).isEqualTo("fallback");
  }

  @Test
  void getOrDefault_returnsResolvedValue_whenSet() {
    AdkConfiguration.set(KEY, "resolved");
    assertThat(AdkConfiguration.getOrDefault(KEY, "fallback")).isEqualTo("resolved");
  }

  @Test
  void set_throws_onNullKeyOrValue() {
    assertThrows(IllegalArgumentException.class, () -> AdkConfiguration.set(null, "v"));
    assertThrows(IllegalArgumentException.class, () -> AdkConfiguration.set(KEY, null));
  }

  @Test
  void get_returnsEmpty_forNullKey() {
    assertThat(AdkConfiguration.get(null)).isEmpty();
  }
}
