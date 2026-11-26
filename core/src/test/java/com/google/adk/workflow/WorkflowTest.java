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

import static com.google.adk.testing.WorkflowTestNodes.dispatcher;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.eventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.workflow.WorkflowResumeTesting.FailedTurn;
import com.google.adk.workflow.WorkflowResumeTesting.JsonSessionService;
import com.google.adk.workflow.WorkflowResumeTesting.LiveEventSessionService;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class WorkflowTest {

  private static final Schema INTEGER = Schema.builder().type(Type.Known.INTEGER).build();

  /** An object schema with an integer {@code count}. */
  private static final Schema COUNT_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(ImmutableMap.of("count", INTEGER))
          .build();

  /** A record whose JSON form fits {@link #COUNT_SCHEMA}. */
  private record Count(int count) {}

  private record Ratio(double value) {}

  private enum Color {
    RED,
    GREEN
  }

  @Test
  public void build_buildsTheGraphFromTheEdges() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
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
    Node a = TestNode.of("a");
    Edge first = new Edge(Start.INSTANCE, a);
    Edge second = new Edge(a, TestNode.of("b"));

    Workflow workflow = Workflow.builder().name("flow").edges(first, second).build();

    assertThat(workflow.edges()).containsExactly(first, second).inOrder();
  }

  @Test
  public void edges_withAConsumer_setsTheEdgesItAdds() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");

    Workflow workflow =
        Workflow.builder().name("flow").edges(g -> g.fromStart().then(a).then(b)).build();

    assertThat(workflow.edges())
        .containsExactly(new Edge(Start.INSTANCE, a), new Edge(a, b))
        .inOrder();
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
  public void edges_rejectsANullConsumer() {
    Workflow.Builder builder = Workflow.builder();

    assertThrows(NullPointerException.class, () -> builder.edges((Consumer<EdgesBuilder>) null));
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
  public void build_rejectsANameThatANodePathCannotCarry() {
    Workflow.Builder builder = Workflow.builder().name("support.flow");

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("but 'support.flow' does");
  }

  @Test
  public void build_rejectsANodeNameThatANodePathCannotCarry() {
    Workflow.Builder builder =
        Workflow.builder().name("flow").edges(new Edge(Start.INSTANCE, TestNode.of("a/b")));

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);

    assertThat(e).hasMessageThat().contains("but 'a/b' does");
  }

  @Test
  public void build_rejectsAMalformedGraph() {
    Node a = TestNode.of("a");
    Node b = TestNode.of("b");
    Workflow.Builder builder =
        Workflow.builder()
            .name("flow")
            .edges(new Edge(Start.INSTANCE, a), new Edge(a, b), new Edge(b, a));

    GraphValidationException e = assertThrows(GraphValidationException.class, builder::build);

    assertThat(e).hasMessageThat().contains("Unconditional cycle detected: a -> b -> a.");
  }

  @Test
  public void aWorkflowCanBeANodeOfAnotherWorkflow() {
    Workflow inner =
        Workflow.builder().name("inner").edges(new Edge(Start.INSTANCE, TestNode.of("a"))).build();

    Workflow outer =
        Workflow.builder().name("outer").edges(new Edge(Start.INSTANCE, inner)).build();

    assertThat(outer.graph().orElseThrow().node("inner")).isSameInstanceAs(inner);
  }

  @Test
  public void runNode_withoutEdges_completesWithoutEmitting() {
    Workflow workflow = Workflow.builder().name("flow").build();
    CallbackContext context =
        new CallbackContext(
            TestUtils.createInvocationContext(TestUtils.createRootAgent()),
            /* eventActions= */ null);

    workflow.runNode(context, /* nodeInput= */ null).test().assertComplete().assertNoValues();
  }

  @Test
  public void run_plainNodeOutput_isHandedOnAsItsOutputSchemaReadsIt() {
    // Arrange
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            COUNT_SCHEMA,
            (ctx, input) -> Flowable.just(ImmutableMap.of("count", "3")));
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, producer), new Edge(producer, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(outputEventAt(events, "wf@1/producer@1").output())
        .hasValue(ImmutableMap.of("count", 3));
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("count", 3));
  }

  @Test
  public void run_plainNodeEventOutput_isHandedOnAsItsOutputSchemaReadsIt() {
    // Arrange
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            COUNT_SCHEMA,
            (ctx, input) ->
                Flowable.just(newEvent().output(ImmutableMap.of("count", "3")).build()));
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, producer), new Edge(producer, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("count", 3));
  }

  @Test
  public void run_plainNodeRecordOutput_isHandedOnInJsonForm() {
    // Arrange
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            COUNT_SCHEMA,
            (ctx, input) -> Flowable.just(new Count(3)));
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, producer), new Edge(producer, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(outputEventAt(events, "wf@1/producer@1").output())
        .hasValue(ImmutableMap.of("count", 3));
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("count", 3));
  }

  @Test
  public void run_plainNodeEnumOutput_isHandedOnAsItsName() {
    // Arrange
    Schema colors = Schema.builder().type(Type.Known.STRING).enum_("RED", "GREEN").build();
    Node producer =
        new SchemaNode(
            "producer", /* input= */ null, colors, (ctx, input) -> Flowable.just(Color.RED));
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, producer), new Edge(producer, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo("RED");
  }

  @Test
  public void run_plainNodeRecordInput_isReadByItsInputSchemaInJsonForm() {
    // Arrange
    Node reply =
        new SchemaNode(
            "reply",
            COUNT_SCHEMA,
            /* output= */ null,
            (ctx, input) -> Flowable.just(Collections.singletonMap("received", input)));
    Node producer = emitter("producer", new Count(7));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, producer), new Edge(producer, reply)));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("count", 7));
  }

  @Test
  public void run_join_readsARecordPredecessorOutputInJsonForm() {
    // Arrange
    Node a = emitter("a", new Count(2));
    Node b = emitter("b", ImmutableMap.of("count", 5));
    JoinNode join = JoinNode.builder().name("join").inputSchema(COUNT_SCHEMA).build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, a),
                new Edge(START, b),
                new Edge(a, join),
                new Edge(b, join),
                new Edge(join, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events))
        .isEqualTo(
            ImmutableMap.of("a", ImmutableMap.of("count", 2), "b", ImmutableMap.of("count", 5)));
  }

  @Test
  public void runNode_recordChildInput_reachesTheChildInJsonForm() {
    // Arrange
    Node child =
        new SchemaNode(
            "child",
            COUNT_SCHEMA,
            /* output= */ null,
            (ctx, input) -> Flowable.just(Collections.singletonMap("received", input)));
    Node parent =
        dispatcher("parent", (ctx, input) -> ctx.runNode(child, new Count(3)).toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, parent)));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(outputEventAt(events, "wf@1/parent@1/child@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("count", 3)));
  }

  @Test
  public void resume_recordOutputFromAJsonStore_reachesTheJoinAsItsLiveJsonForm() {
    // Arrange
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            COUNT_SCHEMA,
            (ctx, input) -> Flowable.just(new Count(3)));
    Node ask = longRunningAsk("ask", "ask_1");
    JoinNode join = JoinNode.builder().name("join").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, producer),
                new Edge(START, ask),
                new Edge(producer, join),
                new Edge(ask, join),
                new Edge(join, captureInput("reply"))),
            new JsonSessionService());
    ImmutableList<Event> live = session.turn(go());

    // Act
    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    // Assert
    Object liveOutput = outputEventAt(live, "wf@1/producer@1").output().orElseThrow();
    assertThat(liveOutput).isEqualTo(ImmutableMap.of("count", 3));
    assertThat(replyInput(events))
        .isEqualTo(ImmutableMap.of("producer", liveOutput, "ask", ImmutableMap.of("v", 1)));
  }

  @Test
  public void resume_convertedOutputFromALiveEventStore_matchesTheLiveSuccessorsInput() {
    // Arrange
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            COUNT_SCHEMA,
            (ctx, input) -> Flowable.just(ImmutableMap.of("count", "3")));
    Node liveSuccessor = captureInput("live");
    Node ask = longRunningAsk("ask", "ask_1");
    JoinNode join = JoinNode.builder().name("join").build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, producer),
                new Edge(START, ask),
                new Edge(producer, liveSuccessor),
                new Edge(producer, join),
                new Edge(liveSuccessor, join),
                new Edge(ask, join),
                new Edge(join, captureInput("reply"))),
            new LiveEventSessionService());
    ImmutableList<Event> live = session.turn(go());

    // Act
    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("v", 1)));

    // Assert
    Map<?, ?> liveOutput = (Map<?, ?>) outputEventAt(live, "wf@1/live@1").output().orElseThrow();
    Map<?, ?> liveInput = (Map<?, ?>) liveOutput.get("received");
    assertThat(liveInput).isEqualTo(ImmutableMap.of("count", 3));
    assertThrows(UnsupportedOperationException.class, liveInput::clear);
    assertThat(((Map<?, ?>) replyInput(events)).get("producer")).isEqualTo(liveInput);
  }

  @Test
  public void run_plainNodeOutputWithoutAJsonForm_failsWithAValidationError() {
    // Arrange
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            Schema.builder().type(Type.Known.OBJECT).build(),
            (ctx, input) -> Flowable.just(new Ratio(Double.NaN)));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, producer)));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'producer' holds a NaN or infinite number, which JSON"
                + " lacks.");
  }

  @Test
  public void runNode_eventChildInputUnderAnObjectSchema_failsAsNotAnObject() {
    // Arrange
    Node child =
        new SchemaNode(
            "child",
            Schema.builder().type(Type.Known.OBJECT).build(),
            /* output= */ null,
            (ctx, input) -> Flowable.just("ran"));
    Node parent =
        dispatcher("parent", (ctx, input) -> ctx.runNode(child, newEvent().build()).toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, parent)));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: input of node 'child' does not match its schema: the value is not"
                + " an object");
  }

  @Test
  public void runNode_eventChildInputUnderAnUntypedSchema_reachesTheChildAsTheEvent() {
    // Arrange
    Node child =
        new SchemaNode(
            "child",
            Schema.builder().build(),
            /* output= */ null,
            (ctx, input) -> Flowable.just(input instanceof Event));
    Node parent =
        dispatcher("parent", (ctx, input) -> ctx.runNode(child, newEvent().build()).toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, parent)));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(outputEventAt(events, "wf@1/parent@1/child@1").output()).hasValue(true);
  }

  @Test
  public void run_outputOutsideItsSchemaEnum_failsTheNodeWithoutTheValue() {
    // Arrange
    Schema colors = Schema.builder().type(Type.Known.STRING).enum_("red", "green").build();
    Node node =
        new SchemaNode("n", /* input= */ null, colors, (ctx, input) -> Flowable.just("s3cret"));
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, node)));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    String message =
        "validation error: output of node 'n' does not match its schema: the value is not one of"
            + " the schema's enum values";
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error()).hasMessageThat().isEqualTo(message);
    Event error = eventAt(turn.events(), "wf@1/n@1");
    assertThat(error.errorCode()).hasValue(new FinishReason("IllegalArgumentException"));
    assertThat(error.errorMessage()).hasValue(message);
  }

  @Test
  public void run_plainNodeInput_isReadByItsInputSchema() {
    // Arrange
    Node reply =
        new SchemaNode(
            "reply",
            COUNT_SCHEMA,
            /* output= */ null,
            (ctx, input) -> Flowable.just(Collections.singletonMap("received", input)));
    Node producer = emitter("producer", ImmutableMap.of("count", "7"));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, producer), new Edge(producer, reply)));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("count", 7));
  }

  @Test
  public void run_join_readsEachPredecessorOutputByItsInputSchema() {
    // Arrange
    Node a = emitter("a", "2");
    Node b = emitter("b", 5);
    JoinNode join = JoinNode.builder().name("join").inputSchema(INTEGER).build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, a),
                new Edge(START, b),
                new Edge(a, join),
                new Edge(b, join),
                new Edge(join, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("a", 2, "b", 5));
  }

  @Test
  public void run_joinWithAPredecessorOutputOutsideItsSchema_failsNamingThePredecessor() {
    // Arrange
    Node a = emitter("a", "two");
    Node b = emitter("b", 5);
    JoinNode join = JoinNode.builder().name("join").inputSchema(INTEGER).build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, a), new Edge(START, b), new Edge(a, join), new Edge(b, join)));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of 'a' into node 'join' does not match its schema: the value"
                + " is not an integer");
  }

  @Test
  public void runNode_childInputOutsideItsSchema_failsTheCallerAndNeverStartsTheChild() {
    // Arrange
    AtomicInteger childRuns = new AtomicInteger();
    Node child =
        new SchemaNode(
            "child",
            INTEGER,
            /* output= */ null,
            (ctx, input) -> {
              childRuns.incrementAndGet();
              return Flowable.just(input);
            });
    Node parent = dispatcher("parent", (ctx, input) -> ctx.runNode(child, "three").toFlowable());
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, parent)));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: input of node 'child' does not match its schema: the value is not"
                + " an integer");
    assertThat(eventAt(turn.events(), "wf@1/parent@1").errorCode())
        .hasValue(new FinishReason("IllegalArgumentException"));
    assertThat(childRuns.get()).isEqualTo(0);
    assertThat(pathsOf(turn.events())).doesNotContain("wf@1/parent@1/child@1");
  }

  @Test
  public void runNode_retryAfterARejectedChildInput_runsTheChildUnderTheNextRunIdAsTheOutput() {
    // Arrange
    RunNodeConfig useAsOutput = RunNodeConfig.builder().useAsOutput(true).build();
    Node child =
        new SchemaNode("child", INTEGER, /* output= */ null, (ctx, in) -> Flowable.just(in));
    Node parent =
        dispatcher(
            "parent",
            (ctx, input) ->
                ctx.runNode(child, "three", useAsOutput)
                    .onErrorResumeWith(ctx.runNode(child, "3", useAsOutput))
                    .toFlowable());
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, parent), new Edge(parent, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(outputEventAt(events, "wf@1/parent@1/child@2").output()).hasValue(3);
    assertThat(pathsOf(events)).doesNotContain("wf@1/parent@1/child@1");
    assertThat(replyInput(events)).isEqualTo(3);
  }

  @Test
  public void run_plainFanInNode_convertsEachPredecessorOutputByItsInputSchema() {
    // Arrange
    Node a = emitter("a", "2");
    Node b = emitter("b", 5);
    Node reply = new FanInNode("reply", INTEGER);
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, a), new Edge(START, b), new Edge(a, reply), new Edge(b, reply)));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("a", 2, "b", 5));
  }

  @Test
  public void run_joinWithAPredecessorWithoutOutput_getsNullForIt() {
    // Arrange
    Node a = emitter("a", "2");
    Node b = TestNode.of("b");
    JoinNode join = JoinNode.builder().name("join").inputSchema(INTEGER).build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, a),
                new Edge(START, b),
                new Edge(a, join),
                new Edge(b, join),
                new Edge(join, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    Map<String, @Nullable Object> expected = new HashMap<>();
    expected.put("a", 2);
    expected.put("b", null);
    assertThat(replyInput(events)).isEqualTo(expected);
  }

  @Test
  public void run_workflowNode_startsItsGraphOnTheInputItsSchemaReads() {
    // Arrange
    Workflow inner =
        Workflow.builder()
            .name("inner")
            .inputSchema(COUNT_SCHEMA)
            .edges(new Edge(START, captureInput("reply")))
            .build();
    Node producer = emitter("producer", ImmutableMap.of("count", "3"));
    WorkflowSession session =
        new WorkflowSession(workflow(new Edge(START, producer), new Edge(producer, inner)));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(outputEventAt(events, "wf@1/inner@1/reply@1").output())
        .hasValue(ImmutableMap.of("received", ImmutableMap.of("count", 3)));
  }

  @Test
  public void run_workflowRootWithAnInputSchema_failsAContentPortAfterStart() {
    // Arrange
    FunctionNode<Content, String> reader =
        FunctionNodes.of("reader", Content.class, String.class, (ctx, message) -> message.text());
    Workflow root =
        Workflow.builder()
            .name("wf")
            .inputSchema(COUNT_SCHEMA)
            .edges(new Edge(START, reader))
            .build();
    WorkflowSession session = new WorkflowSession(root);

    // Act
    FailedTurn turn = session.failingTurn(Content.fromParts(Part.fromText("{\"count\": \"3\"}")));

    // Assert
    assertThat(turn.error()).isInstanceOf(NodeInputValidationException.class);
    assertThat(turn.error()).hasMessageThat().contains("input of node 'reader'");
    assertThat(eventAt(turn.events(), "wf@1/reader@1").errorCode()).isPresent();
  }

  @Test
  public void run_llmAgentNodeWithAnObjectInputSchema_failsOnTextThatIsNotJson() {
    // Arrange
    TestLlm model = TestLlm.create(TestUtils.createTextLlmResponse("done"));
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(model).inputSchema(COUNT_SCHEMA).build();
    WorkflowSession session = new WorkflowSession(workflow(new Edge(START, agent)));

    // Act
    FailedTurn turn = session.failingTurn(Content.fromParts(Part.fromText("s3cret words")));

    // Assert
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: input of node 'agent' does not match its schema: the value is not"
                + " an object");
    assertThat(model.getRequests()).isEmpty();
  }

  @Test
  public void run_functionNodeOutputOutsideItsBuilderSchema_isHandedOnUnchecked() {
    // Arrange
    Schema colors = Schema.builder().type(Type.Known.STRING).enum_("red", "green").build();
    FunctionNode<Object, String> producer =
        FunctionNodes.builder(Object.class, String.class)
            .name("producer")
            .outputSchema(colors)
            .body((ctx, input) -> "blue")
            .build();
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, producer), new Edge(producer, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo("blue");
  }

  /** A plain fan-in node, not a {@link BaseNode}, that outputs what it received. */
  private record FanInNode(String name, Schema input) implements Node {

    @Override
    public boolean requiresAllPredecessors() {
      return true;
    }

    @Override
    public Optional<Schema> inputSchema() {
      return Optional.of(input);
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(Collections.singletonMap("received", nodeInput));
    }
  }

  /** A plain node, not a {@link BaseNode}, with schemas. */
  private record SchemaNode(
      String name,
      @Nullable Schema input,
      @Nullable Schema output,
      BiFunction<Context, @Nullable Object, Flowable<?>> body)
      implements Node {

    @Override
    public Optional<Schema> inputSchema() {
      return Optional.ofNullable(input);
    }

    @Override
    public Optional<Schema> outputSchema() {
      return Optional.ofNullable(output);
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return body.apply(context, nodeInput);
    }
  }
}
