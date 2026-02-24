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

package com.google.adk.tools.computeruse;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ComputerState}. */
@RunWith(JUnit4.class)
public final class ComputerStateTest {

  @Test
  public void testBuilder() {
    byte[] screenshot = new byte[] {1, 2, 3};
    String url = "https://google.com";
    ComputerState state = ComputerState.builder().screenshot(screenshot).url(url).build();

    assertThat(state.screenshot()).isEqualTo(screenshot);
    assertThat(state.url()).hasValue(url);
  }

  @Test
  public void testBuilder_noUrl() {
    byte[] screenshot = new byte[] {1, 2, 3};
    ComputerState state = ComputerState.builder().screenshot(screenshot).build();

    assertThat(state.screenshot()).isEqualTo(screenshot);
    assertThat(state.url()).isEmpty();
  }

  @Test
  public void testEqualsAndHashCode() {
    byte[] screenshot1 = new byte[] {1, 2, 3};
    byte[] screenshot2 = new byte[] {1, 2, 3};
    byte[] screenshot3 = new byte[] {4, 5, 6};

    ComputerState state1 = ComputerState.builder().screenshot(screenshot1).url("url1").build();
    ComputerState state2 = ComputerState.builder().screenshot(screenshot2).url("url1").build();
    ComputerState state3 = ComputerState.builder().screenshot(screenshot3).url("url1").build();
    ComputerState state4 = ComputerState.builder().screenshot(screenshot1).url("url2").build();

    assertThat(state1).isEqualTo(state2);
    assertThat(state1.hashCode()).isEqualTo(state2.hashCode());

    assertThat(state1).isNotEqualTo(state3);
    assertThat(state1).isNotEqualTo(state4);
  }

  @Test
  public void testScreenshotImmutability() {
    byte[] screenshot = new byte[] {1, 2, 3};
    ComputerState state = ComputerState.builder().screenshot(screenshot).build();

    // Modify original array
    screenshot[0] = 9;
    assertThat(state.screenshot()[0]).isEqualTo(1);

    // Modify returned array
    state.screenshot()[0] = 9;
    assertThat(state.screenshot()[0]).isEqualTo(1);
  }
}
