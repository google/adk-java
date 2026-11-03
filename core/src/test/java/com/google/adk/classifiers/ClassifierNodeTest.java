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

package com.google.adk.classifiers;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.agents.Context;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestNode;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.EdgesBuilder.RouteMap;
import com.google.adk.workflow.GraphValidationException;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeConfig;
import com.google.adk.workflow.RetryConfig;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Start;
import com.google.adk.workflow.Workflow;
import com.google.adk.workflow.WorkflowConfigurationException;
import com.google.adk.workflow.WorkflowDataException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifierNodeTest {

  private static final String APP = "app";
  private static final String USER = "user";
  private static final Choice TRIAGE =
      Choice.builder()
          .instructions("Route this ticket.")
          .criterion("billing", "Invoices or refunds")
          .criterion("technical", "Bugs")
          .build();

  private final Sink billing = new Sink("billing");
  private final Sink technical = new Sink("technical");

  @Test
  public void choice_routesToTheChosenLabelAndPassesTheInputThrough() {
    FixedClassifier classifier = new FixedClassifier(choice("technical"));

    Run run = run(triageWorkflow(triageNode(classifier, /* answerKey= */ null)));

    assertThat(run.error()).isNull();
    assertThat(billing.inputs).isEmpty();
    Content received = (Content) technical.inputs.get(0);
    assertThat(received.text()).isEqualTo("go");
    assertThat(classifier.requests)
        .containsExactly(new ClassifyRequest(received, ImmutableMap.of("route", TRIAGE)));
  }

  @Test
  public void choice_emitsOneEventWithTheOutputTheRouteAndTheAnswer() {
    ClassifierNode node = triageNode(new FixedClassifier(choice("billing")), "triage_answer");

    Run run = run(triageWorkflow(node));

    ImmutableList<Event> events =
        run.events().stream()
            .filter(event -> event.nodeName().equals(Optional.of("triage")))
            .collect(toImmutableList());
    assertThat(events).hasSize(1);
    Event event = events.get(0);
    assertThat(((Content) event.output().get()).text()).isEqualTo("go");
    assertThat(event.actions().route()).hasValue(ImmutableList.of(new Route.Tag("billing")));
    assertThat(event.actions().stateDelta().keySet()).containsExactly("triage_answer");
  }

  @Test
  public void binary_probabilityAboveTheThreshold_routesTrue() {
    assertThat(routeTaken(gate(0.7).threshold(0.6).build())).isTrue();
  }

  @Test
  public void binary_probabilityAtTheThreshold_routesTrue() {
    assertThat(routeTaken(gate(0.6).threshold(0.6).build())).isTrue();
  }

  @Test
  public void binary_probabilityBelowTheThreshold_routesFalse() {
    assertThat(routeTaken(gate(0.59).threshold(0.6).build())).isFalse();
  }

  @Test
  public void binary_withoutAThreshold_routesTrueFromOneHalf() {
    assertThat(routeTaken(gate(0.5).build())).isTrue();
    assertThat(routeTaken(gate(0.49).build())).isFalse();
  }

  @Test
  public void answerKey_storesTheAnswerInSessionStateInItsJsonForm() {
    Run run = run(triageWorkflow(triageNode(new FixedClassifier(choice("billing")), "answer")));

    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("type", "choice");
    expected.put("choice", "billing");
    expected.put("probabilities", ImmutableMap.of("billing", 0.9, "technical", 0.1));
    expected.put("confidence", null);
    assertThat(run.session().state()).containsExactly("answer", expected);
  }

  @Test
  public void noAnswerKey_leavesStateUntouched() {
    Run run = run(triageWorkflow(triageNode(new FixedClassifier(choice("billing")), null)));

    assertThat(run.session().state()).isEmpty();
  }

  @Test
  public void jsonMapInput_isClassifiedAsAJsonObjectAndPassedThrough() {
    FixedClassifier classifier = new FixedClassifier(choice("billing"));
    ImmutableMap<String, Object> ticket =
        ImmutableMap.of("subject", "x", "tags", ImmutableList.of("a", "b"));
    Sink sink = new Sink("sink");

    var unused = run(sourcedWorkflow(ticket, triageNode(classifier, null), sink));

    assertThat(sink.inputs).containsExactly(ticket);
    assertThat(classifier.requests.get(0).input())
        .isEqualTo(new ClassifierInput.JsonObject(ticket));
  }

  @Test
  public void jsonListInput_isClassifiedAsAJsonArrayAndPassedThrough() {
    FixedClassifier classifier = new FixedClassifier(choice("billing"));
    ImmutableList<Object> items = ImmutableList.of("first", ImmutableMap.of("k", 1));
    Sink sink = new Sink("sink");

    var unused = run(sourcedWorkflow(items, triageNode(classifier, null), sink));

    assertThat(sink.inputs).containsExactly(items);
    assertThat(classifier.requests.get(0).input()).isEqualTo(new ClassifierInput.JsonArray(items));
  }

  @Test
  public void recordInput_isClassifiedAsItsJsonObjectWithJsonScalars() {
    FixedClassifier classifier = new FixedClassifier(choice("billing"));
    Invoice invoice =
        new Invoice("Refund", new BigDecimal("12.50"), Instant.ofEpochSecond(1_700_000_000L));
    Sink sink = new Sink("sink");

    Run run = run(sourcedWorkflow(invoice, triageNode(classifier, null), sink));

    assertThat(run.error()).isNull();
    assertThat(sink.inputs).containsExactly(invoice);
    Map<String, ?> input =
        ((ClassifierInput.JsonObject) classifier.requests.get(0).input()).value();
    assertThat(input).containsEntry("subject", "Refund");
    assertThat(input).containsEntry("amount", 12.5);
    assertThat(input).containsEntry("due", "2023-11-14T22:13:20Z");
  }

  @Test
  public void missingInput_failsTheNodeWithWorkflowDataException() {
    Throwable error = failureForInput(null);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("ClassifierNode 'triage' received no input to classify.");
  }

  @Test
  public void scalarInput_failsTheNodeWithWorkflowDataException() {
    Throwable error = failureForInput(42);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "ClassifierNode 'triage' requires a String, Map, List, Content, or object that the JSON"
                + " mapper writes as an object or array, but got Integer.");
  }

  @Test
  public void mapInputWithNonJsonValue_failsTheNodeWithWorkflowDataException() {
    Throwable error = failureForInput(ImmutableMap.of("x", Double.NaN));

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("ClassifierNode 'triage' received an input that is not JSON content.");
    assertThat(error).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void listInputWithNonJsonValue_failsTheNodeWithWorkflowDataException() {
    Throwable error = failureForInput(ImmutableList.of(Double.NaN));

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("ClassifierNode 'triage' received an input that is not JSON content.");
    assertThat(error).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void inputTheMapperCannotWrite_failsTheNodeWithTheMapperErrorAsCause() {
    Throwable error = failureForInput(new Object());

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("ClassifierNode 'triage' could not write its input as JSON.");
    assertThat(error).hasCauseThat().isInstanceOf(JsonProcessingException.class);
  }

  @Test
  public void labelOutsideTheQuestion_failsTheNodeWithoutQuotingIt() {
    Throwable error = failureForAnswer(choice("billing").toBuilder().choice("sales").build());

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "Classifier for node 'triage' chose a label that is not one of its question's labels.");
  }

  @Test
  public void binaryAnswerToAChoice_failsTheNode() {
    Throwable error = failureForAnswer(BinaryAnswer.builder().probability(0.5).build());

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Classifier for node 'triage' returned a BinaryAnswer to a Choice question.");
  }

  @Test
  public void scoreAnswerToAChoice_failsTheNode() {
    Throwable error =
        failureForAnswer(
            ScoreAnswer.builder()
                .score(1.0)
                .probabilities(ImmutableMap.of(0, 0.0, 1, 1.0))
                .build());

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Classifier for node 'triage' returned a ScoreAnswer to a Choice question.");
  }

  @Test
  public void refusal_failsTheNode() {
    Throwable error = failureForAnswer(RefusalAnswer.INSTANCE);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "Classifier for node 'triage' declined to answer, so the node has no route to take.");
  }

  @Test
  public void noAnswer_failsTheNode() {
    Throwable error = failureForAnswer(null);

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("Classifier for node 'triage' returned no answer.");
  }

  @Test
  public void workflowDataException_isRetriedByName() {
    assertThat(classifierCallsUnder(instantRetry().exceptions("WorkflowDataException").build()))
        .isEqualTo(2);
  }

  @Test
  public void workflowDataException_isRetriedByClass() {
    assertThat(
            classifierCallsUnder(
                instantRetry().exceptionClasses(WorkflowDataException.class).build()))
        .isEqualTo(2);
  }

  @Test
  public void edgeForARouteTheNodeNeverEmits_failsGraphValidation() {
    ClassifierNode node = triageNode(new FixedClassifier(null), null);

    GraphValidationException error =
        assertThrows(
            GraphValidationException.class,
            () ->
                routedWorkflow(
                    node,
                    r -> {
                      r.on("biling").then(billing);
                      r.on("technical").then(technical);
                    }));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "Graph validation failed. ClassifierNode 'triage' has edges for routes it never"
                + " emits: [\"biling\"]. Its routes are [\"billing\", \"technical\"].");
  }

  @Test
  public void labelWithoutAnEdge_failsGraphValidation() {
    ClassifierNode node = triageNode(new FixedClassifier(null), null);

    GraphValidationException error =
        assertThrows(
            GraphValidationException.class,
            () -> routedWorkflow(node, r -> r.on("billing").then(billing)));

    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "Graph validation failed. ClassifierNode 'triage' has no edge for routes"
                + " [\"technical\"], so the branch would end when the classifier picks one. Add an"
                + " edge for each, or a default or unconditional edge.");
  }

  @Test
  public void binaryWithStringRoutes_failsGraphValidation() {
    ClassifierNode gate =
        ClassifierNode.builder()
            .name("gate")
            .classifier(new FixedClassifier(null))
            .question(Binary.builder().build())
            .build();

    GraphValidationException error =
        assertThrows(
            GraphValidationException.class,
            () ->
                routedWorkflow(
                    gate,
                    r -> {
                      r.on("true").then(billing);
                      r.on("false").then(technical);
                    }));

    assertThat(error)
        .hasMessageThat()
        .contains("never emits: [\"false\", \"true\"]. Its routes are [false, true].");
  }

  @Test
  public void defaultEdge_coversLabelsWithoutAnEdge() {
    ClassifierNode node = triageNode(new FixedClassifier(null), null);

    var unused =
        routedWorkflow(
            node,
            r -> {
              r.on("billing").then(billing);
              r.otherwise(technical);
            });
  }

  @Test
  public void unconditionalEdge_coversLabelsWithoutAnEdge() {
    ClassifierNode node = triageNode(new FixedClassifier(null), null);

    var unused =
        Workflow.builder()
            .name("wf")
            .edges(
                g -> {
                  g.fromStart().then(node);
                  g.from(node).route(r -> r.on("billing").then(billing));
                  g.from(node).then(technical);
                })
            .build();
  }

  @Test
  public void onlyUnconditionalEdges_skipRouteValidation() {
    ClassifierNode node = triageNode(new FixedClassifier(null), null);

    var unused =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, node), new Edge(node, billing))
            .build();
  }

  @Test
  public void scoreQuestion_throwsWorkflowConfigurationException() {
    ClassifierNode.Builder builder =
        ClassifierNode.builder()
            .name("rate")
            .classifier(new FixedClassifier(null))
            .question(Score.builder().criteria(ImmutableList.of("low", "high")).build());

    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, builder::build);

    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "ClassifierNode 'rate' cannot route on a Score question; use a Choice or a Binary.");
  }

  @Test
  public void thresholdWithChoiceQuestion_throwsWorkflowConfigurationException() {
    ClassifierNode.Builder builder =
        ClassifierNode.builder()
            .name("triage")
            .classifier(new FixedClassifier(null))
            .question(TRIAGE)
            .threshold(0.5);

    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, builder::build);

    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "ClassifierNode 'triage' takes a threshold only with a Binary question; a Choice"
                + " always routes to the label the classifier chose.");
  }

  @Test
  public void thresholdOutsideZeroToOne_throwsWorkflowConfigurationException() {
    for (double threshold : new double[] {-0.1, 1.5, Double.NaN}) {
      ClassifierNode.Builder builder =
          ClassifierNode.builder()
              .name("gate")
              .classifier(new FixedClassifier(null))
              .question(Binary.builder().build())
              .threshold(threshold);

      WorkflowConfigurationException error =
          assertThrows(WorkflowConfigurationException.class, builder::build);

      assertWithMessage("threshold %s", threshold)
          .that(error)
          .hasMessageThat()
          .isEqualTo("ClassifierNode 'gate' needs a threshold in [0, 1].");
    }
  }

  @Test
  public void defaultRouteSentinelAsALabel_throwsWorkflowConfigurationException() {
    Choice withSentinel =
        Choice.builder().criterion("billing").criterion(Route.DEFAULT_ROUTE_SENTINEL).build();

    ClassifierNode.Builder builder =
        ClassifierNode.builder()
            .name("triage")
            .classifier(new FixedClassifier(null))
            .question(withSentinel);

    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, builder::build);

    assertThat(error).hasMessageThat().contains("reserved for the default route");
  }

  @Test
  public void invalidNodeName_throwsIllegalArgumentException() {
    ClassifierNode.Builder builder =
        ClassifierNode.builder().name("a.b").classifier(new FixedClassifier(null)).question(TRIAGE);

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void builder_setsEveryProperty() {
    FixedClassifier classifier = new FixedClassifier(null);
    NodeConfig config =
        NodeConfig.builder().retryConfig(RetryConfig.builder().maxAttempts(2).build()).build();
    Schema schema = Schema.builder().type(Type.Known.STRING).build();
    Binary question = Binary.builder().build();

    ClassifierNode node =
        ClassifierNode.builder()
            .name("gate")
            .classifier(classifier)
            .question(question)
            .threshold(0.7)
            .answerKey("gate_answer")
            .description("Gates risky tickets.")
            .config(config)
            .inputSchema(schema)
            .outputSchema(schema)
            .build();

    assertThat(node.name()).isEqualTo("gate");
    assertThat(node.classifier()).isSameInstanceAs(classifier);
    assertThat(node.question()).isEqualTo(question);
    assertThat(node.threshold()).isEqualTo(0.7);
    assertThat(node.answerKey()).hasValue("gate_answer");
    assertThat(node.description()).isEqualTo("Gates risky tickets.");
    assertThat(node.config()).isEqualTo(config);
    assertThat(node.inputSchema()).hasValue(schema);
    assertThat(node.outputSchema()).hasValue(schema);
  }

  @Test
  public void builder_unsetOptionalProperties_takeTheirDefaults() {
    ClassifierNode node = triageNode(new FixedClassifier(null), null);

    assertThat(node.threshold()).isEqualTo(0.5);
    assertThat(node.answerKey()).isEmpty();
    assertThat(node.description()).isEmpty();
    assertThat(node.rerunOnResume()).isFalse();
    assertThat(node.waitForOutput()).isFalse();
    assertThat(node.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(node.inputSchema()).isEmpty();
    assertThat(node.outputSchema()).isEmpty();
  }

  @Test
  public void builder_missingRequiredProperty_throwsIllegalStateException() {
    FixedClassifier classifier = new FixedClassifier(null);
    ClassifierNode.Builder withoutName =
        ClassifierNode.builder().classifier(classifier).question(TRIAGE);
    ClassifierNode.Builder withoutClassifier =
        ClassifierNode.builder().name("triage").question(TRIAGE);
    ClassifierNode.Builder withoutQuestion =
        ClassifierNode.builder().name("triage").classifier(classifier);

    assertThrows(IllegalStateException.class, withoutName::build);
    assertThrows(IllegalStateException.class, withoutClassifier::build);
    assertThrows(IllegalStateException.class, withoutQuestion::build);
  }

  private static ChoiceAnswer choice(String label) {
    return ChoiceAnswer.builder()
        .choice(label)
        .probabilities(
            label.equals("billing")
                ? ImmutableMap.of("billing", 0.9, "technical", 0.1)
                : ImmutableMap.of("billing", 0.1, "technical", 0.9))
        .build();
  }

  /** Returns a builder for a binary gate whose classifier answers {@code probability}. */
  private static ClassifierNode.Builder gate(double probability) {
    return ClassifierNode.builder()
        .name("gate")
        .classifier(new FixedClassifier(BinaryAnswer.builder().probability(probability).build()))
        .question(Binary.builder().instructions("Is this risky?").build());
  }

  /** Runs {@code gate} with a true and a false route and returns the route it took. */
  private static boolean routeTaken(ClassifierNode gate) {
    Sink yes = new Sink("yes");
    Sink no = new Sink("no");
    var unused =
        run(
            Workflow.builder()
                .name("wf")
                .edges(
                    g -> {
                      g.fromStart().then(gate);
                      g.from(gate)
                          .route(
                              r -> {
                                r.on(true).then(yes);
                                r.on(false).then(no);
                              });
                    })
                .build());
    assertThat(yes.inputs.size() + no.inputs.size()).isEqualTo(1);
    return !yes.inputs.isEmpty();
  }

  private static ClassifierNode triageNode(BaseClassifier classifier, @Nullable String answerKey) {
    return ClassifierNode.builder()
        .name("triage")
        .classifier(classifier)
        .question(TRIAGE)
        .answerKey(answerKey)
        .build();
  }

  /** Returns START -> {@code node}, with {@code billing} and {@code technical} routed to sinks. */
  private Workflow triageWorkflow(ClassifierNode node) {
    return routedWorkflow(
        node,
        r -> {
          r.on("billing").then(billing);
          r.on("technical").then(technical);
        });
  }

  private static Workflow routedWorkflow(ClassifierNode node, Consumer<RouteMap> routes) {
    return Workflow.builder()
        .name("wf")
        .edges(
            g -> {
              g.fromStart().then(node);
              g.from(node).route(routes);
            })
        .build();
  }

  /** Returns {@link #sourcedWorkflow(Object, ClassifierNode, Node)} with a new sink. */
  private static Workflow sourcedWorkflow(@Nullable Object input, ClassifierNode node) {
    return sourcedWorkflow(input, node, new Sink("sink"));
  }

  /**
   * Returns START -> source -> {@code node} -> {@code sink}, where source outputs {@code input}, or
   * nothing if it is null.
   */
  private static Workflow sourcedWorkflow(@Nullable Object input, ClassifierNode node, Node sink) {
    Node source =
        new TestNode(
            "src", (context, nodeInput) -> input == null ? Flowable.empty() : Flowable.just(input));
    return Workflow.builder()
        .name("wf")
        .edges(g -> g.fromStart().chain(source, node, sink))
        .build();
  }

  /**
   * Runs the triage node with a classifier that answers {@code answer}, and returns the resulting
   * failure.
   */
  private static Throwable failureForAnswer(@Nullable Answer answer) {
    return run(sourcedWorkflow("x", triageNode(new FixedClassifier(answer), null))).error();
  }

  /**
   * Runs the triage node on {@code input}, or on no input when {@code input} is null, and returns
   * the resulting failure.
   */
  private static Throwable failureForInput(@Nullable Object input) {
    return run(sourcedWorkflow(input, triageNode(new FixedClassifier(choice("billing")), null)))
        .error();
  }

  /**
   * Runs a triage node whose classifier always refuses, under {@code policy}, and counts the
   * classifier calls.
   */
  private int classifierCallsUnder(RetryConfig policy) {
    FixedClassifier classifier = new FixedClassifier(RefusalAnswer.INSTANCE);
    ClassifierNode node =
        ClassifierNode.builder()
            .name("triage")
            .classifier(classifier)
            .question(TRIAGE)
            .config(NodeConfig.builder().retryConfig(policy).build())
            .build();

    Throwable error = run(triageWorkflow(node)).error();

    assertThat(error).isInstanceOf(WorkflowDataException.class);
    return classifier.requests.size();
  }

  private static RetryConfig.Builder instantRetry() {
    return RetryConfig.builder().maxAttempts(2).initialDelay(Duration.ZERO).jitter(0);
  }

  /** Runs one turn of an app rooted on {@code workflow}, saying "go". */
  private static Run run(Workflow workflow) {
    Runner runner =
        Runner.builder().app(App.builder().name(APP).rootNode(workflow).build()).build();
    String sessionId = runner.sessionService().createSession(APP, USER).blockingGet().id();
    List<Event> events = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    runner
        .runAsync(
            USER, sessionId, Content.builder().role("user").parts(Part.fromText("go")).build())
        .timeout(30, SECONDS)
        .blockingSubscribe(events::add, error::set);
    Session session =
        runner.sessionService().getSession(APP, USER, sessionId, Optional.empty()).blockingGet();
    return new Run(ImmutableList.copyOf(events), error.get(), session);
  }

  /** The events a turn emitted, its failure or null, and its session afterwards. */
  private record Run(ImmutableList<Event> events, @Nullable Throwable error, Session session) {}

  private record Invoice(String subject, BigDecimal amount, Instant due) {}

  /**
   * Answers every question with {@code answer}, or with nothing if it is null, and records calls.
   */
  private static final class FixedClassifier extends BaseClassifier {
    final List<ClassifyRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final @Nullable Answer answer;

    FixedClassifier(@Nullable Answer answer) {
      super("fixed");
      this.answer = answer;
    }

    @Override
    public Single<ClassifierResponse> classify(ClassifyRequest request) {
      requests.add(request);
      ImmutableMap<String, Answer> answers =
          answer == null
              ? ImmutableMap.of()
              : request.questions().keySet().stream()
                  .collect(toImmutableMap(Function.identity(), unused -> answer));
      return Single.just(ClassifierResponse.builder().answers(answers).build());
    }
  }

  /** Records the inputs it receives and outputs nothing. */
  private static final class Sink implements Node {
    final List<@Nullable Object> inputs = Collections.synchronizedList(new ArrayList<>());
    private final String name;

    Sink(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      inputs.add(nodeInput);
      return Flowable.empty();
    }
  }
}
