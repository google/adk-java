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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ToolConfirmationTest {

  @Test
  public void builder_setsDefaultValues() {
    ToolConfirmation toolConfirmation = ToolConfirmation.builder().build();

    assertThat(toolConfirmation.hint()).isEmpty();
    assertThat(toolConfirmation.confirmed()).isFalse();
    assertThat(toolConfirmation.payload()).isNull();
  }

  @Test
  public void builder_setsValues() {
    ToolConfirmation toolConfirmation =
        ToolConfirmation.builder().hint("hint").confirmed(true).payload("payload").build();

    assertThat(toolConfirmation.hint()).isEqualTo("hint");
    assertThat(toolConfirmation.confirmed()).isTrue();
    assertThat(toolConfirmation.payload()).isEqualTo("payload");
  }

  @Test
  public void toBuilder_createsBuilderWithSameValues() {
    ToolConfirmation toolConfirmation =
        ToolConfirmation.builder().hint("hint").confirmed(true).payload("payload").build();
    ToolConfirmation copiedToolConfirmation = toolConfirmation.toBuilder().build();

    assertThat(copiedToolConfirmation).isEqualTo(toolConfirmation);
  }
}
