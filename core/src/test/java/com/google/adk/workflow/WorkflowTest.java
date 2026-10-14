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
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Schema;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class WorkflowTest {

  @Test
  public void build_buildsTheGraphFromTheEdges() {
    Node a = new StubNode("a");
    Node b = new StubNode("b");
    ImmutableList<Edge> edges = ImmutableList.of(new Edge(Start.INSTANCE, a), new Edge(a, b));

    Workflow workflow = Workflow.builder().name("flow").edges(edges).build();

    assertThat(workflow.edges()).containsExactlyElementsIn(edges).inOrder();
    Graph graph = workflow.graph().orElseThrow();
    assertThat(graph.nodes()).containsExactly(Start.INSTANCE, a, b).inOrder();
    assertThat(graph.terminalNodeNames()).containsExactly("b");
  }

  @Test
  public void build_withoutEdges_buildsNoGraph() {
    Workflow workflow = Workflow.builder().name("flow").build();

    assertThat(workflow.edges()).isEmpty();
    assertThat(workflow.graph()).isEmpty();
  }

  @Test
  public void build_leavesUnsetPropertiesAtWorkflowDefaults() {
    Workflow workflow = Workflow.builder().name("flow").build();

    assertThat(workflow.name()).isEqualTo("flow");
    assertThat(workflow.description()).isEmpty();
    assertThat(workflow.maxConcurrency()).isEmpty();
    // Unlike other nodes, a workflow reruns from scratch when resumed.
    assertThat(workflow.rerunOnResume()).isTrue();
    assertThat(workflow.waitForOutput()).isFalse();
    assertThat(workflow.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(workflow.inputSchema()).isEmpty();
    assertThat(workflow.outputSchema()).isEmpty();
    assertThat(workflow.stateSchema()).isEmpty();
  }

  @Test
  public void build_setsEveryProperty() {
    NodeConfig config = NodeConfig.builder().timeout(Duration.ofSeconds(5)).build();
    Schema inputSchema = Schema.builder().description("input").build();
    Schema outputSchema = Schema.builder().description("output").build();
    Schema stateSchema = Schema.builder().description("state").build();

    Workflow workflow =
        Workflow.builder()
            .name("flow")
            .description("Triages a request.")
            .maxConcurrency(2)
            .rerunOnResume(false)
            .waitForOutput(true)
            .config(config)
            .inputSchema(inputSchema)
            .outputSchema(outputSchema)
            .stateSchema(stateSchema)
            .build();

    assertThat(workflow.description()).isEqualTo("Triages a request.");
    assertThat(workflow.maxConcurrency()).hasValue(2);
    assertThat(workflow.rerunOnResume()).isFalse();
    assertThat(workflow.waitForOutput()).isTrue();
    assertThat(workflow.config()).isEqualTo(config);
    assertThat(workflow.inputSchema()).hasValue(inputSchema);
    assertThat(workflow.outputSchema()).hasValue(outputSchema);
    assertThat(workflow.stateSchema()).hasValue(stateSchema);
  }

  @Test
  public void edges_withVarargs_setsTheEdgesInOrder() {
    Node a = new StubNode("a");
    Edge first = new Edge(Start.INSTANCE, a);
    Edge second = new Edge(a, new StubNode("b"));

    Workflow workflow = Workflow.builder().name("flow").edges(first, second).build();

    assertThat(workflow.edges()).containsExactly(first, second).inOrder();
  }

  @Test
  public void name_rejectsNull() {
    Workflow.Builder builder = Workflow.builder();

    assertThrows(NullPointerException.class, () -> builder.name(null));
  }

  @Test
  public void description_rejectsNull() {
    Workflow.Builder builder = Workflow.builder();

    assertThrows(NullPointerException.class, () -> builder.description(null));
  }

  @Test
  public void config_rejectsNull() {
    Workflow.Builder builder = Workflow.builder();

    assertThrows(NullPointerException.class, () -> builder.config(null));
  }

  @Test
  public void edges_rejectsANullList() {
    Workflow.Builder builder = Workflow.builder();

    assertThrows(NullPointerException.class, () -> builder.edges((List<Edge>) null));
  }

  @Test
  public void maxConcurrency_acceptsOne() {
    Workflow workflow = Workflow.builder().name("flow").maxConcurrency(1).build();

    assertThat(workflow.maxConcurrency()).hasValue(1);
  }

  @Test
  public void maxConcurrency_rejectsAValueBelowOne() {
    Workflow.Builder builder = Workflow.builder();

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> builder.maxConcurrency(0));

    assertThat(e).hasMessageThat().contains("maxConcurrency");
  }

  @Test
  public void build_rejectsAMissingName() {
    Workflow.Builder builder = Workflow.builder();

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);

    assertThat(e).hasMessageThat().contains("name");
  }

  @Test
  public void aWorkflowCanBeANodeOfAnotherWorkflow() {
    Workflow inner =
        Workflow.builder().name("inner").edges(new Edge(Start.INSTANCE, new StubNode("a"))).build();

    Workflow outer =
        Workflow.builder().name("outer").edges(new Edge(Start.INSTANCE, inner)).build();

    assertThat(outer.graph().orElseThrow().node("inner")).isSameInstanceAs(inner);
  }

  @Test
  public void runNode_failsAsNotSupportedYet() {
    Workflow workflow =
        Workflow.builder().name("flow").edges(new Edge(Start.INSTANCE, new StubNode("a"))).build();
    CallbackContext context =
        new CallbackContext(
            TestUtils.createInvocationContext(TestUtils.createRootAgent()),
            /* eventActions= */ null);

    workflow
        .runNode(context, /* nodeInput= */ null)
        .test()
        .assertError(UnsupportedOperationException.class);
  }
}
