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

package com.google.adk.tokt;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.kt.agents.BaseAgent;
import com.google.adk.kt.agents.ResumabilityConfig;
import com.google.adk.kt.agents.SequentialAgent;
import com.google.adk.kt.apps.App;
import com.google.adk.kt.models.Model;
import com.google.adk.kt.runners.InMemoryRunner;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.plugins.GlobalInstructionPlugin;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.LongRunningFunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HarmBlockThreshold;
import com.google.genai.types.HarmCategory;
import com.google.genai.types.Part;
import com.google.genai.types.SafetySetting;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Live integration tests: real ADK <b>Java</b> tools, toolsets, plugins, models and services
 * running on the ADK <b>Kotlin</b> engine through the {@code tokt} interop, driven entirely through
 * the ADK Java {@link Runner} API via {@link KotlinAdkToJava#asJavaRunner}, against the real Gemini
 * API. This is the Java port of the Kotlin {@code KtRunnerInteropLiveTest}: Java-shaped request in,
 * a real model call on the Kotlin engine, converted Java events out.
 *
 * <p>Like ADK Java's other API-key-gated integration tests, each test is <b>skipped</b> (JUnit
 * assumption) unless {@code GOOGLE_API_KEY} is set, so hermetic runs pass without it. Uses the
 * Gemini Developer API (AI Studio), not Vertex. Assertions are deliberately <b>structural</b>
 * (which calls / responses / authors / state appeared), never on model prose, so wording cannot
 * make them flaky.
 */
@RunWith(JUnit4.class)
public final class KtRunnerInteropLiveTest {

  /** Bounds a hung API call, which would otherwise stall the whole run. */
  @Rule public final Timeout timeout = Timeout.seconds(180);

  // ==== Forward interop: adapted Java tools, toolsets, plugins, models, services ====

  /**
   * The other live tests send a near-empty generation config, so the request the codec builds is
   * barely exercised against a real endpoint. Only a real call proves the API accepts the request
   * built from a rich config - which is how a wrongly-cased enum reached review once already. Built
   * as an ADK Java agent so the Java-to-Kotlin config codec is exercised on the way to the engine.
   */
  @Test
  public void richGenerateContentConfig_isAcceptedByTheRealApi() {
    String key = apiKey();
    GenerateContentConfig richConfig =
        GenerateContentConfig.builder()
            .temperature(0.2f)
            .topP(0.9f)
            .topK(40.0f)
            .maxOutputTokens(512)
            .stopSequences(ImmutableList.of("NEVER_EMITTED"))
            .systemInstruction(
                Content.builder().role("system").parts(Part.fromText("You are terse.")).build())
            .responseMimeType("application/json")
            .responseSchema(
                Schema.builder()
                    .type(new Type(Type.Known.OBJECT))
                    .properties(
                        ImmutableMap.of(
                            "city",
                            Schema.builder()
                                .type(new Type(Type.Known.STRING))
                                .description("The city named.")
                                .build(),
                            "population",
                            Schema.builder().type(new Type(Type.Known.INTEGER)).build()))
                    .required(ImmutableList.of("city", "population"))
                    .build())
            .safetySettings(
                ImmutableList.of(
                    SafetySetting.builder()
                        .category(HarmCategory.Known.HARM_CATEGORY_HATE_SPEECH)
                        .threshold(HarmBlockThreshold.Known.BLOCK_ONLY_HIGH)
                        .build()))
            .thinkingConfig(ThinkingConfig.builder().includeThoughts(true).build())
            .build();
    LlmAgent agent =
        LlmAgent.builder()
            .name("assistant")
            .model(javaModel(key))
            .instruction("Answer using the requested JSON shape.")
            .generateContentConfig(richConfig)
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder().agent(JavaAdkToKt.asKtAgent(agent)).appName("live").build());

    List<Event> events = drive(javaRunner, "Give me the population of Warsaw.", 4);

    // The API accepting the request is most of the point; a malformed converted config surfaces as
    // an event error message.
    assertThat(hasErrorMessage(events)).isFalse();
    // responseSchema and responseMimeType really took effect: the reply is a JSON object with the
    // required keys, not prose.
    String json = lastNonBlankText(events).trim();
    assertThat(json).startsWith("{");
    assertThat(json).contains("\"city\"");
    assertThat(json).contains("\"population\"");
  }

  @Test
  public void javaToolsToolsetAndServices_onKotlinAgent() {
    String key = apiKey();
    LiveInteropTools.MathToolset toolset = new LiveInteropTools.MathToolset();
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("assistant")
            .model(ktModel(key))
            .instruction("Use the provided tools when relevant, then answer briefly.")
            .tools(
                ImmutableList.of(
                    JavaAdkToKt.asKtTool(tool("getWeather")),
                    JavaAdkToKt.asKtTool(tool("rememberColor"))))
            .toolsets(ImmutableList.of(JavaAdkToKt.asKtToolset(toolset)))
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .app(App.builder().appName("live").rootAgent(agent).build())
                .sessionService(JavaAdkToKt.asKtSessionService(new InMemorySessionService()))
                .artifactService(JavaAdkToKt.asKtArtifactService(new InMemoryArtifactService()))
                .memoryService(JavaAdkToKt.asKtMemoryService(new InMemoryMemoryService()))
                .build());

    List<Event> events = drive(javaRunner, "What's the weather in Warsaw, and what is 21 + 21?", 8);

    assertThat(responseNames(events)).contains("getWeather");
    assertThat(toolset.provisionedForAgents()).isNotEmpty();
    assertThat(toolset.provisionedForAgents().get(0)).isEqualTo("assistant");
  }

  @Test
  public void transferWithJavaPlugins_keepsTransferToAgentTool() {
    // A bridged beforeModel plugin mutates the request; it must NOT drop the engine's
    // transfer_to_agent tool, or multi-agent routing silently stops working.
    String key = apiKey();
    com.google.adk.kt.agents.LlmAgent weather =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("weather_agent")
            .description("Answers questions about the weather in a city.")
            .model(ktModel(key))
            .instruction("Use the getWeather tool, then answer.")
            .tools(ImmutableList.of(JavaAdkToKt.asKtTool(tool("getWeather"))))
            .build();
    com.google.adk.kt.agents.LlmAgent math =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("math_agent")
            .description("Answers arithmetic questions.")
            .model(ktModel(key))
            .instruction("Use the add tool, then answer.")
            .tools(ImmutableList.of(JavaAdkToKt.asKtTool(tool("add"))))
            .build();
    com.google.adk.kt.agents.LlmAgent router =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("router")
            .description("Routes the user request to the best sub-agent.")
            .model(ktModel(key))
            .instruction("Transfer the user's request to the most suitable sub-agent.")
            .subAgents(weather, math)
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .app(
                    App.builder()
                        .appName("live")
                        .rootAgent(router)
                        .plugins(
                            JavaAdkToKt.asKtPlugins(
                                ImmutableList.of(
                                    new LoggingPlugin(),
                                    new GlobalInstructionPlugin(
                                        "Be concise and name the tool used."))))
                        .build())
                .build());

    List<Event> events = drive(javaRunner, "What's the weather in Tokyo?", 10);

    assertThat(events.stream().anyMatch(e -> Objects.equals(e.author(), "weather_agent"))).isTrue();
  }

  @Test
  public void javaPluginAfterTool_writesAndRemovesState() {
    String key = apiKey();
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("assistant")
            .model(ktModel(key))
            .instruction("When the user shares a favorite color, call rememberColor to store it.")
            .tools(ImmutableList.of(JavaAdkToKt.asKtTool(tool("rememberColor"))))
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .app(
                    App.builder()
                        .appName("live")
                        .rootAgent(agent)
                        .plugins(
                            JavaAdkToKt.asKtPlugins(
                                ImmutableList.of(new LiveInteropTools.StateProbePlugin())))
                        .build())
                .build());

    drive(javaRunner, "My favorite color is teal - please remember it.", 6);

    Map<String, Object> state =
        javaRunner
            .sessionService()
            .getSession("live", "u", "s", Optional.empty())
            .blockingGet()
            .state();
    assertThat(state).containsEntry("favorite_color", "teal");
    assertThat(state).containsEntry("last_tool", "rememberColor");
    assertThat(state).doesNotContainKey("probe_temp");
    assertThat(state).containsEntry("probe_removed_value", "temp");
  }

  @Test
  public void thinkingModel_roundTripsThoughtSignatures() {
    String key = apiKey();
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("thinker")
            .model(ktModel(key))
            .instruction("Think it through, use getWeather if useful, then answer briefly.")
            .tools(ImmutableList.of(JavaAdkToKt.asKtTool(tool("getWeather"))))
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(InMemoryRunner.builder().agent(agent).appName("live").build());

    List<Event> events = drive(javaRunner, "Should I bring an umbrella in Seattle today?", 8);

    // Reaching a tool proves the thought signature survived the round-trip: the follow-up request
    // replays the prior turn's parts, and a dropped or corrupted signature makes the API reject it.
    assertThat(responseNames(events)).contains("getWeather");
  }

  @Test
  public void javaLongRunningTool_surfacesCallThenResumes() {
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("approver")
            .model(ktModel(apiKey()))
            .instruction(
                "To approve spending you MUST call requestApproval. Once the decision arrives, tell"
                    + " the user the outcome in one sentence.")
            .tools(
                ImmutableList.of(
                    JavaAdkToKt.asKtTool(
                        LongRunningFunctionTool.create(LiveInteropTools.class, "requestApproval"))))
            .build();
    assertApprovalPausesThenResumes(agent);
  }

  @Test
  public void javaRequireConfirmationTool_gatesThenRuns() {
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("fileops")
            .model(ktModel(apiKey()))
            .instruction(
                "To delete a file you MUST call the deleteFile tool with its path; do not ask for"
                    + " confirmation, just call it.")
            .tools(
                ImmutableList.of(
                    JavaAdkToKt.asKtTool(
                        FunctionTool.create(
                            LiveInteropTools.class,
                            "deleteFile",
                            /* requireConfirmation= */ true))))
            .build();
    assertConfirmationGatesThenRuns(agent);
  }

  // ==== Adapted whole Java agents on the Kotlin engine ====

  @Test
  public void adaptedJavaAgent_usesJavaToolsAndModel() {
    String key = apiKey();
    LlmAgent javaAgent =
        LlmAgent.builder()
            .name("java_assistant")
            .description("A helpful assistant implemented as an ADK Java agent.")
            .model(javaModel(key))
            .instruction("You are a helpful assistant. Use the provided tools when relevant.")
            .tools(tool("getWeather"), tool("add"))
            .generateContentConfig(cfg())
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .agent(JavaAdkToKt.asKtAgent(javaAgent))
                .appName("live")
                .build());

    List<Event> events = drive(javaRunner, "What's the weather in Paris, and what is 7 + 5?", 8);

    // The Java agent runs its OWN multi-step flow (model -> tool -> model) on the Kotlin runner.
    assertThat(responseNames(events)).contains("getWeather");
    assertThat(lastNonBlankText(events)).isNotEmpty();
  }

  @Test
  public void adaptedJavaAgent_withToolsetPluginsAndServices_seesStateSetThisTurn() {
    String key = apiKey();
    LiveInteropTools.LifecyclePlugin lifecycle = new LiveInteropTools.LifecyclePlugin();
    LlmAgent javaAgent =
        LlmAgent.builder()
            .name("concierge")
            .description("A helpful concierge implemented as an ADK Java agent.")
            .model(javaModel(key))
            .instruction(
                "You are a concierge. Use getWeather for weather, add for math, rememberColor to"
                    + " store the user's favorite color, and recallColor to look it up. Always call"
                    + " the tools; never guess.")
            // Tools and a toolset together (ADK Java's tools() accepts a BaseToolset too).
            .tools(
                tool("getWeather"),
                tool("rememberColor"),
                tool("recallColor"),
                new LiveInteropTools.MathToolset())
            .generateContentConfig(cfg())
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .app(
                    App.builder()
                        .appName("live")
                        .rootAgent(JavaAdkToKt.asKtAgent(javaAgent))
                        .plugins(
                            JavaAdkToKt.asKtPlugins(
                                ImmutableList.of(lifecycle, new LoggingPlugin())))
                        .build())
                .sessionService(JavaAdkToKt.asKtSessionService(new InMemorySessionService()))
                .artifactService(JavaAdkToKt.asKtArtifactService(new InMemoryArtifactService()))
                .memoryService(JavaAdkToKt.asKtMemoryService(new InMemoryMemoryService()))
                .build());

    List<Event> events =
        drive(
            javaRunner,
            "My favorite color is teal. What's the weather in Paris, what is 12 + 30, and what"
                + " color do I like?",
            12);

    Map<String, Object> state =
        javaRunner
            .sessionService()
            .getSession("live", "u", "s", Optional.empty())
            .blockingGet()
            .state();
    assertThat(state).containsEntry("favorite_color", "teal");
    // recallColor reads state a previous tool set in the SAME turn -- only a live session view
    // makes that visible to the Java flow's later steps.
    Map<String, Object> recall = responseFor(events, "recallColor");
    assertThat(recall).containsEntry("color", "teal");
    // The Kotlin runner drives the agent/run-level plugin callbacks for an adapted Java agent.
    assertThat(lifecycle.calls()).containsAtLeast("beforeRun", "afterRun");
  }

  @Test
  public void adaptedJavaAgentTree_transfersWithinTheJavaSubtree() {
    String key = apiKey();
    LlmAgent weatherAgent =
        LlmAgent.builder()
            .name("weather_agent")
            .description("Answers questions about the weather in a city.")
            .model(javaModel(key))
            .instruction("Use getWeather, then answer.")
            .tools(tool("getWeather"))
            .generateContentConfig(cfg())
            .build();
    LlmAgent mathAgent =
        LlmAgent.builder()
            .name("math_agent")
            .description("Answers arithmetic questions.")
            .model(javaModel(key))
            .instruction("Use add, then answer.")
            .tools(tool("add"))
            .generateContentConfig(cfg())
            .build();
    LlmAgent router =
        LlmAgent.builder()
            .name("router")
            .description("Routes the user request to the best sub-agent.")
            .model(javaModel(key))
            .instruction("Transfer the request to the most suitable sub-agent.")
            .subAgents(weatherAgent, mathAgent)
            .generateContentConfig(cfg())
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder().agent(JavaAdkToKt.asKtAgent(router)).appName("live").build());

    List<Event> events = drive(javaRunner, "What's the weather in Tokyo?", 10);

    // Transfer happens inside the Java flow's own subtree (Java findAgent); the Kotlin engine sees
    // the whole tree as one opaque leaf agent.
    assertThat(responseNames(events)).contains("getWeather");
  }

  @Test
  public void mixedTree_transfersFromKotlinRootToAdaptedJavaSubAgent() {
    String key = apiKey();
    com.google.adk.kt.agents.LlmAgent ktMathAgent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("kt_math_agent")
            .description("A Kotlin agent that adds numbers.")
            .model(ktModel(key))
            .instruction("Use the add tool, then answer.")
            .tools(ImmutableList.of(JavaAdkToKt.asKtTool(tool("add"))))
            .build();
    BaseAgent javaWeatherAgent =
        JavaAdkToKt.asKtAgent(
            LlmAgent.builder()
                .name("java_weather_agent")
                .description("A Java agent that reports the weather in a city.")
                .model(javaModel(key))
                .instruction("Use getWeather, then answer.")
                .tools(tool("getWeather"))
                .generateContentConfig(cfg())
                .build());
    com.google.adk.kt.agents.LlmAgent router =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("router")
            .description("Routes the request to the best sub-agent.")
            .model(ktModel(key))
            .instruction("Transfer the request to the most suitable sub-agent.")
            .subAgents(ktMathAgent, javaWeatherAgent)
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder().agent(router).appName("live").build());

    List<Event> events = drive(javaRunner, "What's the weather in Tokyo?", 10);

    assertThat(events.stream().anyMatch(e -> Objects.equals(e.author(), "java_weather_agent")))
        .isTrue();
  }

  @Test
  public void kotlinSequentialAgent_runsNativeThenAdaptedJavaAgent() {
    String key = apiKey();
    com.google.adk.kt.agents.LlmAgent ktGreeter =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("kt_greeter")
            .description("A Kotlin agent that greets the user.")
            .model(ktModel(key))
            .instruction("Greet the user warmly in one short sentence.")
            .build();
    BaseAgent javaWeather =
        JavaAdkToKt.asKtAgent(
            LlmAgent.builder()
                .name("java_weather")
                .description("A Java agent that reports the weather.")
                .model(javaModel(key))
                .instruction(
                    "Use getWeather for the city mentioned in the conversation, then answer.")
                .tools(tool("getWeather"))
                .generateContentConfig(cfg())
                .build());
    SequentialAgent pipeline =
        SequentialAgent.builder()
            .name("pipeline")
            .description("Greets, then reports the weather.")
            .subAgents(ktGreeter, javaWeather)
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder().agent(pipeline).appName("live").build());

    List<Event> events = drive(javaRunner, "Hi! I'm in Paris today.", 10);

    List<String> authors = new ArrayList<>();
    for (Event event : events) {
      if (!authors.contains(event.author())) {
        authors.add(event.author());
      }
    }
    assertThat(authors).contains("kt_greeter");
    assertThat(authors).contains("java_weather");
    // Both steps share one session, so the Java step can act on the Kotlin step's conversation.
    assertThat(responseNames(events)).contains("getWeather");
  }

  @Test
  public void adaptedJavaAgent_longRunningTool_surfacesCallThenResumes() {
    // Same HITL shape as javaLongRunningTool_surfacesCallThenResumes, but the whole agent is a Java
    // one adapted onto the engine, so the pause/resume runs through the Java flow instead.
    LlmAgent javaAgent =
        LlmAgent.builder()
            .name("approver")
            .description("Requests human approval for spending.")
            .model(javaModel(apiKey()))
            .instruction(
                "To approve spending you MUST call requestApproval; report the outcome once it"
                    + " arrives.")
            .tools(LongRunningFunctionTool.create(LiveInteropTools.class, "requestApproval"))
            .generateContentConfig(cfg())
            .build();
    assertApprovalPausesThenResumes(JavaAdkToKt.asKtAgent(javaAgent));
  }

  @Test
  public void adaptedJavaAgent_requireConfirmation_gatesThenRuns() {
    // Same gating shape as javaRequireConfirmationTool_gatesThenRuns, driven through an adapted
    // whole Java agent: this is the path that needs the invocation's resumability carried across.
    LlmAgent javaAgent =
        LlmAgent.builder()
            .name("fileops")
            .description("Performs file operations that need confirmation.")
            .model(javaModel(apiKey()))
            .instruction(
                "To delete a file you MUST call the deleteFile tool with its path; do not ask for"
                    + " confirmation, just call it.")
            .tools(
                FunctionTool.create(
                    LiveInteropTools.class, "deleteFile", /* requireConfirmation= */ true))
            .generateContentConfig(cfg())
            .build();
    assertConfirmationGatesThenRuns(JavaAdkToKt.asKtAgent(javaAgent));
  }

  // ==== Reverse direction: Kotlin-engine runner via asJavaRunner ====

  /**
   * The reverse direction end-to-end: a Kotlin-engine runner exposed as an ADK Java {@link Runner}
   * via {@link KotlinAdkToJava#asJavaRunner}, then driven exactly like a Java runner against the
   * real API. Proves the whole path - Java-shaped request in, real model call on the Kotlin engine,
   * converted events out - not just that the types line up.
   */
  @Test
  public void asJavaRunner_drivesTheKotlinEngineAgainstTheRealApi() {
    String key = apiKey();
    com.google.adk.kt.agents.LlmAgent agent =
        com.google.adk.kt.agents.LlmAgent.builder()
            .name("assistant")
            .model(ktModel(key))
            .instruction("Reply with a short greeting.")
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .app(App.builder().appName("live").rootAgent(agent).build())
                .build());

    List<Event> events =
        javaRunner
            .runAsync("u", "s", userMessage("Hi"), RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(lastNonBlankText(events)).isNotEmpty();
  }

  /**
   * The full round trip: a whole ADK Java agent (Java tool + Java model) adapted onto the Kotlin
   * engine with {@link JavaAdkToKt#asKtAgent}, then driven and read back entirely through the Java
   * {@link Runner} wrapper - Java in, Kotlin engine in the middle, Java out. The Java tool must run
   * and its result and the final answer must be visible as Java-shaped events.
   */
  @Test
  public void asJavaRunner_wrapsAdaptedJavaAgent_toolRunsAndIsReadThroughTheJavaApi() {
    String key = apiKey();
    LlmAgent javaAgent =
        LlmAgent.builder()
            .name("java_assistant")
            .description("A helpful assistant implemented as an ADK Java agent.")
            .model(javaModel(key))
            .instruction("You are a helpful assistant. Use the provided tools when relevant.")
            .tools(tool("getWeather"), tool("add"))
            .generateContentConfig(cfg())
            .build();
    Runner javaRunner =
        KotlinAdkToJava.asJavaRunner(
            InMemoryRunner.builder()
                .agent(JavaAdkToKt.asKtAgent(javaAgent))
                .appName("live")
                .build());

    List<Event> events = drive(javaRunner, "What's the weather in Paris, and what is 7 + 5?", 8);

    // The Java tool ran on the Kotlin engine, and its response is visible as a Java-shaped event.
    assertThat(responseNames(events)).contains("getWeather");
    // A final text answer came back through the Java API after the tool step.
    assertThat(lastNonBlankText(events)).isNotEmpty();
  }

  // ==== shared helpers ====

  /** The rolling "latest" alias, so these tests track the current model, not a pinned snapshot. */
  private static final String MODEL = "gemini-flash-latest";

  private static final String CONFIRMATION_CALL = "adk_request_confirmation";

  private static String apiKey() {
    String key = System.getenv("GOOGLE_API_KEY");
    assumeTrue(
        "GOOGLE_API_KEY not set; skipping live interop test.", key != null && !key.isBlank());
    return key;
  }

  /** A real ADK Java Gemini model over the Gemini Developer API (AI Studio), by API key. */
  private static BaseLlm javaModel(String key) {
    return new Gemini(MODEL, Client.builder().apiKey(key).vertexAI(false).build());
  }

  /** The same Java model, adapted so the Kotlin engine can call it from a native Kotlin agent. */
  private static Model ktModel(String key) {
    return JavaAdkToKt.asKtModel(javaModel(key));
  }

  /** A modest output cap (genai config) so a chatty live turn does not run to MAX_TOKENS. */
  private static GenerateContentConfig cfg() {
    return GenerateContentConfig.builder().maxOutputTokens(512).build();
  }

  private static FunctionTool tool(String method) {
    return FunctionTool.create(LiveInteropTools.class, method);
  }

  private static Content userMessage(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  private static List<Event> drive(Runner runner, String text, int maxLlmCalls) {
    return runner
        .runAsync("u", "s", userMessage(text), RunConfig.builder().maxLlmCalls(maxLlmCalls).build())
        .toList()
        .blockingGet();
  }

  /** Resumability is what makes a flow pause on a pending long-running call instead of looping. */
  private static App resumableApp(BaseAgent agent) {
    return App.builder()
        .appName("live")
        .rootAgent(agent)
        .resumabilityConfig(new ResumabilityConfig(/* isResumable= */ true))
        .build();
  }

  private static List<String> responseNames(List<Event> events) {
    List<String> names = new ArrayList<>();
    for (Event event : events) {
      for (FunctionResponse response : event.functionResponses()) {
        response.name().ifPresent(names::add);
      }
    }
    return names;
  }

  /** The payload of the last response named {@code name}, or null if the tool produced none. */
  private static Map<String, Object> responseFor(List<Event> events, String name) {
    Map<String, Object> response = null;
    for (Event event : events) {
      for (FunctionResponse functionResponse : event.functionResponses()) {
        if (name.equals(functionResponse.name().orElse(null))) {
          response = functionResponse.response().orElse(null);
        }
      }
    }
    return response;
  }

  private static FunctionCall pendingLongRunningCall(List<Event> events, String name) {
    Set<String> ids = new HashSet<>();
    for (Event event : events) {
      event.longRunningToolIds().ifPresent(ids::addAll);
    }
    for (Event event : events) {
      for (FunctionCall call : event.functionCalls()) {
        if (name.equals(call.name().orElse(null))
            && call.id().isPresent()
            && ids.contains(call.id().get())) {
          return call;
        }
      }
    }
    return null;
  }

  private static FunctionCall firstFunctionCall(List<Event> events, String name) {
    for (Event event : events) {
      for (FunctionCall call : event.functionCalls()) {
        if (name.equals(call.name().orElse(null))) {
          return call;
        }
      }
    }
    return null;
  }

  /** A user turn carrying the awaited {@link FunctionResponse} for a paused call. */
  private static Content functionResponseTurn(
      String name, String id, Map<String, Object> response) {
    FunctionResponse.Builder functionResponse =
        FunctionResponse.builder().name(name).response(response);
    if (id != null) {
      functionResponse = functionResponse.id(id);
    }
    return Content.builder()
        .role("user")
        .parts(Part.builder().functionResponse(functionResponse.build()).build())
        .build();
  }

  private static boolean hasErrorMessage(List<Event> events) {
    for (Event event : events) {
      if (event.errorMessage().isPresent()) {
        return true;
      }
    }
    return false;
  }

  private static String lastNonBlankText(List<Event> events) {
    String text = "";
    for (Event event : events) {
      if (event.content().isEmpty()) {
        continue;
      }
      for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
        String candidate = part.text().orElse("");
        if (!candidate.isBlank()) {
          text = candidate;
        }
      }
    }
    return text;
  }

  /**
   * Drives the human-in-the-loop shape against the live model: turn 1 must surface the pending
   * long-running call rather than a decision, and a turn 2 carrying the out-of-band answer must
   * produce a final reply.
   */
  private void assertApprovalPausesThenResumes(BaseAgent rootAgent) {
    Runner runner =
        KotlinAdkToJava.asJavaRunner(InMemoryRunner.builder().app(resumableApp(rootAgent)).build());
    List<Event> turn1 =
        runner
            .runAsync(
                "u",
                "s",
                userMessage("Please request approval to spend $500 on laptops."),
                RunConfig.builder().maxLlmCalls(6).build())
            .toList()
            .blockingGet();

    FunctionCall call = pendingLongRunningCall(turn1, "requestApproval");
    assertThat(call).isNotNull();
    // A long-running tool still returns immediately; what makes it long-running is that the answer
    // is a placeholder and the real decision arrives out of band on a later turn.
    Map<String, Object> pending = responseFor(turn1, "requestApproval");
    assertThat(pending).containsEntry("status", "PENDING");

    List<Event> turn2 =
        runner
            .runAsync(
                "u",
                "s",
                functionResponseTurn(
                    "requestApproval",
                    call.id().orElse(null),
                    ImmutableMap.<String, Object>of("status", "APPROVED")),
                RunConfig.builder().maxLlmCalls(6).build())
            .toList()
            .blockingGet();

    assertThat(lastNonBlankText(turn2)).isNotEmpty();
  }

  /**
   * Drives the confirmation shape against the live model: turn 1 must gate behind {@code
   * CONFIRMATION_CALL} without running the tool body, and approving on turn 2 must run it.
   */
  private void assertConfirmationGatesThenRuns(BaseAgent rootAgent) {
    Runner runner =
        KotlinAdkToJava.asJavaRunner(InMemoryRunner.builder().app(resumableApp(rootAgent)).build());
    List<Event> turn1 =
        runner
            .runAsync(
                "u",
                "s",
                userMessage("Delete the file /tmp/old.log"),
                RunConfig.builder().maxLlmCalls(6).build())
            .toList()
            .blockingGet();

    FunctionCall confirmCall = firstFunctionCall(turn1, CONFIRMATION_CALL);
    assertThat(confirmCall).isNotNull();
    // The framework emits a placeholder deleteFile response carrying the confirmation request; the
    // tool BODY must not have run, and only the body reports status=DELETED.
    Map<String, Object> gated = responseFor(turn1, "deleteFile");
    assertThat(gated).isNotNull();
    assertThat(gated).doesNotContainEntry("status", "DELETED");

    List<Event> turn2 =
        runner
            .runAsync(
                "u",
                "s",
                functionResponseTurn(
                    CONFIRMATION_CALL,
                    confirmCall.id().orElse(null),
                    ImmutableMap.<String, Object>of("confirmed", true)),
                RunConfig.builder().maxLlmCalls(6).build())
            .toList()
            .blockingGet();

    Map<String, Object> result = responseFor(turn2, "deleteFile");
    assertThat(result).containsEntry("status", "DELETED");
  }
}
