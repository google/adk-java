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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.workflow.Node;
import io.reactivex.rxjava3.core.Flowable;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeViewAgentTest {

  private static final Node NODE =
      new Node() {
        @Override
        public String name() {
          return "solo";
        }

        @Override
        public String description() {
          return "A test node.";
        }

        @Override
        public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
          return Flowable.empty();
        }
      };

  @Test
  public void constructor_takesTheNameAndDescriptionOfTheNode() {
    NodeViewAgent agent = new NodeViewAgent(NODE);

    assertThat(agent.name()).isEqualTo("solo");
    assertThat(agent.description()).isEqualTo("A test node.");
    assertThat(agent.node()).isSameInstanceAs(NODE);
  }

  @Test
  public void constructor_withAnAgent_throwsIllegalArgumentException() {
    BaseAgent agent = TestUtils.createSubAgent("worker");

    assertThrows(IllegalArgumentException.class, () -> new NodeViewAgent(agent));
  }

  @Test
  public void constructor_withANodeNameThatIsNotAValidAgentName_throwsIllegalArgumentException() {
    Node node = TestNode.of("not@valid");

    assertThrows(IllegalArgumentException.class, () -> new NodeViewAgent(node));
  }

  @Test
  public void runAsyncImpl_failsWithUnsupportedOperationException() {
    NodeViewAgent agent = new NodeViewAgent(NODE);

    agent.runAsyncImpl(contextFor(agent)).test().assertError(UnsupportedOperationException.class);
  }

  @Test
  public void runLiveImpl_failsWithUnsupportedOperationException() {
    NodeViewAgent agent = new NodeViewAgent(NODE);

    agent.runLiveImpl(contextFor(agent)).test().assertError(UnsupportedOperationException.class);
  }

  private static InvocationContext contextFor(NodeViewAgent agent) {
    return TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
        .agent(agent)
        .node(agent.node())
        .build();
  }
}
