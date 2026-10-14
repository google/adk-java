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

import com.google.adk.agents.Context;
import io.reactivex.rxjava3.core.Flowable;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeTest {

  @Test
  public void defaultMethods_returnDefaults() {
    Node node =
        new Node() {
          @Override
          public String name() {
            return "bare";
          }

          @Override
          public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
            return Flowable.empty();
          }
        };

    assertThat(node.description()).isEmpty();
    assertThat(node.rerunOnResume()).isFalse();
    assertThat(node.waitForOutput()).isFalse();
    assertThat(node.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(node.inputSchema()).isEmpty();
    assertThat(node.outputSchema()).isEmpty();
    assertThat(node.stateSchema()).isEmpty();
    assertThat(node.requiresAllPredecessors()).isFalse();
  }
}
