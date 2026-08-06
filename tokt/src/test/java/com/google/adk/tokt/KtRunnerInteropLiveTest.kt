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

package com.google.adk.tokt

import com.google.adk.agents.LlmAgent as JavaLlmAgent
import com.google.adk.artifacts.InMemoryArtifactService as JavaInMemoryArtifactService
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ResumabilityConfig
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.apps.App
import com.google.adk.kt.events.Event
import com.google.adk.kt.models.Gemini as KtGemini
import com.google.adk.kt.models.Model as KtModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.ThinkingConfig
import com.google.adk.memory.InMemoryMemoryService as JavaInMemoryMemoryService
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.models.Gemini as JavaGemini
import com.google.adk.plugins.GlobalInstructionPlugin
import com.google.adk.plugins.LoggingPlugin
import com.google.adk.sessions.InMemorySessionService as JavaInMemorySessionService
import com.google.adk.tools.FunctionTool
import com.google.adk.tools.LongRunningFunctionTool
import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig as JavaGenerateContentConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Live integration tests: real ADK **Java** tools, toolsets, plugins, models and services running
 * on the ADK **Kotlin** engine through the `tokt` forward interop, against the real Gemini API.
 *
 * Like ADK Java's other API-key-gated integration tests, each test is **skipped** (JUnit
 * assumption) unless `GOOGLE_API_KEY` is set, so hermetic runs pass without it. Uses the Gemini
 * Developer API (AI Studio), not Vertex. Assertions are deliberately **structural** (which calls /
 * responses / authors / state appeared), never on model prose, so wording cannot make them flaky.
 *
 * Run: `GOOGLE_API_KEY=... ./mvnw -pl tokt test -Dtest=KtRunnerInteropLiveTest`.
 */
class KtRunnerInteropLiveTest {

  /** Bounds a hung API call, which would otherwise stall the whole run. */
  @get:Rule val timeout: Timeout = Timeout.seconds(180)

  private fun apiKey(): String {
    val key = System.getenv("GOOGLE_API_KEY")
    assumeTrue("GOOGLE_API_KEY not set; skipping live interop test.", !key.isNullOrBlank())
    return key!!
  }

  /** A real ADK Java Gemini model over the Gemini Developer API (AI Studio), by API key. */
  private fun ktModel(key: String): KtModel = KtGemini(name = MODEL, apiKey = key)

  private fun javaModel(key: String): JavaBaseLlm =
    JavaGemini(MODEL, Client.builder().apiKey(key).vertexAI(false).build())

  /** A modest output cap so a chatty live turn does not run to MAX_TOKENS. */
  private fun cfg(maxOutputTokens: Int = 512, thinking: Boolean = false) =
    GenerateContentConfig(
      maxOutputTokens = maxOutputTokens,
      thinkingConfig = if (thinking) ThinkingConfig(includeThoughts = true) else null,
    )

  /** The same cap for an ADK Java agent, which takes the genai config type. */
  private fun javaCfg(): JavaGenerateContentConfig =
    JavaGenerateContentConfig.builder().maxOutputTokens(512).build()

  @Test
  fun javaToolsToolsetAndServices_onKotlinAgent() = runBlocking {
    val key = apiKey()
    val toolset = LiveInteropTools.MathToolset()
    val agent =
      LlmAgent(
        name = "assistant",
        model = JavaAdkToKt.asKtModel(javaModel(key)),
        instruction = Instruction("Use the provided tools when relevant, then answer briefly."),
        tools = JavaAdkToKt.asKtTools(listOf(tool("getWeather"), tool("rememberColor"))),
        toolsets = listOf(JavaAdkToKt.asKtToolset(toolset)),
        generateContentConfig = cfg(),
      )
    val runner =
      InMemoryRunner(
        app = App(appName = "live", rootAgent = agent),
        sessionService = JavaAdkToKt.asKtSessionService(JavaInMemorySessionService()),
        artifactService = JavaAdkToKt.asKtArtifactService(JavaInMemoryArtifactService()),
        memoryService = JavaAdkToKt.asKtMemoryService(JavaInMemoryMemoryService()),
      )

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage =
            Content.fromText("user", "What's the weather in Warsaw, and what is 21 + 21?"),
          runConfig = RunConfig(maxLlmCalls = 8),
        )
        .toList()

    assertTrue(
      responseNames(events).contains("getWeather"),
      "the Java FunctionTool should have run; saw ${responseNames(events)}",
    )
    assertTrue(
      toolset.provisionedForAgents().isNotEmpty(),
      "the Java toolset should have been provisioned with a non-null ReadonlyContext",
    )
    assertEquals(
      "assistant",
      toolset.provisionedForAgents().first(),
      "the toolset's ReadonlyContext should report the running agent",
    )
  }

  @Test
  fun transferWithJavaPlugins_keepsTransferToAgentTool() = runBlocking {
    // A bridged beforeModel plugin mutates the request; it must NOT drop the engine's
    // transfer_to_agent tool, or multi-agent routing silently stops working.
    val key = apiKey()
    val weather =
      LlmAgent(
        name = "weather_agent",
        description = "Answers questions about the weather in a city.",
        model = JavaAdkToKt.asKtModel(javaModel(key)),
        instruction = Instruction("Use the getWeather tool, then answer."),
        tools = JavaAdkToKt.asKtTools(listOf(tool("getWeather"))),
        generateContentConfig = cfg(),
      )
    val math =
      LlmAgent(
        name = "math_agent",
        description = "Answers arithmetic questions.",
        model = JavaAdkToKt.asKtModel(javaModel(key)),
        instruction = Instruction("Use the add tool, then answer."),
        tools = JavaAdkToKt.asKtTools(listOf(tool("add"))),
        generateContentConfig = cfg(),
      )
    val router =
      LlmAgent(
        name = "router",
        description = "Routes the user request to the best sub-agent.",
        model = JavaAdkToKt.asKtModel(javaModel(key)),
        instruction = Instruction("Transfer the user's request to the most suitable sub-agent."),
        subAgents = listOf(weather, math),
        generateContentConfig = cfg(),
      )
    val runner =
      InMemoryRunner(
        App(
          appName = "live",
          rootAgent = router,
          plugins =
            JavaAdkToKt.asKtPlugins(
              listOf(LoggingPlugin(), GlobalInstructionPlugin("Be concise and name the tool used."))
            ),
        )
      )

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "What's the weather in Tokyo?"),
          runConfig = RunConfig(maxLlmCalls = 10),
        )
        .toList()

    assertTrue(
      events.any { it.author == "weather_agent" },
      "transfer must survive the bridged beforeModel plugin (weather_agent never ran)",
    )
  }

  @Test
  fun javaPluginAfterTool_writesAndRemovesState() = runBlocking {
    val key = apiKey()
    val agent =
      LlmAgent(
        name = "assistant",
        model = JavaAdkToKt.asKtModel(javaModel(key)),
        instruction =
          Instruction("When the user shares a favorite color, call rememberColor to store it."),
        tools = JavaAdkToKt.asKtTools(listOf(tool("rememberColor"))),
        generateContentConfig = cfg(),
      )
    val runner =
      InMemoryRunner(
        App(
          appName = "live",
          rootAgent = agent,
          plugins = JavaAdkToKt.asKtPlugins(listOf(LiveInteropTools.StateProbePlugin())),
        )
      )

    runner
      .runAsync(
        userId = "u",
        sessionId = "s",
        newMessage = Content.fromText("user", "My favorite color is teal - please remember it."),
        runConfig = RunConfig(maxLlmCalls = 6),
      )
      .toList()

    val state = runner.sessionService.getSession(SessionKey("live", "u", "s"))!!.state
    assertEquals("teal", state["favorite_color"], "the Java tool's state write should persist")
    assertEquals(
      "rememberColor",
      state["last_tool"],
      "the Java plugin's afterTool state write should persist",
    )
    assertTrue(
      !state.containsKey("probe_temp"),
      "a key removed in the Java plugin's afterTool callback must not persist",
    )
    assertEquals(
      "temp",
      state["probe_removed_value"],
      "removing a key must return the value it held, through the bridged state map",
    )
  }

  @Test
  fun thinkingModel_roundTripsThoughtSignatures() = runBlocking {
    val key = apiKey()
    val agent =
      LlmAgent(
        name = "thinker",
        model = JavaAdkToKt.asKtModel(javaModel(key)),
        instruction =
          Instruction("Think it through, use getWeather if useful, then answer briefly."),
        tools = JavaAdkToKt.asKtTools(listOf(tool("getWeather"))),
        generateContentConfig = cfg(maxOutputTokens = 2048, thinking = true),
      )
    val runner = InMemoryRunner(agent, appName = "live")

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "Should I bring an umbrella in Seattle today?"),
          runConfig = RunConfig(maxLlmCalls = 8),
        )
        .toList()

    // Reaching a tool proves the thought signature survived the round-trip: the follow-up request
    // replays the prior turn's parts, and a dropped or corrupted signature makes the API reject it.
    assertTrue(
      responseNames(events).contains("getWeather"),
      "the thinking model should have completed a tool round-trip; saw ${responseNames(events)}",
    )
  }

  @Test
  fun javaLongRunningTool_surfacesCallThenResumes() = runBlocking {
    val agent =
      LlmAgent(
        name = "approver",
        model = JavaAdkToKt.asKtModel(javaModel(apiKey())),
        instruction =
          Instruction(
            "To approve spending you MUST call requestApproval. Once the decision arrives, tell" +
              " the user the outcome in one sentence."
          ),
        tools =
          JavaAdkToKt.asKtTools(
            listOf(LongRunningFunctionTool.create(LiveInteropTools::class.java, "requestApproval"))
          ),
        generateContentConfig = cfg(),
      )
    assertApprovalPausesThenResumes(agent)
  }

  @Test
  fun javaRequireConfirmationTool_gatesThenRuns() = runBlocking {
    val agent =
      LlmAgent(
        name = "fileops",
        model = JavaAdkToKt.asKtModel(javaModel(apiKey())),
        instruction = Instruction("To delete a file, call deleteFile with its path."),
        tools =
          JavaAdkToKt.asKtTools(
            listOf(
              FunctionTool.create(
                LiveInteropTools::class.java,
                "deleteFile",
                /* requireConfirmation= */ true,
              )
            )
          ),
        generateContentConfig = cfg(),
      )
    assertConfirmationGatesThenRuns(agent)
  }

  /**
   * Drives the human-in-the-loop shape against the live model: turn 1 must surface the pending
   * long-running call rather than a decision, and a turn 2 carrying the out-of-band answer must
   * produce a final reply.
   */
  private suspend fun assertApprovalPausesThenResumes(rootAgent: BaseAgent) {
    val runner = InMemoryRunner(resumableApp(rootAgent))
    val turn1 =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage =
            Content.fromText("user", "Please request approval to spend \$500 on laptops."),
          runConfig = RunConfig(maxLlmCalls = 6),
        )
        .toList()

    val call =
      assertNotNull(
        pendingLongRunningCall(turn1, "requestApproval"),
        "the long-running call should be surfaced with its id in longRunningToolIds",
      )
    // A long-running tool still returns immediately; what makes it long-running is that the answer
    // is a placeholder and the real decision arrives out of band on a later turn.
    assertEquals(
      "PENDING",
      responseFor(turn1, "requestApproval")?.get("status"),
      "turn 1 should carry the tool's pending placeholder, not a decision",
    )

    val turn2 =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage =
            functionResponseTurn("requestApproval", call.id, mapOf("status" to "APPROVED")),
          runConfig = RunConfig(maxLlmCalls = 6),
        )
        .toList()

    assertTrue(
      turn2.flatMap { it.content?.parts.orEmpty() }.any { !it.text.isNullOrBlank() },
      "resuming with the human decision should produce a final answer",
    )
  }

  /**
   * Drives the confirmation shape against the live model: turn 1 must gate behind
   * [CONFIRMATION_CALL] without running the tool body, and approving on turn 2 must run it.
   */
  private suspend fun assertConfirmationGatesThenRuns(rootAgent: BaseAgent) {
    val runner = InMemoryRunner(resumableApp(rootAgent))
    val turn1 =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "Delete the file /tmp/old.log"),
          runConfig = RunConfig(maxLlmCalls = 6),
        )
        .toList()

    val confirmCall =
      assertNotNull(
        turn1
          .flatMap { it.content?.parts.orEmpty() }
          .mapNotNull { it.functionCall }
          .firstOrNull { it.name == CONFIRMATION_CALL },
        "a requireConfirmation tool must gate behind $CONFIRMATION_CALL",
      )
    // The framework emits a placeholder deleteFile response carrying the confirmation request; the
    // tool BODY must not have run, and only the body reports status=DELETED.
    val gated =
      assertNotNull(
        responseFor(turn1, "deleteFile"),
        "the framework should emit a placeholder deleteFile response",
      )
    assertTrue(
      gated["status"] != "DELETED",
      "the gated tool's body must not run before it is confirmed",
    )

    val turn2 =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage =
            functionResponseTurn(CONFIRMATION_CALL, confirmCall.id, mapOf("confirmed" to true)),
          runConfig = RunConfig(maxLlmCalls = 6),
        )
        .toList()

    assertEquals(
      "DELETED",
      responseFor(turn2, "deleteFile")?.get("status"),
      "approving the confirmation should actually run the gated Java tool's body",
    )
  }

  @Test
  fun adaptedJavaAgent_usesJavaToolsAndModel() = runBlocking {
    val key = apiKey()
    val javaAgent =
      JavaLlmAgent.builder()
        .name("java_assistant")
        .description("A helpful assistant implemented as an ADK Java agent.")
        .model(javaModel(key))
        .instruction("You are a helpful assistant. Use the provided tools when relevant.")
        .tools(tool("getWeather"), tool("add"))
        .generateContentConfig(javaCfg())
        .build()
    val runner = InMemoryRunner(JavaAdkToKt.asKtAgent(javaAgent), appName = "live")

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "What's the weather in Paris, and what is 7 + 5?"),
          runConfig = RunConfig(maxLlmCalls = 8),
        )
        .toList()

    // The Java agent runs its OWN multi-step flow (model -> tool -> model) on the Kotlin runner.
    assertTrue(
      responseNames(events).contains("getWeather"),
      "the adapted Java agent should run its own tools; saw ${responseNames(events)}",
    )
    assertTrue(
      events.flatMap { it.content?.parts.orEmpty() }.any { !it.text.isNullOrBlank() },
      "the adapted Java agent should reach a final answer after its tool step",
    )
  }

  @Test
  fun adaptedJavaAgent_withToolsetPluginsAndServices_seesStateSetThisTurn() = runBlocking {
    val key = apiKey()
    val lifecycle = LiveInteropTools.LifecyclePlugin()
    val javaAgent =
      JavaLlmAgent.builder()
        .name("concierge")
        .description("A helpful concierge implemented as an ADK Java agent.")
        .model(javaModel(key))
        .instruction(
          "You are a concierge. Use getWeather for weather, add for math, rememberColor to store" +
            " the user's favorite color, and recallColor to look it up. Always call the tools;" +
            " never guess."
        )
        // Tools and a toolset together (ADK Java's tools() accepts a BaseToolset too).
        .tools(
          tool("getWeather"),
          tool("rememberColor"),
          tool("recallColor"),
          LiveInteropTools.MathToolset(),
        )
        .generateContentConfig(javaCfg())
        .build()
    val runner =
      InMemoryRunner(
        app =
          App(
            appName = "live",
            rootAgent = JavaAdkToKt.asKtAgent(javaAgent),
            plugins = JavaAdkToKt.asKtPlugins(listOf(lifecycle, LoggingPlugin())),
          ),
        sessionService = JavaAdkToKt.asKtSessionService(JavaInMemorySessionService()),
        artifactService = JavaAdkToKt.asKtArtifactService(JavaInMemoryArtifactService()),
        memoryService = JavaAdkToKt.asKtMemoryService(JavaInMemoryMemoryService()),
      )

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage =
            Content.fromText(
              "user",
              "My favorite color is teal. What's the weather in Paris, what is 12 + 30, and what" +
                " color do I like?",
            ),
          runConfig = RunConfig(maxLlmCalls = 12),
        )
        .toList()

    assertEquals(
      "teal",
      runner.sessionService.getSession(SessionKey("live", "u", "s"))!!.state["favorite_color"],
      "the adapted Java agent's tool should have written session state",
    )
    // recallColor reads state a previous tool set in the SAME turn -- only a live session view
    // makes that visible to the Java flow's later steps.
    assertEquals(
      "teal",
      responseFor(events, "recallColor")?.get("color"),
      "a later step must see state an earlier step set in the same turn",
    )
    // The Kotlin runner drives the agent/run-level plugin callbacks for an adapted Java agent.
    assertTrue(
      lifecycle.calls().contains("beforeRun") && lifecycle.calls().contains("afterRun"),
      "bridged Java plugins should receive run lifecycle callbacks; saw ${lifecycle.calls()}",
    )
  }

  @Test
  fun adaptedJavaAgentTree_transfersWithinTheJavaSubtree() = runBlocking {
    val key = apiKey()
    val weatherAgent =
      JavaLlmAgent.builder()
        .name("weather_agent")
        .description("Answers questions about the weather in a city.")
        .model(javaModel(key))
        .instruction("Use getWeather, then answer.")
        .tools(tool("getWeather"))
        .generateContentConfig(javaCfg())
        .build()
    val mathAgent =
      JavaLlmAgent.builder()
        .name("math_agent")
        .description("Answers arithmetic questions.")
        .model(javaModel(key))
        .instruction("Use add, then answer.")
        .tools(tool("add"))
        .generateContentConfig(javaCfg())
        .build()
    val router =
      JavaLlmAgent.builder()
        .name("router")
        .description("Routes the user request to the best sub-agent.")
        .model(javaModel(key))
        .instruction("Transfer the request to the most suitable sub-agent.")
        .subAgents(weatherAgent, mathAgent)
        .generateContentConfig(javaCfg())
        .build()
    val runner = InMemoryRunner(JavaAdkToKt.asKtAgent(router), appName = "live")

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "What's the weather in Tokyo?"),
          runConfig = RunConfig(maxLlmCalls = 10),
        )
        .toList()

    // Transfer happens inside the Java flow's own subtree (Java findAgent); the Kotlin engine sees
    // the whole tree as one opaque leaf agent.
    assertTrue(
      responseNames(events).contains("getWeather"),
      "the Java router should transfer to its Java weather sub-agent, which runs getWeather",
    )
  }

  @Test
  fun mixedTree_transfersFromKotlinRootToAdaptedJavaSubAgent() = runBlocking {
    val key = apiKey()
    val ktMathAgent =
      LlmAgent(
        name = "kt_math_agent",
        description = "A Kotlin agent that adds numbers.",
        model = ktModel(key),
        instruction = Instruction("Use the add tool, then answer."),
        tools = listOf(JavaAdkToKt.asKtTool(tool("add"))),
        generateContentConfig = cfg(),
      )
    val javaWeatherAgent =
      JavaAdkToKt.asKtAgent(
        JavaLlmAgent.builder()
          .name("java_weather_agent")
          .description("A Java agent that reports the weather in a city.")
          .model(javaModel(key))
          .instruction("Use getWeather, then answer.")
          .tools(tool("getWeather"))
          .generateContentConfig(javaCfg())
          .build()
      )
    val router =
      LlmAgent(
        name = "router",
        description = "Routes the request to the best sub-agent.",
        model = ktModel(key),
        instruction = Instruction("Transfer the request to the most suitable sub-agent."),
        subAgents = listOf(ktMathAgent, javaWeatherAgent),
        generateContentConfig = cfg(),
      )
    val runner = InMemoryRunner(router, appName = "live")

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "What's the weather in Tokyo?"),
          runConfig = RunConfig(maxLlmCalls = 10),
        )
        .toList()

    assertTrue(
      events.any { it.author == "java_weather_agent" },
      "the Kotlin root should transfer to the adapted Java sub-agent, which then runs",
    )
  }

  @Test
  fun kotlinSequentialAgent_runsNativeThenAdaptedJavaAgent() = runBlocking {
    val key = apiKey()
    val ktGreeter =
      LlmAgent(
        name = "kt_greeter",
        description = "A Kotlin agent that greets the user.",
        model = ktModel(key),
        instruction = Instruction("Greet the user warmly in one short sentence."),
        generateContentConfig = cfg(),
      )
    val javaWeather =
      JavaAdkToKt.asKtAgent(
        JavaLlmAgent.builder()
          .name("java_weather")
          .description("A Java agent that reports the weather.")
          .model(javaModel(key))
          .instruction("Use getWeather for the city mentioned in the conversation, then answer.")
          .tools(tool("getWeather"))
          .generateContentConfig(javaCfg())
          .build()
      )
    val runner =
      InMemoryRunner(
        SequentialAgent(
          name = "pipeline",
          description = "Greets, then reports the weather.",
          subAgents = listOf(ktGreeter, javaWeather),
        ),
        appName = "live",
      )

    val events =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = Content.fromText("user", "Hi! I'm in Paris today."),
          runConfig = RunConfig(maxLlmCalls = 10),
        )
        .toList()

    val authors = events.map { it.author }.distinct()
    assertTrue(authors.contains("kt_greeter"), "the native Kotlin step should run; saw $authors")
    assertTrue(authors.contains("java_weather"), "the adapted Java step should run; saw $authors")
    // Both steps share one session, so the Java step can act on the Kotlin step's conversation.
    assertTrue(
      responseNames(events).contains("getWeather"),
      "the adapted Java step should run its tool over the shared session",
    )
  }

  @Test
  fun adaptedJavaAgent_longRunningTool_surfacesCallThenResumes() = runBlocking {
    // Same HITL shape as javaLongRunningTool_surfacesCallThenResumes, but the whole agent is a Java
    // one adapted onto the engine, so the pause/resume runs through the Java flow instead.
    val javaAgent =
      JavaLlmAgent.builder()
        .name("approver")
        .description("Requests human approval for spending.")
        .model(javaModel(apiKey()))
        .instruction(
          "To approve spending you MUST call requestApproval; report the outcome once it arrives."
        )
        .tools(LongRunningFunctionTool.create(LiveInteropTools::class.java, "requestApproval"))
        .generateContentConfig(javaCfg())
        .build()
    assertApprovalPausesThenResumes(JavaAdkToKt.asKtAgent(javaAgent))
  }

  @Test
  fun adaptedJavaAgent_requireConfirmation_gatesThenRuns() = runBlocking {
    // Same gating shape as javaRequireConfirmationTool_gatesThenRuns, driven through an adapted
    // whole Java agent: this is the path that needs the invocation's resumability carried across.
    val javaAgent =
      JavaLlmAgent.builder()
        .name("fileops")
        .description("Performs file operations that need confirmation.")
        .model(javaModel(apiKey()))
        .instruction("To delete a file, call deleteFile with its path.")
        .tools(
          FunctionTool.create(
            LiveInteropTools::class.java,
            "deleteFile",
            /* requireConfirmation= */ true,
          )
        )
        .generateContentConfig(javaCfg())
        .build()
    assertConfirmationGatesThenRuns(JavaAdkToKt.asKtAgent(javaAgent))
  }

  private companion object {
    /**
     * The rolling "latest" alias, so these tests track the current model, not a pinned snapshot.
     */
    private const val MODEL = "gemini-flash-latest"

    private const val CONFIRMATION_CALL = "adk_request_confirmation"

    private fun tool(method: String) = FunctionTool.create(LiveInteropTools::class.java, method)

    /**
     * Resumability is what makes a flow pause on a pending long-running call instead of looping.
     */
    private fun resumableApp(agent: BaseAgent) =
      App(
        appName = "live",
        rootAgent = agent,
        resumabilityConfig = ResumabilityConfig(isResumable = true),
      )

    private fun responseNames(events: List<Event>): List<String> =
      events.flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.functionResponse?.name }

    /** The payload of the last response named [name], or null if the tool produced none. */
    private fun responseFor(events: List<Event>, name: String): Map<String, Any?>? =
      events
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionResponse }
        .lastOrNull { it.name == name }
        ?.response

    private fun pendingLongRunningCall(events: List<Event>, name: String): FunctionCall? {
      val ids = events.flatMap { it.longRunningToolIds }.toSet()
      return events
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionCall }
        .firstOrNull { it.name == name && it.id in ids }
    }

    /** A user turn carrying the awaited [FunctionResponse] for a paused call. */
    private fun functionResponseTurn(name: String, id: String?, response: Map<String, Any>) =
      Content(
        role = "user",
        parts =
          listOf(
            Part(functionResponse = FunctionResponse(name = name, id = id, response = response))
          ),
      )
  }
}
