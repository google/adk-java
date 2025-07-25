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

package com.google.adk;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class VersionTest {

  @Test
  public void versionShouldMatchProjectVersion() {
    assertThat(Version.JAVA_ADK_VERSION).isNotNull();
    assertThat(Version.JAVA_ADK_VERSION).isNotEmpty();
    assertThat(Version.JAVA_ADK_VERSION).isNotEqualTo("unknown");
    assertThat(Version.JAVA_ADK_VERSION).isNotEqualTo("${project.version}");

    assertThat(Version.JAVA_ADK_VERSION).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
  }
}
