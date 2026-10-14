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
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Context;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class JoinNodeTest {

  @Test
  public void build_withOnlyAName_leavesTheRestAtDefaults() {
    JoinNode join = JoinNode.builder().name("gather").build();

    assertThat(join.name()).isEqualTo("gather");
    assertThat(join.description()).isEmpty();
    assertThat(join.inputSchema()).isEmpty();
    assertThat(join.outputSchema()).isEmpty();
    assertThat(join.rerunOnResume()).isFalse();
    assertThat(join.waitForOutput()).isFalse();
    assertThat(join.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(join.stateSchema()).isEmpty();
  }

  @Test
  public void build_setsEveryProperty() {
    Schema inputSchema = Schema.builder().description("input").build();
    Schema outputSchema = Schema.builder().description("output").build();

    JoinNode join =
        JoinNode.builder()
            .name("gather")
            .description("Joins the research notes.")
            .inputSchema(inputSchema)
            .outputSchema(outputSchema)
            .build();

    assertThat(join.description()).isEqualTo("Joins the research notes.");
    assertThat(join.inputSchema()).hasValue(inputSchema);
    assertThat(join.outputSchema()).hasValue(outputSchema);
  }

  @Test
  public void build_rejectsAMissingName() {
    JoinNode.Builder builder = JoinNode.builder();

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);

    assertThat(e).hasMessageThat().contains("name");
  }

  @Test
  public void name_rejectsNull() {
    JoinNode.Builder builder = JoinNode.builder();

    assertThrows(NullPointerException.class, () -> builder.name(null));
  }

  @Test
  public void description_rejectsNull() {
    JoinNode.Builder builder = JoinNode.builder();

    assertThrows(NullPointerException.class, () -> builder.description(null));
  }

  @Test
  public void requiresAllPredecessors_isTrue() {
    assertThat(JoinNode.builder().name("gather").build().requiresAllPredecessors()).isTrue();
  }

  @Test
  public void runNode_outputsTheJoinedOutputsUnchanged() {
    JoinNode join = JoinNode.builder().name("gather").build();
    ImmutableMap<String, Object> joined = ImmutableMap.of("a", "A", "b", "B");

    List<?> outputs = join.runNode(context(), joined).toList().blockingGet();

    assertThat(outputs).containsExactly(joined);
  }

  @Test
  public void runNode_withoutInput_outputsNothing() {
    JoinNode join = JoinNode.builder().name("gather").build();

    List<?> outputs = join.runNode(context(), /* nodeInput= */ null).toList().blockingGet();

    assertThat(outputs).isEmpty();
  }

  private static Context context() {
    return new CallbackContext(
        TestUtils.createInvocationContext(TestUtils.createRootAgent()), /* eventActions= */ null);
  }
}
