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

import com.google.adk.agents.CallbackContext as JavaCallbackContext
import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.agents.ReadonlyContext as JavaReadonlyContext
import com.google.adk.artifacts.InMemoryArtifactService as JavaInMemoryArtifactService
import com.google.adk.kt.agents.LlmAgent as KtLlmAgent
import com.google.adk.kt.apps.App as KtApp
import com.google.adk.kt.runners.InMemoryRunner as KtInMemoryRunner
import com.google.adk.kt.sessions.GetSessionConfig as KtGetSessionConfig
import com.google.adk.kt.sessions.SessionKey as KtSessionKey
import com.google.adk.kt.sessions.State as KtState
import com.google.adk.kt.types.Blob as KtBlob
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.kt.types.FileData as KtFileData
import com.google.adk.kt.types.FunctionCallingConfig as KtFunctionCallingConfig
import com.google.adk.kt.types.FunctionResponse as KtFunctionResponse
import com.google.adk.kt.types.GenerateContentConfig as KtConfig
import com.google.adk.kt.types.GenerationConfigRoutingConfig as KtRoutingConfig
import com.google.adk.kt.types.GenerationConfigRoutingConfigManualRoutingMode as KtManualRoutingMode
import com.google.adk.kt.types.GoogleMaps as KtGoogleMaps
import com.google.adk.kt.types.GoogleSearch as KtGoogleSearch
import com.google.adk.kt.types.HarmBlockThreshold as KtHarmBlockThreshold
import com.google.adk.kt.types.HarmCategory as KtHarmCategory
import com.google.adk.kt.types.MediaResolution as KtMediaResolution
import com.google.adk.kt.types.Part as KtPart
import com.google.adk.kt.types.Retrieval as KtRetrieval
import com.google.adk.kt.types.SafetySetting as KtSafetySetting
import com.google.adk.kt.types.Schema as KtSchema
import com.google.adk.kt.types.ServiceTier as KtServiceTier
import com.google.adk.kt.types.ThinkingConfig as KtThinkingConfig
import com.google.adk.kt.types.ThinkingLevel as KtThinkingLevel
import com.google.adk.kt.types.Tool as KtTool
import com.google.adk.kt.types.ToolConfig as KtToolConfig
import com.google.adk.kt.types.Type as KtType
import com.google.adk.kt.types.UrlContext as KtUrlContext
import com.google.adk.kt.types.VertexAISearch as KtVertexAISearch
import com.google.adk.kt.types.VertexAISearchDataStoreSpec as KtVertexAISearchDataStoreSpec
import com.google.adk.kt.types.VertexRagStore as KtVertexRagStore
import com.google.adk.kt.types.VertexRagStoreRagResource as KtVertexRagStoreRagResource
import com.google.adk.memory.InMemoryMemoryService as JavaInMemoryMemoryService
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.models.BaseLlmConnection as JavaBaseLlmConnection
import com.google.adk.models.LlmRequest as JavaLlmRequest
import com.google.adk.models.LlmResponse as JavaLlmResponse
import com.google.adk.plugins.BasePlugin as JavaBasePlugin
import com.google.adk.sessions.InMemorySessionService as JavaInMemorySessionService
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.BaseToolset as JavaBaseToolset
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.genai.types.Content as GenaiContent
import com.google.genai.types.CustomMetadata as GenaiCustomMetadata
import com.google.genai.types.FinishReason as GenaiFinishReason
import com.google.genai.types.FunctionCall as GenaiFunctionCall
import com.google.genai.types.FunctionDeclaration as GenaiFunctionDeclaration
import com.google.genai.types.GenerateContentResponseUsageMetadata as GenaiUsageMetadata
import com.google.genai.types.GroundingChunk as GenaiGroundingChunk
import com.google.genai.types.GroundingChunkRetrievedContext as GenaiGroundingChunkRetrievedContext
import com.google.genai.types.GroundingChunkWeb as GenaiGroundingChunkWeb
import com.google.genai.types.GroundingMetadata as GenaiGroundingMetadata
import com.google.genai.types.GroundingSupport as GenaiGroundingSupport
import com.google.genai.types.MediaModality as GenaiMediaModality
import com.google.genai.types.ModalityTokenCount as GenaiModalityTokenCount
import com.google.genai.types.Part as GenaiPart
import com.google.genai.types.PartialArg as GenaiPartialArg
import com.google.genai.types.RetrievalMetadata as GenaiRetrievalMetadata
import com.google.genai.types.Schema as GenaiSchema
import com.google.genai.types.SearchEntryPoint as GenaiSearchEntryPoint
import com.google.genai.types.Segment as GenaiSegment
import com.google.genai.types.VideoMetadata as GenaiVideoMetadata
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Exercises the Kotlin (engine) [KtInMemoryRunner] driving a Kotlin agent whose components are ADK
 * Java components adapted via [JavaAdkToKt] (forward interop).
 */
class KtRunnerInteropTest {

  /** A Java tool that echoes its args; used by the engine runner via [JavaAdkToKt.asKtTool]. */
  private class JavaEchoTool : JavaBaseTool("java_echo", "echoes args") {
    // A parameter schema covering every SchemaCodec facet: object + properties, nested array items,
    // an enum, and required.
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(
        GenaiFunctionDeclaration.builder()
          .name("java_echo")
          .description("echoes args")
          .parameters(
            GenaiSchema.builder()
              .type("OBJECT")
              .properties(
                mapOf(
                  "text" to
                    GenaiSchema.builder().type("STRING").description("text to echo").build(),
                  "tags" to
                    GenaiSchema.builder()
                      .type("ARRAY")
                      .items(GenaiSchema.builder().type("STRING").build())
                      .build(),
                  "mode" to
                    GenaiSchema.builder().type("STRING").enum_(listOf("upper", "lower")).build(),
                )
              )
              .required(listOf("text"))
              .description("echo arguments")
              .build()
          )
          .build()
      )

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> = Single.just(mapOf("echoed" to (args["text"] ?: "")))
  }

  /** A Java tool provided by a [JavaBaseToolset]; used via [JavaAdkToKt.asKtToolset]. */
  private class JavaToolsetTool : JavaBaseTool("toolset_tool", "from a toolset") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("toolset_tool").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> = Single.just(mapOf("from" to "toolset"))
  }

  /** A Java toolset exposing a single [JavaToolsetTool]. */
  private class JavaEchoToolset : JavaBaseToolset {
    override fun getTools(readonlyContext: JavaReadonlyContext?): Flowable<JavaBaseTool> {
      // Read through the readonly-context view so its accessors are exercised, mirroring a real
      // dynamic toolset that filters its tools by the invocation context.
      readonlyContext?.let { ctx ->
        val reads =
          listOf(
            ctx.userContent(),
            ctx.invocationId(),
            ctx.branch(),
            ctx.agentName(),
            ctx.userId(),
            ctx.sessionId(),
            ctx.events(),
            ctx.state(),
            runCatching { ctx.invocationContext() }.isFailure,
          )
        check(reads.isNotEmpty())
      }
      return Flowable.just<JavaBaseTool>(JavaToolsetTool())
    }

    override fun close() {}
  }

  /**
   * A Java tool that gates on human confirmation: it requests confirmation when none is present,
   * and proceeds once the resumed call carries one. Exercises the interop confirmation bridge.
   */
  private class JavaConfirmTool : JavaBaseTool("java_confirm", "requires confirmation") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("java_confirm").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> {
      val confirmation = toolContext.toolConfirmation()
      if (confirmation.isEmpty) {
        toolContext.requestConfirmation("please approve java_confirm")
        return Single.just(mapOf("status" to "pending"))
      }
      return Single.just(
        mapOf("status" to if (confirmation.get().confirmed()) "confirmed" else "rejected")
      )
    }
  }

  /**
   * A Java tool that mutates its [JavaToolContext.actions] in place (not via `setActions`) to set
   * every control-flow signal, exercising the live [KtEventActionsToJavaView] write-through.
   */
  private class JavaControlFlowTool :
    JavaBaseTool("java_control_flow", "sets control-flow actions") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("java_control_flow").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> {
      toolContext.actions().setEscalate(true)
      toolContext.actions().setSkipSummarization(true)
      toolContext.actions().setEndOfAgent(true)
      toolContext.actions().setTransferToAgent("b")
      return Single.just(mapOf("ok" to true))
    }
  }

  /**
   * A Java tool that writes to and removes from [JavaToolContext.state], exercising the Java ->
   * Kotlin removal-sentinel translation: it keeps one key and set-then-removes another.
   */
  private class JavaStateMutatingTool : JavaBaseTool("java_state_mutator", "mutates state") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("java_state_mutator").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> {
      toolContext.state()["kept"] = "yes"
      toolContext.state()["gone"] = "temp"
      val removed = toolContext.state().remove("gone")
      return Single.just(mapOf("removed" to (removed ?: "none")))
    }
  }

  /** A Java tool that echoes its running agent's name, exercising ToolContext.agentName(). */
  private class JavaAgentNameTool : JavaBaseTool("java_agent_name", "reports the agent name") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("java_agent_name").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> = Single.just(mapOf("agent" to toolContext.agentName()))
  }

  /** A Java plugin whose afterTool callback removes a state key via the live tool context. */
  private class ToolStateRemovingPlugin : JavaBasePlugin("tool_state_removing_plugin") {
    @JvmSuppressWildcards
    override fun afterToolCallback(
      tool: JavaBaseTool,
      toolArgs: Map<String, Any>,
      toolContext: JavaToolContext,
      result: Map<String, Any>,
    ): Maybe<Map<String, Any>> {
      toolContext.state()["kept3"] = "yes"
      toolContext.state()["gone3"] = "temp"
      val removed = toolContext.state().remove("gone3")
      return Maybe.just(mapOf("removed" to (removed ?: "none")))
    }
  }

  /** A Java plugin that counts how many invocations the runner started. */
  private class CountingJavaPlugin : JavaBasePlugin("counting_plugin") {
    var beforeRunCount = 0

    override fun beforeRunCallback(invocationContext: JavaInvocationContext): Maybe<GenaiContent> {
      beforeRunCount++
      return Maybe.empty()
    }
  }

  /**
   * A Java plugin that inspects the engine agent it is handed: it records the sub-agent names (the
   * wrapped tree must be visible) and tries to run it (which must fail, since the view is
   * inspection-only). It captures the error and lets the run go on.
   */
  private class AgentInspectingJavaPlugin : JavaBasePlugin("agent_inspecting_plugin") {
    var runError: Throwable? = null
    var subAgentNames: List<String> = emptyList()

    override fun beforeRunCallback(invocationContext: JavaInvocationContext): Maybe<GenaiContent> {
      val agent = invocationContext.agent()
      subAgentNames = agent.subAgents().map { it.name() }
      return agent
        .runAsync(invocationContext)
        .count()
        .flatMapMaybe { Maybe.empty<GenaiContent>() }
        .onErrorComplete { e ->
          runError = e
          true
        }
    }
  }

  /**
   * A Java plugin that saves an artifact from its model callback, exercising the artifact service
   * wired into the callback context.
   */
  private class ArtifactSavingJavaPlugin : JavaBasePlugin("artifact_saving_plugin") {
    var saved = false
    var saveError: Throwable? = null

    override fun beforeModelCallback(
      callbackContext: JavaCallbackContext,
      llmRequestBuilder: JavaLlmRequest.Builder,
    ): Maybe<JavaLlmResponse> =
      callbackContext
        .saveArtifact("note.txt", GenaiPart.fromText("hi"))
        .doOnComplete { saved = true }
        .toMaybe<JavaLlmResponse>()
        .onErrorComplete { e ->
          saveError = e
          true
        }
  }

  /** A Java model that replays a fixed sequence of responses, one per LLM step. */
  private class SequentialJavaModel(private val turns: List<GenaiContent>) :
    JavaBaseLlm("java-model") {
    private var step = 0

    override fun generateContent(
      llmRequest: JavaLlmRequest,
      stream: Boolean,
    ): Flowable<JavaLlmResponse> {
      val content = turns[step.coerceAtMost(turns.size - 1)]
      step++
      // Attach finish reason + usage metadata so LlmResponseCodec / UsageMetadataCodec are covered.
      return Flowable.just(
        JavaLlmResponse.builder()
          .content(content)
          .finishReason(GenaiFinishReason("STOP"))
          .usageMetadata(
            GenaiUsageMetadata.builder()
              .promptTokenCount(3)
              .candidatesTokenCount(5)
              .totalTokenCount(8)
              .thoughtsTokenCount(2)
              .toolUsePromptTokenCount(1)
              .cachedContentTokenCount(4)
              .promptTokensDetails(
                listOf(
                  GenaiModalityTokenCount.builder()
                    .modality(GenaiMediaModality("TEXT"))
                    .tokenCount(3)
                    .build()
                )
              )
              .build()
          )
          .customMetadata(
            listOf(
              GenaiCustomMetadata.builder().key("tag").stringValue("v1").build(),
              GenaiCustomMetadata.builder().key("score").numericValue(0.5f).build(),
            )
          )
          .build()
      )
    }

    override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
      throw UnsupportedOperationException()
  }

  @Test
  fun javaAdkToKt_convertsEntireCollections() {
    // Tools: order preserved, each Java tool wrapped as a Kotlin tool.
    val ktTools = JavaAdkToKt.asKtTools(listOf(JavaEchoTool(), JavaConfirmTool()))
    assertEquals(listOf("java_echo", "java_confirm"), ktTools.map { it.name })

    // Toolsets and plugins: size preserved.
    assertEquals(1, JavaAdkToKt.asKtToolsets(listOf(JavaEchoToolset())).size)
    assertEquals(1, JavaAdkToKt.asKtPlugins(listOf(CountingJavaPlugin())).size)

    // Empty collections convert to empty.
    assertTrue(JavaAdkToKt.asKtTools(emptyList()).isEmpty())
  }

  @Test
  fun ktRunner_runsAgent_withJavaModelAndJavaTool() = runBlocking {
    val model =
      SequentialJavaModel(
        listOf(
          modelFunctionCall("java_echo", mapOf("text" to "hi")),
          modelText("done from java model"),
        )
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "done from java model" } == true }
    )
    assertTrue(
      events.any { e -> e.content?.parts?.any { it.functionResponse?.name == "java_echo" } == true }
    )
  }

  @Test
  fun ktRunner_wrappedAgentIsInspectableButNotRunnable() = runBlocking {
    val plugin = AgentInspectingJavaPlugin()
    val subAgent =
      KtLlmAgent(
        name = "b",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("sub")))),
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("hello")))),
        subAgents = listOf(subAgent),
      )
    val runner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent = agent,
            plugins = listOf(JavaAdkToKt.asKtPlugin(plugin)),
          )
      )

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "hi"))
        .toList()

    // Inspection: the wrapped agent exposes the real sub-agent tree.
    assertEquals(listOf("b"), plugin.subAgentNames)
    // Execution: the wrapped agent is inspection-only, so running it must fail.
    assertTrue(
      plugin.runError is UnsupportedOperationException,
      "running the wrapped agent should be unsupported, got ${plugin.runError}",
    )
    // The run itself still completes normally.
    assertTrue(events.any { e -> e.content?.parts?.any { it.text == "hello" } == true })
  }

  @Test
  fun ktRunner_pluginCallbackCanUseArtifactService() = runBlocking {
    val plugin = ArtifactSavingJavaPlugin()
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("hi")))),
      )
    val runner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent = agent,
            plugins = listOf(JavaAdkToKt.asKtPlugin(plugin)),
          ),
        artifactService = JavaAdkToKt.asKtArtifactService(JavaInMemoryArtifactService()),
      )

    runner
      .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "hi"))
      .toList()

    // The callback context wires the artifact service, so saving from a model callback succeeds
    // rather than failing with "Artifact service is not initialized".
    assertTrue(
      plugin.saved,
      "saveArtifact in a model callback should complete; error=${plugin.saveError}",
    )
  }

  @Test
  fun ktRunner_drivesEveryJavaAdapter_endToEnd() = runBlocking {
    // The real backing services are ADK Java; the runner sees them through the forward adapters.
    val javaSessions = JavaInMemorySessionService()
    val javaArtifacts = JavaInMemoryArtifactService()
    val javaMemory = JavaInMemoryMemoryService()
    val plugin = CountingJavaPlugin()

    val model =
      SequentialJavaModel(
        listOf(
          modelFunctionCall("java_echo", mapOf("text" to "hi")),
          modelFunctionCall("toolset_tool", emptyMap()),
          modelText("done from java model"),
        )
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
        toolsets = listOf(JavaAdkToKt.asKtToolset(JavaEchoToolset())),
      )
    val runner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent = agent,
            plugins = listOf(JavaAdkToKt.asKtPlugin(plugin)),
          ),
        sessionService = JavaAdkToKt.asKtSessionService(javaSessions),
        artifactService = JavaAdkToKt.asKtArtifactService(javaArtifacts),
        memoryService = JavaAdkToKt.asKtMemoryService(javaMemory),
      )

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    // Model adapter: the final model text was produced.
    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "done from java model" } == true },
      "expected final model text",
    )
    // Tool adapter: the standalone Java tool ran.
    assertTrue(
      events.any { e ->
        e.content?.parts?.any { it.functionResponse?.name == "java_echo" } == true
      },
      "expected standalone tool to run",
    )
    // Toolset adapter: the Java toolset's tool ran.
    assertTrue(
      events.any { e ->
        e.content?.parts?.any { it.functionResponse?.name == "toolset_tool" } == true
      },
      "expected toolset tool to run",
    )
    // Plugin adapter: the Java plugin observed the run.
    assertTrue(plugin.beforeRunCount >= 1, "expected plugin beforeRun to fire")

    // Session-service adapter: the runner created and persisted the session through it.
    val key = KtSessionKey("app", "u", "s")
    val session = runner.sessionService.getSession(key)!!
    assertTrue(
      session.events.isNotEmpty(),
      "expected session persisted via adapted session service",
    )

    // Artifact-service adapter: save + load round-trip on the runner's adapted service.
    val version = runner.artifactService!!.saveArtifact(key, "note.txt", KtPart(text = "v1"))
    assertTrue(version >= 0, "expected an artifact version")
    val loaded = runner.artifactService!!.loadArtifact(key, "note.txt")
    assertEquals("v1", loaded?.text)

    // Memory-service adapter: index the run's text events (keyword search only handles text
    // parts), then keyword-search the model's reply.
    val textEvents =
      session.events.filter { e ->
        val parts = e.content?.parts
        parts != null && parts.isNotEmpty() && parts.all { !it.text.isNullOrEmpty() }
      }
    runner.memoryService!!.addSessionToMemory(session.copy(events = textEvents.toMutableList()))
    val hits = runner.memoryService!!.searchMemory("app", "u", "done")
    assertTrue(hits.memories.isNotEmpty(), "expected memory search to hit via adapted service")
  }

  @Test
  fun ktRunner_exercisesConfigSchemaUsagePartsAndServiceApis() = runBlocking {
    val javaSessions = JavaInMemorySessionService()
    val javaArtifacts = JavaInMemoryArtifactService()
    val model =
      SequentialJavaModel(
        listOf(
          GenaiContent.builder()
            .role("model")
            .parts(
              GenaiPart.builder()
                .functionCall(
                  GenaiFunctionCall.builder()
                    .name("java_echo")
                    .args(mapOf("text" to "hi"))
                    .willContinue(false)
                    .partialArgs(
                      listOf(
                        GenaiPartialArg.builder()
                          .stringValue("hi")
                          .jsonPath("$.text")
                          .willContinue(false)
                          .build()
                      )
                    )
                    .build()
                )
                .build()
            )
            .build(),
          GenaiContent.builder()
            .role("model")
            .parts(
              GenaiPart.builder()
                .text("clip")
                .videoMetadata(
                  GenaiVideoMetadata.builder()
                    .startOffset(java.time.Duration.ofSeconds(1))
                    .endOffset(java.time.Duration.ofSeconds(2))
                    .fps(24.0)
                    .build()
                )
                .build()
            )
            .build(),
          modelText("done rich"),
        )
      )
    // Rich generation config so GenerateContentConfigCodec.toJava covers its parameter branches.
    val ktConfig =
      KtConfig(
        systemInstruction = KtContent.fromText("system", "be helpful"),
        temperature = 0.5f,
        topP = 0.9f,
        topK = 40,
        candidateCount = 1,
        maxOutputTokens = 256,
        stopSequences = listOf("STOP"),
        responseMimeType = "text/plain",
        responseSchema = KtSchema(type = KtType.STRING),
        labels = mapOf("env" to "test"),
        presencePenalty = 0.1f,
        frequencyPenalty = 0.2f,
        responseLogprobs = true,
        mediaResolution = KtMediaResolution.MEDIA_RESOLUTION_LOW,
        serviceTier = KtServiceTier.STANDARD,
        routingConfig =
          KtRoutingConfig(manualMode = KtManualRoutingMode(modelName = "router-model")),
        toolConfig =
          KtToolConfig(
            functionCallingConfig =
              KtFunctionCallingConfig(
                allowedFunctionNames = listOf("java_echo"),
                streamFunctionCallArguments = true,
              )
          ),
        safetySettings =
          listOf(
            KtSafetySetting(
              category = KtHarmCategory.HARM_CATEGORY_HATE_SPEECH,
              threshold = KtHarmBlockThreshold.BLOCK_ONLY_HIGH,
            )
          ),
        thinkingConfig =
          KtThinkingConfig(
            includeThoughts = true,
            thinkingBudget = 128,
            thinkingLevel = KtThinkingLevel.MEDIUM,
          ),
        tools =
          listOf(
            KtTool(googleSearch = KtGoogleSearch(excludeDomains = listOf("example.com"))),
            KtTool(googleMaps = KtGoogleMaps(enableWidget = true)),
            KtTool(urlContext = KtUrlContext()),
            KtTool(
              retrieval =
                KtRetrieval(
                  vertexAiSearch =
                    KtVertexAISearch(
                      datastore = "ds",
                      engine = "eng",
                      filter = "f",
                      maxResults = 5,
                      dataStoreSpecs =
                        listOf(KtVertexAISearchDataStoreSpec(dataStore = "d", filter = "df")),
                    )
                )
            ),
            KtTool(
              retrieval =
                KtRetrieval(
                  vertexRagStore =
                    KtVertexRagStore(
                      ragCorpora = listOf("corpora/1"),
                      ragResources =
                        listOf(
                          KtVertexRagStoreRagResource(
                            ragCorpus = "corpora/2",
                            ragFileIds = listOf("f1"),
                          )
                        ),
                      similarityTopK = 3,
                      vectorDistanceThreshold = 0.7,
                    )
                )
            ),
          ),
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
        generateContentConfig = ktConfig,
      )
    val runner =
      KtInMemoryRunner(
        app = KtApp(appName = "app", rootAgent = agent),
        sessionService = JavaAdkToKt.asKtSessionService(javaSessions),
        artifactService = JavaAdkToKt.asKtArtifactService(javaArtifacts),
      )

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()
    assertTrue(events.isNotEmpty(), "expected the rich-config run to produce events")

    val key = KtSessionKey("app", "u", "s")

    // PartCodec inlineData + fileData round-trips through the adapted artifact service.
    val inlineVersion =
      runner.artifactService!!.saveArtifact(
        key,
        "img.png",
        KtPart(inlineData = KtBlob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
      )
    assertTrue(inlineVersion >= 0)
    val fileVersion =
      runner.artifactService!!.saveArtifact(
        key,
        "doc.txt",
        KtPart(fileData = KtFileData(fileUri = "gs://b/doc.txt", mimeType = "text/plain")),
      )
    assertTrue(fileVersion >= 0)
    val loadedInline = runner.artifactService!!.loadArtifact(key, "img.png")
    assertEquals("image/png", loadedInline?.inlineData?.mimeType)

    // Artifact service list / versions / delete.
    assertTrue(runner.artifactService!!.listArtifactKeys(key).contains("img.png"))
    assertTrue(runner.artifactService!!.listVersions(key, "img.png").isNotEmpty())
    runner.artifactService!!.deleteArtifact(key, "img.png")

    // Session service list / getSession(config) / listEvents / delete.
    assertTrue(runner.sessionService.listSessions("app", "u").sessions.isNotEmpty())
    val withConfig = runner.sessionService.getSession(key, KtGetSessionConfig(numRecentEvents = 1))
    assertTrue(withConfig != null, "expected getSession with a config")
    assertTrue(runner.sessionService.listEvents(key).events.size >= 0)
    runner.sessionService.deleteSession(key)
  }

  @Test
  fun ktRunner_runsMultipleTurns_accumulatingHistory() = runBlocking {
    // Records how many contents (history) the Java model sees on each turn.
    val requestContentSizes = mutableListOf<Int>()
    val model =
      object : JavaBaseLlm("java-model") {
        private var step = 0

        override fun generateContent(
          llmRequest: JavaLlmRequest,
          stream: Boolean,
        ): Flowable<JavaLlmResponse> {
          requestContentSizes.add(llmRequest.contents().size)
          return Flowable.just(
            JavaLlmResponse.builder().content(modelText("reply ${++step}")).build()
          )
        }

        override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
          throw UnsupportedOperationException()
      }
    val runner =
      KtInMemoryRunner(KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(model)), "app")

    suspend fun turn(text: String) =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", text))
        .toList()

    val turn1 = turn("first")
    val turn2 = turn("second")
    val turn3 = turn("third")

    assertTrue(turn1.any { e -> e.content?.parts?.any { it.text == "reply 1" } == true })
    assertTrue(turn2.any { e -> e.content?.parts?.any { it.text == "reply 2" } == true })
    assertTrue(turn3.any { e -> e.content?.parts?.any { it.text == "reply 3" } == true })

    // Each turn's request carries the history accumulated from prior turns (read back through the
    // session service and converted by ContentCodec / EventCodec).
    assertEquals(3, requestContentSizes.size)
    assertTrue(requestContentSizes[1] > requestContentSizes[0], "turn 2 should see more history")
    assertTrue(requestContentSizes[2] > requestContentSizes[1], "turn 3 should see more history")

    // All three turns' events are persisted on the one session.
    val session = runner.sessionService.getSession(KtSessionKey("app", "u", "s"))!!
    assertTrue(session.events.size >= 6, "expected user+model events across 3 turns")
  }

  @Test
  fun ktRunner_javaToolConfirmation_requestAndResume() = runBlocking {
    val model =
      SequentialJavaModel(
        listOf(modelFunctionCall("java_confirm", emptyMap()), modelText("confirmed done"))
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaConfirmTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    // Turn 1: the model calls the gated Java tool; it calls requestConfirmation(), so JavaToolToKt
    // copies the request onto the Kotlin actions and the engine emits an adk_request_confirmation
    // call and pauses.
    val turn1 =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()
    val confirmationId =
      turn1
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionCall }
        .firstOrNull { it.name == "adk_request_confirmation" }
        ?.id
    assertTrue(confirmationId != null, "turn 1 should emit an adk_request_confirmation call")

    // Turn 2: resume by sending the user's approval as a function response to that call.
    val turn2 =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage =
            KtContent(
              role = "user",
              parts =
                listOf(
                  KtPart(
                    functionResponse =
                      KtFunctionResponse(
                        name = "adk_request_confirmation",
                        id = confirmationId,
                        response = mapOf("confirmed" to true),
                      )
                  )
                ),
            ),
        )
        .toList()

    // On resume, ktToolContextToJava carries the confirmation to the Java tool (via
    // ToolConfirmationCodec.toJava), so it proceeds and reports "confirmed".
    val confirmResponse =
      turn2
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionResponse }
        .firstOrNull { it.name == "java_confirm" }
    assertEquals("confirmed", confirmResponse?.response?.get("status"))
  }

  @Test
  fun ktRunner_javaToolMutatingLiveActions_propagatesControlFlow() = runBlocking {
    // The tool transfers to this sub-agent; its reply proves transferToAgent propagated live.
    val subAgent =
      KtLlmAgent(
        name = "b",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("transferred reply")))),
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("java_control_flow", emptyMap()), modelText("a-final"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(JavaControlFlowTool())),
        subAgents = listOf(subAgent),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    // The Java tool mutated toolContext.actions() in place; the live KtEventActionsToJavaView must
    // carry every control-flow signal onto the Kotlin function-response event.
    val actionEvent = events.firstOrNull { it.actions.transferToAgent == "b" }
    assertTrue(
      actionEvent != null,
      "expected a function-response event carrying the tool's actions",
    )
    assertTrue(actionEvent.actions.escalate, "escalate should propagate")
    assertTrue(actionEvent.actions.skipSummarization, "skipSummarization should propagate")
    assertTrue(actionEvent.actions.endOfAgent, "endOfAgent should propagate")

    // The transfer executed (findAgent("b") + ran it), confirming transferToAgent took effect.
    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "transferred reply" } == true },
      "expected the transferred-to agent to run",
    )
  }

  @Test
  fun ktRunner_receivesGroundingFromJavaModel() = runBlocking {
    val grounding =
      GenaiGroundingMetadata.builder()
        .webSearchQueries(listOf("adk kotlin"))
        .groundingChunks(
          listOf(
            GenaiGroundingChunk.builder()
              .web(GenaiGroundingChunkWeb.builder().uri("https://x.test").domain("x.test").build())
              .build(),
            GenaiGroundingChunk.builder()
              .retrievedContext(
                GenaiGroundingChunkRetrievedContext.builder().uri("gs://c").text("ctx").build()
              )
              .build(),
          )
        )
        .groundingSupports(
          listOf(
            GenaiGroundingSupport.builder()
              .segment(GenaiSegment.builder().startIndex(0).endIndex(5).text("hello").build())
              .groundingChunkIndices(listOf(0))
              .confidenceScores(listOf(0.9f))
              .build()
          )
        )
        .searchEntryPoint(GenaiSearchEntryPoint.builder().renderedContent("<div/>").build())
        .retrievalMetadata(
          GenaiRetrievalMetadata.builder().googleSearchDynamicRetrievalScore(0.5f).build()
        )
        .build()
    val model =
      object : JavaBaseLlm("java-model") {
        override fun generateContent(
          llmRequest: JavaLlmRequest,
          stream: Boolean,
        ): Flowable<JavaLlmResponse> =
          Flowable.just(
            JavaLlmResponse.builder()
              .content(modelText("grounded"))
              .groundingMetadata(grounding)
              .build()
          )

        override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
          throw UnsupportedOperationException()
      }
    val runner =
      KtInMemoryRunner(KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(model)), "app")

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    // A grounded Java model response is consumed end-to-end: LlmResponseCodec.fromJava +
    // GroundingMetadataCodec.fromJava convert the full grounding payload (chunks, supports,
    // segment,
    // search entry point, retrieval metadata) while the Kotlin agent produces its reply. The Kotlin
    // engine does not re-surface a response's grounding on the emitted event, so this asserts the
    // reply is produced rather than grounding fields on the event.
    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "grounded" } == true },
      "expected the grounded model reply",
    )
  }

  @Test
  fun ktRunner_bridgedNoOpPlugin_preservesAgentTransfer() = runBlocking {
    // Regression: bridging any Java plugin must not drop the request's Kotlin-only toolsDict in
    // beforeModel, or the request-scoped transfer_to_agent tool disappears and transfer breaks.
    val subAgent =
      KtLlmAgent(
        name = "b",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("transferred reply")))),
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(
                modelFunctionCall("transfer_to_agent", mapOf("agent_name" to "b")),
                modelText("a-final"),
              )
            )
          ),
        subAgents = listOf(subAgent),
      )
    val runner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent = agent,
            plugins = listOf(JavaAdkToKt.asKtPlugin(CountingJavaPlugin())),
          )
      )

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "transferred reply" } == true },
      "transfer_to_agent should still work when a Java plugin is bridged",
    )
  }

  @Test
  fun ktRunner_javaToolStateRemoval_appliesDeletion() = runBlocking {
    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("java_state_mutator", emptyMap()), modelText("done"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(JavaStateMutatingTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    // The tool's removal writes the Java sentinel into the live delta; it must be translated to the
    // Kotlin one so the engine deletes the key rather than persisting a foreign sentinel.
    val deltaEvent = events.firstOrNull { it.actions.stateDelta.containsKey("gone") }
    assertTrue(deltaEvent != null, "expected an event carrying the tool's state delta")
    assertTrue(
      deltaEvent.actions.stateDelta["gone"] === KtState.REMOVED,
      "the Java removal sentinel should be translated to the Kotlin State.REMOVED",
    )

    val session = runner.sessionService.getSession(KtSessionKey("app", "u", "s"))!!
    assertEquals("yes", session.state["kept"], "a kept key should persist")
    assertTrue(!session.state.containsKey("gone"), "a removed key should not be in state")
  }

  @Test
  fun ktRunner_forwardsCachedContentToJavaModel() = runBlocking {
    // Regression: GenerateContentConfig.cachedContent must reach the Java model so context caching
    // is not silently disabled.
    var receivedCachedContent: String? = null
    val model =
      object : JavaBaseLlm("java-model") {
        override fun generateContent(
          llmRequest: JavaLlmRequest,
          stream: Boolean,
        ): Flowable<JavaLlmResponse> {
          receivedCachedContent = llmRequest.config().getOrNull()?.cachedContent()?.getOrNull()
          return Flowable.just(JavaLlmResponse.builder().content(modelText("hi")).build())
        }

        override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
          throw UnsupportedOperationException()
      }
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        generateContentConfig = KtConfig(cachedContent = "cachedContents/abc"),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    runner
      .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
      .toList()

    assertEquals("cachedContents/abc", receivedCachedContent)
  }

  @Test
  fun ktArtifactService_saveAndReloadArtifact_roundTrips() = runBlocking {
    val service = JavaAdkToKt.asKtArtifactService(JavaInMemoryArtifactService())
    val key = KtSessionKey("app", "u", "s")

    val reloaded = service.saveAndReloadArtifact(key, "note.txt", KtPart(text = "v1"))

    assertEquals("v1", reloaded.text)
  }

  @Test
  fun ktRunner_preservesThoughtOnlyPartRoundTrip() = runBlocking {
    // A model response part carrying ONLY a thoughtSignature (no text/functionCall) must survive
    // both directions: Java model -> Kotlin event (fromJava) and Kotlin history -> Java request
    // (toJava). Dropping it breaks Gemini thinking continuity.
    val requests = mutableListOf<JavaLlmRequest>()
    val model =
      object : JavaBaseLlm("java-model") {
        private var step = 0

        override fun generateContent(
          llmRequest: JavaLlmRequest,
          stream: Boolean,
        ): Flowable<JavaLlmResponse> {
          requests += llmRequest
          val content =
            if (step++ == 0)
              GenaiContent.builder()
                .role("model")
                .parts(
                  listOf(
                    GenaiPart.builder().thought(true).thoughtSignature("sig".toByteArray()).build(),
                    GenaiPart.builder()
                      .functionCall(
                        GenaiFunctionCall.builder().name("java_echo").args(emptyMap()).build()
                      )
                      .build(),
                  )
                )
                .build()
            else modelText("done")
          return Flowable.just(JavaLlmResponse.builder().content(content).build())
        }

        override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
          throw UnsupportedOperationException()
      }
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    // fromJava: the thought-only part survives onto the emitted model event.
    assertTrue(
      events.any { e -> e.content?.parts?.any { it.thoughtSignature != null } == true },
      "thought-only part should survive Java model -> Kotlin event",
    )
    // toJava: the thought-only part survives in the history sent back on the next turn.
    assertTrue(
      requests.last().contents().any { c ->
        c.parts().getOrNull().orEmpty().any { it.thoughtSignature().isPresent }
      },
      "thought-only part should survive Kotlin history -> Java request",
    )
  }

  @Test
  fun ktRunner_javaToolReadsAgentName() = runBlocking {
    // A Java tool reading ToolContext.agentName() must not NPE: the tool-path invocation-context
    // view wires the agent.
    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("java_agent_name", emptyMap()), modelText("done"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(JavaAgentNameTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events =
      runner
        .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
        .toList()

    val response =
      events
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionResponse }
        .firstOrNull { it.name == "java_agent_name" }
    assertEquals("a", response?.response?.get("agent"))
  }

  @Test
  fun ktRunner_pluginAfterToolStateRemoval_appliesDeletion() = runBlocking {
    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("java_echo", mapOf("text" to "hi")), modelText("done"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
      )
    val runner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent = agent,
            plugins = listOf(JavaAdkToKt.asKtPlugin(ToolStateRemovingPlugin())),
          )
      )

    runner
      .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
      .toList()

    // The plugin's afterTool removal writes the Java sentinel into the live delta; it must be
    // translated so the engine deletes the key rather than persisting a foreign sentinel.
    val session = runner.sessionService.getSession(KtSessionKey("app", "u", "s"))!!
    assertEquals("yes", session.state["kept3"], "a kept key should persist")
    assertTrue(
      !session.state.containsKey("gone3"),
      "a key removed in a plugin afterTool callback should not be in state",
    )
  }

  private companion object {
    fun modelFunctionCall(name: String, args: Map<String, Any>): GenaiContent =
      GenaiContent.builder()
        .role("model")
        .parts(
          GenaiPart.builder()
            .functionCall(GenaiFunctionCall.builder().name(name).args(args).build())
            .build()
        )
        .build()

    fun modelText(text: String): GenaiContent =
      GenaiContent.builder().role("model").parts(GenaiPart.builder().text(text).build()).build()
  }
}
