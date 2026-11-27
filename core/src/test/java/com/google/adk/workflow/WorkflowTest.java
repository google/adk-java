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
import static com.google.adk.workflow.WorkflowResumeTesting.STATE_SCHEMA;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.errorEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.eventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.adk.workflow.WorkflowResumeTesting.stateSchemaWorkflow;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.State;
import com.google.adk.sessions.StateSchemaException;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
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

  /** A record whose JSON form does not fit {@link #COUNT_SCHEMA}: its count is text. */
  private record TextCount(String count) {}

  private record Ratio(double value) {}

  private enum Color {
    RED,
    GREEN
  }

  /**
   * A state schema whose {@code total} takes a {@link Count}, {@code color} a color name and {@code
   * tags} a list of strings.
   */
  private static final Schema TYPED_STATE_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "total",
                  COUNT_SCHEMA,
                  "color",
                  Schema.builder().type(Type.Known.STRING).enum_("RED", "GREEN").build(),
                  "tags",
                  Schema.builder()
                      .type(Type.Known.ARRAY)
                      .items(Schema.builder().type(Type.Known.STRING).build())
                      .build()))
          .build();

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
  public void run_messageBesideAnEnumInAnOutput_reachesTheSuccessorAsText() {
    // Arrange
    Schema string = Schema.builder().type(Type.Known.STRING).build();
    Node producer =
        new SchemaNode(
            "producer",
            /* input= */ null,
            Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(ImmutableMap.of("msg", string, "color", string))
                .build(),
            (ctx, input) ->
                Flowable.just(
                    ImmutableMap.of(
                        "msg", Content.fromParts(Part.fromText("hi")), "color", Color.RED)));
    WorkflowSession session =
        new WorkflowSession(
            workflow(new Edge(START, producer), new Edge(producer, captureInput("reply"))));

    // Act
    ImmutableList<Event> events = session.turn(go());

    // Assert
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("msg", "hi", "color", "RED"));
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
  public void run_functionNodeOutputOutsideItsBuilderSchema_failsTheNodeWithoutTheValue() {
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
    FailedTurn turn = session.failingTurn(go());

    // Assert
    assertThat(turn.error()).isInstanceOf(IllegalArgumentException.class);
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'producer' does not match its schema: the value is"
                + " not one of the schema's enum values");
    assertThat(eventAt(turn.events(), "wf@1/producer@1").errorCode())
        .hasValue(new FinishReason("IllegalArgumentException"));
    assertThat(pathsOf(turn.events())).doesNotContain("wf@1/reply@1");
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

  @Test
  public void stateSchema_anEmittedStateDeltaIsCheckedWithoutChangingTheEmittedEvent() {
    Event emitted =
        newEvent()
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("count", "4")).build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    session.advance(go());

    assertThat(session.state()).containsEntry("count", 4);
    assertThat(emitted.actions().stateDelta()).containsExactly("count", "4");
  }

  @Test
  public void stateSchema_anUndeclaredKeyInAnEmittedDeltaFailsTheNode() {
    Node node =
        new TestNode(
            "n",
            (ctx, input) ->
                Flowable.just(
                    newEvent()
                        .actions(
                            EventActions.builder().stateDelta(ImmutableMap.of("other", 1)).build())
                        .build()));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    FailedTurn turn = session.failingTurn(go());

    Event errorEvent = errorEvent(turn.events());
    assertThat(errorEvent.errorCode().orElseThrow().toString()).contains("StateSchemaException");
    assertThat(errorEvent.errorMessage())
        .hasValue("validation error: state key 'other' is not declared in the state schema.");
    assertThat(session.state()).doesNotContainKey("other");
  }

  @Test
  public void stateSchema_aTypeMismatchInAnEmittedDeltaFailsTheNode() {
    Event emitted =
        newEvent()
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("count", "nope")).build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    FailedTurn turn = session.failingTurn(go());

    Event errorEvent = errorEvent(turn.events());
    assertThat(turn.error()).isInstanceOf(StateSchemaException.class);
    assertThat(errorEvent.errorCode().orElseThrow().toString()).contains("StateSchemaException");
    assertThat(errorEvent.errorMessage().orElseThrow())
        .startsWith("validation error: state key 'count' does not match its schema: ");
    assertThat(session.state()).doesNotContainKey("count");
  }

  @Test
  public void stateSchema_anEmittedDeltaValueConvertingToNullFailsTheNode() {
    Content nullText = Content.fromParts(Part.fromText("null"));
    Event emitted =
        newEvent()
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("note", nullText)).build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    FailedTurn turn = session.failingTurn(go());

    Event errorEvent = errorEvent(turn.events());
    assertThat(turn.error()).isInstanceOf(StateSchemaException.class);
    assertThat(errorEvent.errorCode().orElseThrow().toString()).contains("StateSchemaException");
    assertThat(errorEvent.errorMessage()).hasValue("validation error: state key 'note' is null.");
    assertThat(session.state()).doesNotContainKey("note");
  }

  @Test
  public void stateSchema_aRecordAndAnEnumInAnEmittedDeltaAreStoredInJsonForm() {
    // Arrange
    Event emitted =
        newEvent()
            .actions(
                EventActions.builder()
                    .stateDelta(ImmutableMap.of("total", new Count(3), "color", Color.RED))
                    .build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(TYPED_STATE_SCHEMA, node));

    // Act
    session.advance(go());

    // Assert
    Map<String, Object> storedDelta =
        session.storedEvents().stream()
            .map(event -> event.actions().stateDelta())
            .filter(delta -> delta.containsKey("total"))
            .collect(onlyElement());
    assertThat(storedDelta).containsExactly("total", ImmutableMap.of("count", 3), "color", "RED");
    assertThat(session.state())
        .containsAtLeast("total", ImmutableMap.of("count", 3), "color", "RED");
  }

  @Test
  public void stateSchema_aRecordWhoseJsonFormDoesNotFitFailsTheNode() {
    // Arrange
    Event emitted =
        newEvent()
            .actions(
                EventActions.builder()
                    .stateDelta(ImmutableMap.of("total", new TextCount("many")))
                    .build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(TYPED_STATE_SCHEMA, node));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    Event errorEvent = errorEvent(turn.events());
    assertThat(turn.error()).isInstanceOf(StateSchemaException.class);
    assertThat(errorEvent.errorMessage().orElseThrow())
        .startsWith("validation error: state key 'total' does not match its schema: ");
    assertThat(session.state()).doesNotContainKey("total");
  }

  @Test
  public void stateSchema_aValueWithNoJsonFormFailsWithStateSchemaException() {
    // Arrange
    Event emitted =
        newEvent()
            .actions(
                EventActions.builder().stateDelta(ImmutableMap.of("note", new Object())).build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    Event errorEvent = errorEvent(turn.events());
    assertThat(turn.error()).isInstanceOf(StateSchemaException.class);
    assertThat(errorEvent.errorCode().orElseThrow().toString()).contains("StateSchemaException");
    assertThat(session.state()).doesNotContainKey("note");
  }

  @Test
  public void stateSchema_aLongUnderAnIntegerKeyIsStoredAsAnInteger() {
    // Arrange
    AtomicReference<@Nullable Object> live = new AtomicReference<>();
    WorkflowSession session =
        new WorkflowSession(
            emitThenRead(STATE_SCHEMA, ImmutableMap.of("count", 3L), "count", live));

    // Act
    session.advance(go());

    // Assert
    assertThat(live.get()).isInstanceOf(Integer.class);
    assertThat(live.get()).isEqualTo(3);
    assertThat(storedDelta(session, "count").get("count")).isInstanceOf(Integer.class);
    assertThat(session.state().get("count")).isEqualTo(3);
  }

  @Test
  public void stateSchema_aMutableListIsStoredUnmodifiable() {
    // Arrange
    AtomicReference<@Nullable Object> live = new AtomicReference<>();
    List<String> tags = new ArrayList<>(ImmutableList.of("a"));
    WorkflowSession session =
        new WorkflowSession(
            emitThenRead(TYPED_STATE_SCHEMA, ImmutableMap.of("tags", tags), "tags", live));

    // Act
    session.advance(go());

    // Assert
    @SuppressWarnings("unchecked") // The schema declares a list.
    List<Object> liveTags = (List<Object>) live.get();
    assertThat(liveTags).containsExactly("a");
    assertThrows(UnsupportedOperationException.class, () -> liveTags.add("b"));
    assertThat(storedDelta(session, "tags")).containsExactly("tags", ImmutableList.of("a"));
  }

  @Test
  public void stateSchema_aNaNUnderAnUntypedKeyFailsWithStateSchemaException() {
    // Arrange
    Event emitted =
        newEvent()
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("note", Double.NaN)).build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    // Act
    FailedTurn turn = session.failingTurn(go());

    // Assert
    assertThat(turn.error()).isInstanceOf(StateSchemaException.class);
    assertThat(errorEvent(turn.events()).errorMessage())
        .hasValue(
            "validation error: state key 'note' holds a NaN or infinite number, which JSON"
                + " lacks.");
    assertThat(session.state()).doesNotContainKey("note");
  }

  @Test
  public void stateSchema_aNestedMessageUnderAnUntypedKeyIsStoredAsItsJsonMap() {
    // Arrange
    AtomicReference<@Nullable Object> live = new AtomicReference<>();
    Map<String, Object> doc = ImmutableMap.of("body", Content.fromParts(Part.fromText("hi")));
    WorkflowSession session =
        new WorkflowSession(emitThenRead(STATE_SCHEMA, ImmutableMap.of("note", doc), "note", live));

    // Act
    session.advance(go());

    // Assert
    ImmutableMap<String, Object> json =
        ImmutableMap.of(
            "body",
            ImmutableMap.of(
                "parts", ImmutableList.of(ImmutableMap.of("text", "hi")), "role", "user"));
    assertThat(live.get()).isEqualTo(json);
    assertThat(storedDelta(session, "note")).containsExactly("note", json);
    assertThat(session.state()).containsEntry("note", json);
  }

  /**
   * Returns a workflow under {@code stateSchema} whose first node emits {@code delta} and whose
   * second node puts the live value of {@code key} into {@code live}.
   */
  private static Workflow emitThenRead(
      Schema stateSchema,
      Map<String, Object> delta,
      String key,
      AtomicReference<@Nullable Object> live) {
    Node emit =
        new TestNode(
            "emit",
            (ctx, input) ->
                Flowable.just(
                    newEvent().actions(EventActions.builder().stateDelta(delta).build()).build()));
    Node read =
        new TestNode(
            "read",
            (ctx, input) -> {
              live.set(ctx.state().get(key));
              return Flowable.empty();
            });
    return Workflow.builder()
        .name("wf")
        .stateSchema(stateSchema)
        .edges(g -> g.fromStart().then(emit).then(read))
        .build();
  }

  /** Returns the only stored state delta that holds {@code key}. */
  private static Map<String, Object> storedDelta(WorkflowSession session, String key) {
    return session.storedEvents().stream()
        .map(event -> event.actions().stateDelta())
        .filter(delta -> delta.containsKey(key))
        .collect(onlyElement());
  }

  @Test
  public void stateSchema_anLlmAgentNodeToolsUndeclaredWriteFailsTheNodeAtDispatch() {
    TestLlm model =
        TestLlm.create(
            TestUtils.createFunctionCallLlmResponse("call_1", "writeOther", ImmutableMap.of()),
            TestUtils.createTextLlmResponse("done"));
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .tools(FunctionTool.create(StateTools.class, "writeOther"))
            .build();
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, agent));

    FailedTurn turn = session.failingTurn(go());

    Event errorEvent = errorEvent(turn.events());
    assertThat(errorEvent.errorCode().orElseThrow().toString()).contains("StateSchemaException");
    assertThat(errorEvent.errorMessage())
        .hasValue("validation error: state key 'other' is not declared in the state schema.");
    assertThat(
            turn.events().stream().flatMap(event -> event.actions().stateDelta().keySet().stream()))
        .doesNotContain("other");
    // The write reached the live session unchecked; the in-memory service stores that session.
    assertThat(session.state()).containsEntry("other", 1);
  }

  @Test
  public void stateSchema_colonKeysAndRemovalsInEmittedDeltaPassUnchecked() {
    Event emitted =
        newEvent()
            .actions(
                EventActions.builder()
                    .stateDelta(
                        ImmutableMap.of(
                            "app:mode", "fast", "owner:count", "many", "count", State.REMOVED))
                    .build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(STATE_SCHEMA, node));

    session.advance(go());

    assertThat(session.state()).containsEntry("app:mode", "fast");
    assertThat(session.state()).containsEntry("owner:count", "many");
    assertThat(session.state()).doesNotContainKey("count");
  }

  @Test
  public void stateSchema_theNearestDeclaringAncestorsSchemaApplies() {
    Schema innerSchema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ImmutableMap.of("count", Schema.builder().type(Type.Known.STRING).build()))
            .build();
    Event emitted =
        newEvent()
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("count", "3")).build())
            .build();
    Node node = new TestNode("n", (ctx, input) -> Flowable.just(emitted));
    Workflow outer =
        Workflow.builder()
            .name("outer")
            .stateSchema(STATE_SCHEMA)
            .edges(g -> g.fromStart().then(stateSchemaWorkflow(innerSchema, node)))
            .build();
    WorkflowSession session = new WorkflowSession(outer);

    session.advance(go());

    assertThat(session.state()).containsEntry("count", "3");
  }

  @Test
  public void stateSchema_aStateWriteIsStoredConvertedToTheKeysSchema() {
    WorkflowSession session = stateSession(WorkflowTest::exercisePutAndHasDelta);

    List<Event> events = session.turn(go());

    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output())
        .hasValue(ImmutableMap.of("value", 3, "before", false, "after", true, "cleared", false));
    assertThat(outputEvent.actions().stateDelta()).containsEntry("count", 3);
    assertThat(session.state()).containsEntry("count", 3);
  }

  private static ImmutableMap<String, Object> exercisePutAndHasDelta(Context ctx) {
    State state = ctx.state();
    boolean before = state.hasDelta();
    state.put("count", "3");
    boolean after = state.hasDelta();
    assertThrows(UnsupportedOperationException.class, state::clear);
    assertThrows(UnsupportedOperationException.class, () -> state.keySet().remove("count"));
    assertThrows(
        UnsupportedOperationException.class, () -> state.entrySet().iterator().next().setValue(9));
    assertThrows(UnsupportedOperationException.class, () -> state.values().clear());
    ctx.eventActions().stateDelta().clear();
    boolean cleared = state.hasDelta();
    state.put("count", "3");
    return ImmutableMap.of(
        "value", state.get("count"),
        "before", before,
        "after", after,
        "cleared", cleared);
  }

  @Test
  public void stateSchema_putIfAbsentReplaceAndRemoveConvertAndRejectBadValues() {
    WorkflowSession session = stateSession(WorkflowTest::exercisePutIfAbsentReplaceAndRemove);

    List<Event> events = session.turn(go());

    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output())
        .hasValue(
            ImmutableMap.<String, Object>builder()
                .put("firstWasNull", true)
                .put("firstDelta", 1)
                .put("existing", 1)
                .put("absentReplaceWasNull", true)
                .put("absentRemoveWasNull", true)
                .put("noopsWroteDelta", false)
                .put("replaced", 1)
                .put("replaceDelta", 2)
                .put("badCas", false)
                .put("cas", true)
                .put("badRemove", false)
                .put("removed", true)
                .buildOrThrow());
    assertThat(outputEvent.actions().stateDelta()).containsExactly("count", State.REMOVED);
    assertThat(session.state()).isEmpty();
  }

  @Test
  public void stateSchema_putAllConvertsAndRejectsAtomically() {
    ImmutableMap<String, Object> valid = ImmutableMap.of("count", "2", "note", "ok");
    ImmutableMap<String, Object> invalid = ImmutableMap.of("count", "9", "other", 1);
    WorkflowSession session =
        stateSession(ctx -> {
          ctx.state().putAll(valid);
          assertThrows(IllegalArgumentException.class, () -> ctx.state().putAll(invalid));
          return ImmutableMap.copyOf(ctx.state());
        });

    List<Event> events = session.turn(go());

    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output()).hasValue(ImmutableMap.of("count", 2, "note", "ok"));
    assertThat(outputEvent.actions().stateDelta()).containsExactly("count", 2, "note", "ok");
    assertThat(session.state()).containsExactly("count", 2, "note", "ok");
  }

  @Test
  public void stateSchema_computeAndMergeReturnAndStoreConvertedValues() {
    WorkflowSession session = stateSession(WorkflowTest::exerciseComputeAndMerge);

    List<Event> events = session.turn(go());

    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output())
        .hasValue(
            ImmutableMap.<String, Object>builder()
                .put("computeIfAbsent", 1)
                .put("computeIfAbsentDelta", 1)
                .put("computeIfAbsentPresent", 1)
                .put("nullAbsentWasNull", true)
                .put("absentPresentWasNull", true)
                .put("absentComputeWasNull", true)
                .put("noopsWroteNoteDelta", false)
                .put("computeInsert", "viaCompute")
                .put("computeInsertDelta", "viaCompute")
                .put("mergeInsert", "viaMerge")
                .put("mergeInsertDelta", "viaMerge")
                .put("computeIfPresent", 2)
                .put("computeIfPresentDelta", 2)
                .put("compute", 3)
                .put("computeDelta", 3)
                .put("merge", 4)
                .put("mergeDelta", 4)
                .put("afterReplaceAll", 5)
                .put("removedByPresent", true)
                .put("removedByCompute", true)
                .put("removedByMerge", true)
                .buildOrThrow());
    assertThat(outputEvent.actions().stateDelta())
        .containsExactly("count", 5, "note", State.REMOVED);
    assertThat(session.state()).containsExactly("count", 5);
  }

  @Test
  public void stateSchema_computeRetriesOnConcurrentKeyChanges() {
    WorkflowSession session = stateSession(WorkflowTest::exerciseComputeRaces);

    List<Event> events = session.turn(go());

    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output())
        .hasValue(
            ImmutableMap.<String, Object>builder()
                .put("racedComputeInsert", 8)
                .put("liveAfterComputeInsert", 8)
                .put("racedComputeReplace", 10)
                .put("liveAfterComputeReplace", 10)
                .put("racedPresent", 12)
                .put("liveAfterPresent", 12)
                .put("racedMerge", 14)
                .put("liveAfterMerge", 14)
                .put("racedAbsent", "winner")
                .put("racedAbsentDelta", "winner")
                .put("racedRemoveWasNull", true)
                .put("liveNoteRemoved", true)
                .buildOrThrow());
    assertThat(outputEvent.actions().stateDelta())
        .containsExactly("count", 14, "note", State.REMOVED);
    assertThat(session.state()).containsExactly("count", 14);
  }

  private static ImmutableMap<String, Object> exercisePutIfAbsentReplaceAndRemove(Context ctx) {
    State state = ctx.state();
    Map<String, Object> delta = ctx.eventActions().stateDelta();
    Object first = state.putIfAbsent("count", "1");
    Object firstDelta = delta.get("count");
    delta.clear();
    Object existing = state.putIfAbsent("count", "9");
    Object absentReplace = state.replace("note", "x");
    boolean badCas = state.replace("count", 99, "3");
    Object absentRemove = state.remove("note");
    boolean badRemove = state.remove("count", 99);
    boolean noopsWroteDelta = !delta.isEmpty();
    assertThrows(IllegalArgumentException.class, () -> state.putIfAbsent("count", "bad"));
    Object replaced = state.replace("count", "2");
    Object replaceDelta = delta.get("count");
    boolean cas = state.replace("count", 2, "3");
    assertThrows(NullPointerException.class, () -> state.replace("count", null, "3"));
    assertThrows(IllegalArgumentException.class, () -> state.replace("count", "bad"));
    assertThrows(IllegalArgumentException.class, () -> state.replace("count", 3, "bad"));
    boolean removed = state.remove("count", 3);
    return ImmutableMap.<String, Object>builder()
        .put("firstWasNull", first == null)
        .put("firstDelta", firstDelta)
        .put("existing", existing)
        .put("absentReplaceWasNull", absentReplace == null)
        .put("absentRemoveWasNull", absentRemove == null)
        .put("noopsWroteDelta", noopsWroteDelta)
        .put("replaced", replaced)
        .put("replaceDelta", replaceDelta)
        .put("badCas", badCas)
        .put("cas", cas)
        .put("badRemove", badRemove)
        .put("removed", removed)
        .buildOrThrow();
  }

  private static ImmutableMap<String, Object> exerciseComputeAndMerge(Context ctx) {
    State state = ctx.state();
    Map<String, Object> delta = ctx.eventActions().stateDelta();
    Object r1 = state.computeIfAbsent("count", k -> "1");
    Object r1Delta = delta.get("count");
    Object r1Present = state.computeIfAbsent("count", k -> "bad");
    Object nullAbsent = state.computeIfAbsent("note", k -> null);
    Object absentPresent = state.computeIfPresent("note", (k, v) -> "bad");
    Object absentCompute = state.compute("note", (k, v) -> null);
    boolean noopsWroteNoteDelta = delta.containsKey("note");
    Object computeInsert = state.compute("note", (k, v) -> "viaCompute");
    Object computeInsertDelta = delta.get("note");
    state.remove("note");
    Object mergeInsert = state.merge("note", "viaMerge", (o, n) -> n);
    Object mergeInsertDelta = delta.get("note");
    state.remove("note");
    Object r2 = state.computeIfPresent("count", (k, v) -> "2");
    Object r2Delta = delta.get("count");
    Object r3 = state.compute("count", (k, v) -> "3");
    Object r3Delta = delta.get("count");
    Object r4 = state.merge("count", "4", (oldVal, newVal) -> newVal);
    Object r4Delta = delta.get("count");
    assertThrows(IllegalArgumentException.class, () -> state.compute("count", (k, v) -> "x"));
    assertThrows(IllegalArgumentException.class, () -> state.merge("other", "1", (o, n) -> n));
    state.replaceAll((k, v) -> "5");
    state.put("note", "a");
    Object rm1 = state.computeIfPresent("note", (k, v) -> null);
    state.put("note", "b");
    Object rm2 = state.compute("note", (k, v) -> null);
    state.put("note", "c");
    Object rm3 = state.merge("note", "d", (o, n) -> null);
    return ImmutableMap.<String, Object>builder()
        .put("computeIfAbsent", r1)
        .put("computeIfAbsentDelta", r1Delta)
        .put("computeIfAbsentPresent", r1Present)
        .put("nullAbsentWasNull", nullAbsent == null)
        .put("absentPresentWasNull", absentPresent == null)
        .put("absentComputeWasNull", absentCompute == null)
        .put("noopsWroteNoteDelta", noopsWroteNoteDelta)
        .put("computeInsert", computeInsert)
        .put("computeInsertDelta", computeInsertDelta)
        .put("mergeInsert", mergeInsert)
        .put("mergeInsertDelta", mergeInsertDelta)
        .put("computeIfPresent", r2)
        .put("computeIfPresentDelta", r2Delta)
        .put("compute", r3)
        .put("computeDelta", r3Delta)
        .put("merge", r4)
        .put("mergeDelta", r4Delta)
        .put("afterReplaceAll", state.get("count"))
        .put("removedByPresent", rm1 == null)
        .put("removedByCompute", rm2 == null)
        .put("removedByMerge", rm3 == null)
        .buildOrThrow();
  }

  private static ImmutableMap<String, Object> exerciseComputeRaces(Context ctx) {
    State state = ctx.state();
    Map<String, Object> delta = ctx.eventActions().stateDelta();
    Object racedComputeInsert =
        state.compute(
            "count",
            (k, v) -> {
              if (v == null) {
                state.put("count", "7");
              }
              return "8";
            });
    // Each retry shows in the live state before the delta reaches the session.
    Object liveAfterComputeInsert = state.get("count");
    Object racedComputeReplace =
        state.compute(
            "count",
            (k, v) -> {
              if (Integer.valueOf(8).equals(v)) {
                state.put("count", "9");
              }
              return "10";
            });
    Object liveAfterComputeReplace = state.get("count");
    Object racedPresent =
        state.computeIfPresent(
            "count",
            (k, v) -> {
              if (Integer.valueOf(10).equals(v)) {
                state.put("count", "11");
              }
              return "12";
            });
    Object liveAfterPresent = state.get("count");
    Object racedMerge =
        state.merge(
            "count",
            "14",
            (oldVal, newVal) -> {
              if (Integer.valueOf(12).equals(oldVal)) {
                state.put("count", "13");
              }
              return newVal;
            });
    Object liveAfterMerge = state.get("count");
    Object racedAbsent =
        state.computeIfAbsent(
            "note",
            k -> {
              state.put("note", "winner");
              return "loser";
            });
    Object racedAbsentDelta = delta.get("note");
    Object racedRemove =
        state.compute(
            "note",
            (k, v) -> {
              if ("winner".equals(v)) {
                state.put("note", "updated");
              }
              return null;
            });
    boolean liveNoteRemoved = !state.containsKey("note");
    return ImmutableMap.<String, Object>builder()
        .put("racedComputeInsert", racedComputeInsert)
        .put("liveAfterComputeInsert", liveAfterComputeInsert)
        .put("racedComputeReplace", racedComputeReplace)
        .put("liveAfterComputeReplace", liveAfterComputeReplace)
        .put("racedPresent", racedPresent)
        .put("liveAfterPresent", liveAfterPresent)
        .put("racedMerge", racedMerge)
        .put("liveAfterMerge", liveAfterMerge)
        .put("racedAbsent", racedAbsent)
        .put("racedAbsentDelta", racedAbsentDelta)
        .put("racedRemoveWasNull", racedRemove == null)
        .put("liveNoteRemoved", liveNoteRemoved)
        .buildOrThrow();
  }

  @Test
  public void stateSchema_objectWriteChecksOnceAndSharesConvertedCopyWithDelta() {
    Schema objSchema =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ImmutableMap.of("item", STATE_SCHEMA))
            .build();
    Node node =
        new TestNode(
            "n",
            (ctx, input) -> {
              ctx.state().put("item", ImmutableMap.of("count", "7"));
              return Flowable.just(
                  ctx.state().get("item") == ctx.eventActions().stateDelta().get("item"));
            });
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(objSchema, node));

    List<Event> events = session.turn(go());

    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output()).hasValue(true);
    assertThat(outputEvent.actions().stateDelta())
        .containsExactly("item", ImmutableMap.of("count", 7));
  }

  @Test
  public void stateSchema_aRecordPutThroughStateIsStoredInJsonFormInLiveStateAndDelta() {
    // Arrange
    Node node =
        new TestNode(
            "n",
            (ctx, input) -> {
              ctx.state().put("total", new Count(3));
              return Flowable.just(ctx.state().get("total"));
            });
    WorkflowSession session = new WorkflowSession(stateSchemaWorkflow(TYPED_STATE_SCHEMA, node));

    // Act
    List<Event> events = session.turn(go());

    // Assert
    ImmutableMap<String, Object> jsonForm = ImmutableMap.of("count", 3);
    Event outputEvent = outputEventAt(events, "wf@1/n@1");
    assertThat(outputEvent.output()).hasValue(jsonForm);
    assertThat(outputEvent.actions().stateDelta()).containsExactly("total", jsonForm);
    assertThat(session.state()).containsEntry("total", jsonForm);
  }

  @Test
  public void stateSchema_nullKeyOrValueThrowsNullPointerException() {
    WorkflowSession session =
        stateSession(ctx -> {
          assertThrows(NullPointerException.class, () -> ctx.state().put("count", null));
          assertThrows(NullPointerException.class, () -> ctx.state().put(null, 1));
          assertThrows(
              NullPointerException.class, () -> ctx.requireNodeState().checkStateValue(null, 1));
          return "ok";
        });

    List<Event> events = session.turn(go());

    assertThat(outputEventAt(events, "wf@1/n@1").output()).hasValue("ok");
    assertThat(session.state()).isEmpty();
  }

  @Test
  public void stateSchema_mismatchedAndNullConvertingPutsThrowAndWriteNothing() {
    WorkflowSession session = stateSession(WorkflowTest::exerciseRejectedPuts);

    List<Event> events = session.turn(go());

    Map<?, ?> out = (Map<?, ?>) outputEventAt(events, "wf@1/n@1").output().orElseThrow();
    assertThat(out.get("mismatch").toString())
        .startsWith("validation error: state key 'count' does not match its schema: ");
    assertThat(out.get("nullConv")).isEqualTo("validation error: state key 'note' is null.");
    assertThat(out.get("empty")).isEqualTo(true);
    assertThat(session.state()).isEmpty();
  }

  private static ImmutableMap<String, Object> exerciseRejectedPuts(Context ctx) {
    Content nullText = Content.fromParts(Part.fromText("null"));
    StateSchemaException mismatch =
        assertThrows(StateSchemaException.class, () -> ctx.state().put("count", "many"));
    StateSchemaException nullConv =
        assertThrows(StateSchemaException.class, () -> ctx.state().put("note", nullText));
    return ImmutableMap.of(
        "mismatch", mismatch.getMessage(),
        "nullConv", nullConv.getMessage(),
        "empty", ctx.state().isEmpty());
  }

  @Test
  public void stateSchema_colonKeysAndRemovalsThroughStatePassUnchecked() {
    WorkflowSession session =
        stateSession(ctx -> {
          ctx.state().put("count", 1);
          ctx.state().put("app:mode", "fast");
          ctx.state().put("owner:count", "many");
          ctx.state().remove("count");
          return "ok";
        });

    List<Event> events = session.turn(go());

    assertThat(session.state()).containsEntry("app:mode", "fast");
    assertThat(session.state()).containsEntry("owner:count", "many");
    assertThat(session.state()).doesNotContainKey("count");
    assertThat(outputEventAt(events, "wf@1/n@1").actions().stateDelta())
        .containsEntry("count", State.REMOVED);
  }

  private static WorkflowSession stateSession(Function<Context, Object> body) {
    return new WorkflowSession(
        stateSchemaWorkflow(
            STATE_SCHEMA, new TestNode("n", (ctx, input) -> Flowable.just(body.apply(ctx)))));
  }

  /** The target of a model tool that writes an undeclared state key. */
  public static final class StateTools {
    public static ImmutableMap<String, Object> writeOther(
        @Annotations.Schema(name = "toolContext") ToolContext toolContext) {
      toolContext.state().put("other", 1);
      return ImmutableMap.of("status", "ok");
    }

    private StateTools() {}
  }
}
