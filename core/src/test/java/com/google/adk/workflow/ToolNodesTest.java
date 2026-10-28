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

import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.LONG_RUNNING_CALL;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.callContent;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.newEvent;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.PluginManager;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ToolNodesTest {

  @Test
  public void asNode_withOnlyATool_usesItsNameAndDescriptionAndDefaults() {
    Node node = ToolNodes.asNode(FakeTool.echo("lookup"));

    assertThat(node.name()).isEqualTo("lookup");
    assertThat(node.description()).isEqualTo("A fake tool.");
    assertThat(node.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(node.inputSchema()).isEmpty();
    assertThat(node.outputSchema()).isEmpty();
  }

  @Test
  public void asNode_withAName_overridesTheToolsName() {
    Node node = ToolNodes.asNode(FakeTool.echo("lookup"), "custom");

    assertThat(node.name()).isEqualTo("custom");
    assertThat(node.description()).isEqualTo("A fake tool.");
  }

  @Test
  public void asNode_withANameAndConfig_usesThem() {
    NodeConfig config = NodeConfig.builder().timeout(Duration.ofSeconds(5)).build();

    Node node = ToolNodes.asNode(FakeTool.echo("lookup"), "custom", config);

    assertThat(node.name()).isEqualTo("custom");
    assertThat(node.config()).isEqualTo(config);
  }

  @Test
  public void asNode_toolWithoutDescription_givesAnEmptyDescription() {
    BaseTool tool = new BaseTool("lookup", /* description= */ null) {};

    assertThat(ToolNodes.asNode(tool).description()).isEmpty();
  }

  @Test
  public void runNode_mapInput_passesItAsTheToolsArguments() {
    ImmutableMap<String, Object> args = ImmutableMap.of("param_a", 1, "param_b", "value");

    assertThat(run(FakeTool.echo("mock_tool"), args)).isEqualTo(args);
  }

  @Test
  public void runNode_jsonObjectText_parsesItIntoArguments() {
    Object output = run(FakeTool.echo("mock_tool"), "  {\"param_a\": 1, \"param_b\": \"value\"}  ");

    assertThat(output).isEqualTo(ImmutableMap.of("param_a", 1, "param_b", "value"));
  }

  @Test
  public void runNode_nullInput_callsTheToolWithNoArguments() {
    assertThat(run(FakeTool.echo("mock_tool"), null)).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void runNode_blankText_callsTheToolWithNoArguments() {
    assertThat(run(FakeTool.echo("mock_tool"), " \n\t")).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void runNode_jsonObjectContent_readsItsTextWithoutThoughts() {
    Content content =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("reasoning").thought(true).build(), Part.fromText("{\"x\": 1}"))
            .build();

    assertThat(run(FakeTool.echo("mock_tool"), content)).isEqualTo(ImmutableMap.of("x", 1));
  }

  @Test
  public void runNode_textThatIsNotJson_failsTheNode() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> run(FakeTool.echo("mock_tool"), "not a json"));

    assertThat(thrown)
        .hasMessageThat()
        .contains("The input to tool node 'mock_tool' must be a dictionary of tool arguments");
    assertThat(thrown).hasMessageThat().contains("but got text that is not a JSON object");
    assertThat(thrown).hasMessageThat().doesNotContain("not a json");
  }

  @Test
  public void runNode_jsonArrayText_failsTheNode() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> run(FakeTool.echo("mock_tool"), "[1, 2, 3]"));

    assertThat(thrown).hasMessageThat().contains("but got text that is not a JSON object");
    assertThat(thrown).hasMessageThat().doesNotContain("[1, 2, 3]");
  }

  @Test
  public void runNode_objectTextWithTrailingText_failsTheNode() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> run(FakeTool.echo("mock_tool"), "{\"city\": \"Paris\"} and more"));

    assertThat(thrown).hasMessageThat().contains("but got text that is not a JSON object");
    assertThat(thrown).hasMessageThat().doesNotContain("Paris");
  }

  @Test
  public void runNode_recordInput_passesItsPropertiesAsArguments() {
    FakeTool tool =
        FakeTool.echo("weather", ImmutableList.of("city", "units"), ImmutableList.of("city"));

    assertThat(run(tool, new City("Paris", "metric")))
        .isEqualTo(ImmutableMap.of("city", "Paris", "units", "metric"));
  }

  @Test
  public void runNode_objectWithoutProperties_failsNamingItsClass() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> run(FakeTool.echo("mock_tool"), new Object() {}));

    assertThat(thrown).hasMessageThat().contains("but got com.google.adk.workflow.ToolNodesTest$");
  }

  @Test
  public void runNode_nonMapValue_failsTheNode() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> run(FakeTool.echo("mock_tool"), 42));

    assertThat(thrown).hasMessageThat().contains("but got Integer");
  }

  @Test
  public void runNode_toolWithOneParameter_takesStrippedTextAsIt() {
    Object output = run(FakeTool.echo("reader", "path"), " /tmp/a.txt ");

    assertThat(output).isEqualTo(ImmutableMap.of("path", "/tmp/a.txt"));
  }

  @Test
  public void runNode_toolWithOneParameter_takesANonMapValueAsIt() {
    Object output = run(FakeTool.echo("counter", "count"), 42);

    assertThat(output).isEqualTo(ImmutableMap.of("count", 42));
  }

  @Test
  public void runNode_toolWithOneParameter_takesJsonNullTextAsText() {
    Object output = run(FakeTool.echo("writer", "text"), "null");

    assertThat(output).isEqualTo(ImmutableMap.of("text", "null"));
  }

  @Test
  public void runNode_toolWithOneRequiredParameter_takesANonMapValueAsIt() {
    FakeTool tool =
        FakeTool.echo("weather", ImmutableList.of("city", "units"), ImmutableList.of("city"));

    assertThat(run(tool, "Paris")).isEqualTo(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void runNode_toolWithTwoParameters_rejectsANonMapValue() {
    FakeTool tool = FakeTool.echo("lookup", ImmutableList.of("a", "b"), ImmutableList.of());

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> run(tool, "scalar"));

    assertThat(thrown).hasMessageThat().contains("must be a dictionary");
  }

  @Test
  public void runNode_toolWithRawJsonSchema_readsItsParameters() {
    // MCP tools declare their parameters as a raw JSON schema.
    FakeTool tool =
        FakeTool.withRawSchema(
            "weather",
            ImmutableMap.of(
                "type",
                "object",
                "properties",
                ImmutableMap.of(
                    "city", ImmutableMap.of("type", "string"),
                    "units", ImmutableMap.of("type", "string")),
                "required",
                ImmutableList.of("city")));

    assertThat(run(tool, "Paris")).isEqualTo(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void runNode_toolWithRawAndTypedSchemas_readsTheRawOne() {
    Schema typed =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "a", Schema.builder().type("STRING").build(),
                    "b", Schema.builder().type("STRING").build()))
            .build();
    FunctionDeclaration declaration =
        FunctionDeclaration.builder()
            .name("weather")
            .parameters(typed)
            .parametersJsonSchema(
                ImmutableMap.of(
                    "type",
                    "object",
                    "properties",
                    ImmutableMap.of("city", ImmutableMap.of("type", "string"))))
            .build();
    FakeTool tool = new FakeTool("weather", declaration, (args, toolContext) -> args);

    assertThat(run(tool, "Paris")).isEqualTo(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void runNode_requiredParametersMissing_readsOnlyThoseFromState() {
    FakeTool tool =
        FakeTool.echo("weather", ImmutableList.of("city", "units"), ImmutableList.of("city"));
    Context context = context();
    context.invocationContext().session().state().put("city", "Paris");
    context.invocationContext().session().state().put("units", "fahrenheit");

    assertThat(run(tool, context, null)).isEqualTo(ImmutableMap.of("city", "Paris"));
  }

  @Test
  public void runNode_requiredParameterInNeitherInputNorState_isLeftOut() {
    FakeTool tool = FakeTool.echo("weather", "city");

    assertThat(run(tool, ImmutableMap.of())).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void runNode_argumentInInputAndState_usesTheInput() {
    FakeTool tool = FakeTool.echo("weather", "city");
    Context context = context();
    context.invocationContext().session().state().put("city", "Paris");

    assertThat(run(tool, context, ImmutableMap.of("city", "Rome")))
        .isEqualTo(ImmutableMap.of("city", "Rome"));
  }

  @Test
  public void runNode_functionTool_readsAMissingRequiredArgumentFromState() {
    FunctionTool tool = FunctionTool.create(Functions.class, "forecast");
    Context context = context();
    context.invocationContext().session().state().put("city", "Paris");

    assertThat(run(tool, context, null)).isEqualTo(ImmutableMap.of("forecast", "Sunny in Paris"));
  }

  @Test
  public void runNode_functionToolRequiringConfirmation_outputsItsPlaceholderWithoutRunning() {
    FunctionTool tool =
        FunctionTool.create(Functions.class, "forecast", /* requireConfirmation= */ true);
    Context context = context();

    Object output = run(tool, context, "Rome");

    assertThat(output)
        .isEqualTo(
            ImmutableMap.of(
                "error", "This tool call requires confirmation, please approve or reject."));
    assertThat(context.eventActions().requestedToolConfirmations()).isEmpty();
  }

  @Test
  public void nodeRunner_toolWritesState_putsTheDeltaOnTheOutputEvent() {
    FakeTool tool =
        FakeTool.running(
            "writer",
            (args, toolContext) -> {
              toolContext.state().put("color", "blue");
              return ImmutableMap.of("status", "ok");
            });

    Event event = Iterables.getOnlyElement(runAsRoot(ToolNodes.asNode(tool)));

    assertThat(event.output()).hasValue(ImmutableMap.of("status", "ok"));
    assertThat(event.actions().stateDelta()).containsExactly("color", "blue");
  }

  @Test
  public void runNode_toolSavesArtifact_keepsTheArtifactDeltaOnTheNodeContext() {
    FakeTool tool =
        FakeTool.running(
            "saver",
            (args, toolContext) -> {
              toolContext.saveArtifact("report.txt", Part.fromText("v1")).blockingAwait();
              return ImmutableMap.of("status", "saved");
            });
    Context context = context();

    Object output = run(tool, context, null);

    assertThat(output).isEqualTo(ImmutableMap.of("status", "saved"));
    assertThat(context.eventActions().artifactDelta()).containsExactly("report.txt", 0);
  }

  @Test
  public void nodeRunner_toolWritesStateThenFails_putsTheDeltaOnTheErrorEvent() {
    FakeTool tool =
        FakeTool.running(
            "writer",
            (args, toolContext) -> {
              toolContext.state().put("color", "blue");
              throw new IllegalStateException("transient failure");
            });

    Event event = Iterables.getOnlyElement(runAsRoot(ToolNodes.asNode(tool)));

    assertThat(event.errorMessage()).hasValue("transient failure");
    assertThat(event.actions().stateDelta()).containsExactly("color", "blue");
  }

  @Test
  public void runNode_functionToolReturningNothing_sendsOnlyAnEventWithoutOutput() {
    FunctionTool tool = FunctionTool.create(Functions.class, "record");

    Object emitted = run(tool, "a note");

    assertThat(emitted).isInstanceOf(Event.class);
    assertThat(((Event) emitted).output()).isEmpty();
  }

  @Test
  public void runNode_functionToolReturningNothingThatNeedsConfirmation_outputsItsPlaceholder() {
    FunctionTool tool =
        FunctionTool.create(Functions.class, "record", /* requireConfirmation= */ true);

    assertThat(run(tool, "a note"))
        .isEqualTo(
            ImmutableMap.of(
                "error", "This tool call requires confirmation, please approve or reject."));
  }

  @Test
  public void runNode_toolDeclaringAnObjectResultReturnsAnEmptyMap_outputsIt() {
    FakeTool tool =
        new FakeTool(
            "lookup",
            FunctionDeclaration.builder()
                .name("lookup")
                .response(Schema.builder().type("OBJECT").build())
                .build(),
            (args, toolContext) -> ImmutableMap.of());

    assertThat(run(tool, null)).isEqualTo(ImmutableMap.of());
  }

  @Test
  public void resume_afterALaterInterrupt_doesNotRunAToolDeclaredToReturnNothingAgain() {
    AtomicInteger calls = new AtomicInteger();
    FakeTool tool =
        new FakeTool(
            "notify",
            FunctionDeclaration.builder()
                .name("notify")
                .response(Schema.builder().type("NULL").build())
                .build(),
            (args, toolContext) -> {
              calls.incrementAndGet();
              return ImmutableMap.of();
            });
    Node arguments = emitter("arguments", ImmutableMap.of());
    Node toolNode = ToolNodes.asNode(tool);
    Node asker = new TestNode("asker", (context, input) -> Flowable.just(interrupt("ask-1")));
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, arguments),
                new Edge(arguments, toolNode),
                new Edge(toolNode, asker),
                new Edge(asker, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask-1", ImmutableMap.of("result", "ok")));

    assertThat(calls.get()).isEqualTo(1);
    assertThat(replyInput(events)).isEqualTo("ok");
  }

  @Test
  public void runNode_toolRequestsModelOnlyActions_dropsThem() {
    ImmutableMap<String, Object> placeholder = ImmutableMap.of("error", "Needs confirmation.");
    FakeTool tool =
        FakeTool.running(
            "handoff",
            (args, toolContext) -> {
              toolContext.requestConfirmation("Approve?");
              toolContext.eventActions().setTransferToAgent("elsewhere");
              toolContext.eventActions().setSkipSummarization(true);
              return placeholder;
            });
    Context context = context();

    Object output = run(tool, context, null);

    assertThat(output).isEqualTo(placeholder);
    EventActions actions = context.eventActions();
    assertThat(actions.requestedToolConfirmations()).isEmpty();
    assertThat(actions.transferToAgent()).isEmpty();
    assertThat(actions.skipSummarization()).isEmpty();
  }

  @Test
  public void runNode_twoRuns_giveTheToolAFreshFunctionCallIdEachTime() {
    List<String> seenIds = new ArrayList<>();
    FakeTool tool =
        FakeTool.running(
            "record",
            (args, toolContext) -> {
              seenIds.add(toolContext.functionCallId().orElse(""));
              return ImmutableMap.of();
            });
    Node node = ToolNodes.asNode(tool);
    Context context = context();

    Object unusedFirst = node.runNode(context, null).blockingSingle();
    Object unusedSecond = node.runNode(context, null).blockingSingle();

    assertThat(seenIds).hasSize(2);
    assertThat(seenIds.get(0)).isNotEmpty();
    assertThat(seenIds.get(1)).isNotEqualTo(seenIds.get(0));
  }

  @Test
  public void runNode_agentTool_runsTheAgentOnTheRequestAndOutputsItsReply() {
    TestLlm llm = createTestLlm(createTextLlmResponse("Paris is sunny."));
    AgentTool tool = AgentTool.create(createTestAgentBuilder(llm).name("forecaster").build());

    Object output = run(tool, "Weather in Paris?");

    assertThat(output).isEqualTo(ImmutableMap.of("result", "Paris is sunny."));
    assertThat(Iterables.getLast(llm.getLastRequest().contents()).text())
        .isEqualTo("Weather in Paris?");
  }

  @Test
  public void runNode_runsThePluginsToolCallbacksAroundTheCall() {
    RecordingPlugin plugin = RecordingPlugin.silent();

    Object output = run(FakeTool.echo("lookup"), context(plugin), ImmutableMap.of("city", "Paris"));

    assertThat(output).isEqualTo(ImmutableMap.of("city", "Paris"));
    assertThat(plugin.calls)
        .containsExactly("before lookup {city=Paris}", "after lookup {city=Paris}")
        .inOrder();
  }

  @Test
  public void runNode_pluginBeforeAnswer_replacesTheToolCall() {
    List<Map<String, Object>> toolCalls = new ArrayList<>();
    FakeTool tool =
        FakeTool.running(
            "lookup",
            (args, toolContext) -> {
              toolCalls.add(args);
              return args;
            });
    RecordingPlugin plugin = RecordingPlugin.answeringBefore(ImmutableMap.of("cached", true));

    Object output = run(tool, context(plugin), ImmutableMap.of("city", "Paris"));

    assertThat(output).isEqualTo(ImmutableMap.of("cached", true));
    assertThat(toolCalls).isEmpty();
    assertThat(plugin.calls).contains("after lookup {cached=true}");
  }

  @Test
  public void runNode_pluginAfterAnswer_replacesTheOutput() {
    RecordingPlugin plugin = RecordingPlugin.answeringAfter(ImmutableMap.of("redacted", true));

    Object output = run(FakeTool.echo("lookup"), context(plugin), ImmutableMap.of("city", "Paris"));

    assertThat(output).isEqualTo(ImmutableMap.of("redacted", true));
  }

  @Test
  public void runNode_pluginErrorAnswer_becomesTheOutput() {
    FakeTool tool =
        FakeTool.running(
            "lookup",
            (args, toolContext) -> {
              throw new IllegalStateException("no data");
            });
    RecordingPlugin plugin = RecordingPlugin.answeringError(ImmutableMap.of("error", "handled"));

    Object output = run(tool, context(plugin), ImmutableMap.of("city", "Paris"));

    assertThat(output).isEqualTo(ImmutableMap.of("error", "handled"));
    assertThat(plugin.calls)
        .containsExactly(
            "before lookup {city=Paris}", "error lookup no data", "after lookup {error=handled}")
        .inOrder();
  }

  @Test
  public void runNode_toolFailsAndNoPluginAnswers_failsTheNode() {
    FakeTool tool =
        FakeTool.running(
            "lookup",
            (args, toolContext) -> {
              throw new IllegalStateException("no data");
            });
    RecordingPlugin plugin = RecordingPlugin.silent();

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> run(tool, context(plugin), ImmutableMap.of("city", "Paris")));

    assertThat(thrown).hasMessageThat().isEqualTo("no data");
    assertThat(plugin.calls).contains("error lookup no data");
  }

  private static Object run(BaseTool tool, @Nullable Object nodeInput) {
    return run(tool, context(), nodeInput);
  }

  private static Object run(BaseTool tool, Context context, @Nullable Object nodeInput) {
    return ToolNodes.asNode(tool).runNode(context, nodeInput).blockingSingle();
  }

  private static Context context() {
    return context(new PluginManager());
  }

  private static Context context(BasePlugin plugin) {
    return context(new PluginManager(ImmutableList.of(plugin)));
  }

  private static Context context(PluginManager pluginManager) {
    InvocationContext invocationContext =
        TestUtils.createInvocationContext(TestUtils.createRootAgent()).toBuilder()
            .pluginManager(pluginManager)
            .build();
    return new CallbackContext(invocationContext, /* eventActions= */ null);
  }

  /** Runs {@code node} as the root of a workflow run and returns the events it sends. */
  private static ImmutableList<Event> runAsRoot(Node node) {
    List<Event> events = new ArrayList<>();
    Context unused =
        NodeRunner.builder()
            .node(node)
            .invocationContext(TestUtils.createInvocationContext(TestUtils.createRootAgent()))
            .eventSink((event, nodeFailure) -> Completable.fromAction(() -> events.add(event)))
            .build()
            .run(/* nodeInput= */ null)
            .blockingGet();
    return ImmutableList.copyOf(events);
  }

  private static Event interrupt(String id) {
    return newEvent()
        .content(callContent(LONG_RUNNING_CALL, id))
        .longRunningToolIds(ImmutableSet.of(id))
        .build();
  }

  private record City(String city, String units) {}

  /** Functions for the {@link FunctionTool} tests. */
  public static final class Functions {
    public static ImmutableMap<String, Object> forecast(
        @Annotations.Schema(name = "city") String city) {
      return ImmutableMap.of("forecast", "Sunny in " + city);
    }

    public static void record(@Annotations.Schema(name = "note") String note) {}

    private Functions() {}
  }

  /** A tool with an optional declaration that runs {@code body}. */
  private static final class FakeTool extends BaseTool {
    private final @Nullable FunctionDeclaration declaration;
    private final BiFunction<Map<String, Object>, ToolContext, Map<String, Object>> body;

    private FakeTool(
        String name,
        @Nullable FunctionDeclaration declaration,
        BiFunction<Map<String, Object>, ToolContext, Map<String, Object>> body) {
      super(name, "A fake tool.");
      this.declaration = declaration;
      this.body = body;
    }

    /** Returns a tool without a declaration that outputs its arguments. */
    static FakeTool echo(String name) {
      return running(name, (args, toolContext) -> args);
    }

    /** Returns a tool with one required string parameter that outputs its arguments. */
    static FakeTool echo(String name, String param) {
      return echo(name, ImmutableList.of(param), ImmutableList.of(param));
    }

    /** Returns a tool that declares string {@code params} and outputs its arguments. */
    static FakeTool echo(
        String name, ImmutableList<String> params, ImmutableList<String> required) {
      Schema parameters =
          Schema.builder()
              .type("OBJECT")
              .properties(
                  params.stream()
                      .collect(
                          toImmutableMap(
                              param -> param, param -> Schema.builder().type("STRING").build())))
              .required(required)
              .build();
      return new FakeTool(
          name,
          FunctionDeclaration.builder().name(name).parameters(parameters).build(),
          (args, toolContext) -> args);
    }

    /**
     * Returns a tool that declares {@code schema} as a raw JSON schema and outputs its arguments.
     */
    static FakeTool withRawSchema(String name, Map<String, Object> schema) {
      return new FakeTool(
          name,
          FunctionDeclaration.builder().name(name).parametersJsonSchema(schema).build(),
          (args, toolContext) -> args);
    }

    /** Returns a tool without a declaration that runs {@code body}. */
    static FakeTool running(
        String name, BiFunction<Map<String, Object>, ToolContext, Map<String, Object>> body) {
      return new FakeTool(name, /* declaration= */ null, body);
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.ofNullable(declaration);
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.fromCallable(() -> body.apply(args, toolContext));
    }
  }

  /** Records the plugins' tool callbacks, answering at most one of them with a map. */
  private static final class RecordingPlugin extends BasePlugin {
    final List<String> calls = new ArrayList<>();
    private final @Nullable Map<String, Object> beforeAnswer;
    private final @Nullable Map<String, Object> afterAnswer;
    private final @Nullable Map<String, Object> errorAnswer;

    private RecordingPlugin(
        @Nullable Map<String, Object> beforeAnswer,
        @Nullable Map<String, Object> afterAnswer,
        @Nullable Map<String, Object> errorAnswer) {
      super("recording");
      this.beforeAnswer = beforeAnswer;
      this.afterAnswer = afterAnswer;
      this.errorAnswer = errorAnswer;
    }

    static RecordingPlugin silent() {
      return new RecordingPlugin(null, null, null);
    }

    static RecordingPlugin answeringBefore(Map<String, Object> answer) {
      return new RecordingPlugin(answer, null, null);
    }

    static RecordingPlugin answeringAfter(Map<String, Object> answer) {
      return new RecordingPlugin(null, answer, null);
    }

    static RecordingPlugin answeringError(Map<String, Object> answer) {
      return new RecordingPlugin(null, null, answer);
    }

    @Override
    public Maybe<Map<String, Object>> beforeToolCallback(
        BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
      calls.add("before " + tool.name() + " " + toolArgs);
      return beforeAnswer == null ? Maybe.empty() : Maybe.just(beforeAnswer);
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(
        BaseTool tool,
        Map<String, Object> toolArgs,
        ToolContext toolContext,
        Map<String, Object> result) {
      calls.add("after " + tool.name() + " " + result);
      return afterAnswer == null ? Maybe.empty() : Maybe.just(afterAnswer);
    }

    @Override
    public Maybe<Map<String, Object>> onToolErrorCallback(
        BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
      calls.add("error " + tool.name() + " " + error.getMessage());
      return errorAnswer == null ? Maybe.empty() : Maybe.just(errorAnswer);
    }
  }
}
