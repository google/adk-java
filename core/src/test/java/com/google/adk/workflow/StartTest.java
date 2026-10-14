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

import com.google.adk.agents.CallbackContext;
import com.google.adk.testing.TestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StartTest {

  @Test
  public void name_isTheReservedStartName() {
    assertThat(Start.INSTANCE.name()).isEqualTo("__START__");
  }

  @Test
  public void runNode_emitsNothing() {
    CallbackContext context =
        new CallbackContext(
            TestUtils.createInvocationContext(TestUtils.createRootAgent()),
            /* eventActions= */ null);

    Start.INSTANCE.runNode(context, "input").test().assertComplete().assertNoValues();
  }
}
