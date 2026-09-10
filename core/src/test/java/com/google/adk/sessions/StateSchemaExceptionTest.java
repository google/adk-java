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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * The supertype is the whole contract of {@link StateSchemaException}: a caller that already
 * handles session failures must catch a schema rejection without naming the type.
 */
@RunWith(JUnit4.class)
public final class StateSchemaExceptionTest {

  @Test
  public void isCaughtAsSessionException() {
    Throwable caught = null;
    try {
      throw new StateSchemaException("key 'total' is not declared in the state schema");
    } catch (SessionException e) {
      caught = e;
    }

    assertThat(caught).isInstanceOf(StateSchemaException.class);
    assertThat(caught).hasMessageThat().contains("not declared in the state schema");
  }

  @Test
  public void keepsTheCause() {
    Exception cause = new IllegalArgumentException("bad value");

    StateSchemaException e = new StateSchemaException("rejected", cause);

    assertThat(e).hasCauseThat().isSameInstanceAs(cause);
  }
}
