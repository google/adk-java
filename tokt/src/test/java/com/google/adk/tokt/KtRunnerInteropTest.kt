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

import com.google.adk.agents.BaseAgent as JavaBaseAgent
import com.google.adk.agents.CallbackContext as JavaCallbackContext
import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.agents.LiveRequestQueue
import com.google.adk.agents.ReadonlyContext as JavaReadonlyContext
import com.google.adk.agents.RunConfig as JavaRunConfig
import com.google.adk.artifacts.InMemoryArtifactService as JavaInMemoryArtifactService
import com.google.adk.events.Event as JavaEvent
import com.google.adk.events.EventActions as JavaEventActions
import com.google.adk.events.EventCompaction as JavaEventCompaction
import com.google.adk.kt.agents.LlmAgent as KtLlmAgent
import com.google.adk.kt.agents.RunConfig as KtRunConfig
import com.google.adk.kt.agents.StreamingMode as KtStreamingMode
import com.google.adk.kt.apps.App as KtApp
import com.google.adk.kt.callbacks.AfterModelCallback as KtAfterModelCallback
import com.google.adk.kt.models.LlmResponse as KtLlmResponse
import com.google.adk.kt.runners.InMemoryRunner as KtInMemoryRunner
import com.google.adk.kt.runners.Runner as KtRunner
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
import com.google.adk.kt.types.PartialArgValue as KtPartialArgValue
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
import com.google.adk.plugins.PluginManager as JavaPluginManager
import com.google.adk.runner.Runner as JavaRunner
import com.google.adk.sessions.BaseSessionService as JavaBaseSessionService
import com.google.adk.sessions.GetSessionConfig as JavaGetSessionConfig
import com.google.adk.sessions.InMemorySessionService as JavaInMemorySessionService
import com.google.adk.sessions.ListEventsResponse as JavaListEventsResponse
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.sessions.State as JavaState
import com.google.adk.tokt.codecs.FunctionDeclarationCodec
import com.google.adk.tokt.codecs.GroundingMetadataCodec
import com.google.adk.tokt.codecs.PartCodec
import com.google.adk.tokt.codecs.SchemaCodec
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.BaseToolset as JavaBaseToolset
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.genai.types.Content as GenaiContent
import com.google.genai.types.CustomMetadata as GenaiCustomMetadata
import com.google.genai.types.ExecutableCode as GenaiExecutableCode
import com.google.genai.types.FinishReason as GenaiFinishReason
import com.google.genai.types.FunctionCall as GenaiFunctionCall
import com.google.genai.types.FunctionDeclaration as GenaiFunctionDeclaration
import com.google.genai.types.GenerateContentResponseUsageMetadata as GenaiUsageMetadata
import com.google.genai.types.GroundingChunk as GenaiGroundingChunk
import com.google.genai.types.GroundingChunkMaps as GenaiGroundingChunkMaps
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
import com.google.genai.types.ToolCall as GenaiToolCall
import com.google.genai.types.ToolResponse as GenaiToolResponse
import com.google.genai.types.TrafficType as GenaiTrafficType
import com.google.genai.types.VideoMetadata as GenaiVideoMetadata
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.optionals.getOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Runs one user turn on the shared test session and collects the events it emits. */
@CanIgnoreReturnValue
private suspend fun KtInMemoryRunner.turn(text: String = "go") =
  runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", text)).toList()

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
        // Pin the values, not just the fact that the accessors returned: a toolset filtering by
        // context is the reason ReadonlyContext is bridged at all.
        check(ctx.agentName() == "a") { "unexpected agentName: ${ctx.agentName()}" }
        check(ctx.userId() == "u") { "unexpected userId: ${ctx.userId()}" }
        check(ctx.sessionId() == "s") { "unexpected sessionId: ${ctx.sessionId()}" }
        check(reads.last() == true) { "invocationContext() should be unavailable here" }
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

  /**
   * A Java tool that replaces its actions wholesale via `setActions(...)`, the way a Java tool that
   * builds its own `EventActions` does, rather than mutating the live ones.
   */
  private class JavaActionsReplacingTool :
    JavaBaseTool("replace_actions", "replaces its EventActions wholesale") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("replace_actions").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> {
      toolContext.setActions(
        JavaEventActions.builder()
          .stateDelta(mutableMapOf<String, Any>("replaced_state" to "from_actions"))
          .artifactDelta(mutableMapOf<String, Int>("replaced_artifact.txt" to 7))
          .build()
      )
      return Single.just(mapOf("ok" to true))
    }
  }

  /**
   * A Java plugin that writes and removes state from the agent-level callbacks. Both callbacks are
   * covered because each reconciles the removal sentinel independently.
   */
  private class AgentStateRemovingPlugin : JavaBasePlugin("agent_state_removing_plugin") {
    override fun beforeAgentCallback(
      agent: JavaBaseAgent,
      callbackContext: JavaCallbackContext,
    ): Maybe<GenaiContent> = removeIn(callbackContext, "before")

    override fun afterAgentCallback(
      agent: JavaBaseAgent,
      callbackContext: JavaCallbackContext,
    ): Maybe<GenaiContent> = removeIn(callbackContext, "after")

    private fun removeIn(context: JavaCallbackContext, phase: String): Maybe<GenaiContent> {
      context.state()["agent_kept_$phase"] = "yes"
      context.state()["agent_gone_$phase"] = "temp"
      val unused = context.state().remove("agent_gone_$phase")
      return Maybe.empty()
    }
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

  /**
   * A Java tool driving the three `EventActions` mutators the live view inherits rather than
   * overrides. Each writes a private field on the Java base class that the overridden getters do
   * not read, so a view missing the override loses the write with no error.
   */
  private class JavaInheritedActionMutatorsTool :
    JavaBaseTool("inherited_mutators", "exercises inherited EventActions mutators") {
    override fun declaration(): Optional<GenaiFunctionDeclaration> =
      Optional.of(GenaiFunctionDeclaration.builder().name("inherited_mutators").build())

    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> {
      toolContext.actions().stateDelta()["doomed"] = "temp"
      toolContext.actions().removeStateByKey("doomed")
      toolContext.actions().setArtifactDelta(mapOf("replaced.txt" to 9))
      toolContext
        .actions()
        .setCompaction(
          JavaEventCompaction.builder()
            .startTimestamp(1L)
            .endTimestamp(2L)
            .compactedContent(modelText("summary"))
            .build()
        )
      return Single.just(mapOf("ok" to true))
    }
  }

  /** A Java plugin that records the run config it is handed. */
  private class RunConfigCapturingJavaPlugin : JavaBasePlugin("run_config_plugin") {
    var seen: JavaRunConfig? = null

    override fun beforeRunCallback(invocationContext: JavaInvocationContext): Maybe<GenaiContent> {
      seen = invocationContext.runConfig()
      return Maybe.empty()
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

  /** A Java plugin that tries to re-scope the branch of the invocation it is handed. */
  private class BranchSettingJavaPlugin : JavaBasePlugin("branch_setting_plugin") {
    var error: Throwable? = null

    override fun beforeRunCallback(invocationContext: JavaInvocationContext): Maybe<GenaiContent> {
      try {
        invocationContext.branch("custom-branch")
      } catch (e: UnsupportedOperationException) {
        error = e
      }
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

    /** Every request the Kotlin engine converted for this model, for codec assertions. */
    val requests = CopyOnWriteArrayList<JavaLlmRequest>()

    override fun generateContent(
      llmRequest: JavaLlmRequest,
      stream: Boolean,
    ): Flowable<JavaLlmResponse> {
      requests.add(llmRequest)
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
              .toolUsePromptTokensDetails(
                listOf(
                  GenaiModalityTokenCount.builder()
                    .modality(GenaiMediaModality.Known.TEXT)
                    .tokenCount(1)
                    .build()
                )
              )
              .trafficType(GenaiTrafficType.Known.ON_DEMAND)
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

  /** A Kotlin runner that records whether [close] was called, delegating everything else. */
  private class ClosingSpyKtRunner(private val delegate: KtRunner) : KtRunner by delegate {
    var closed = false

    override fun close() {
      closed = true
      delegate.close()
    }
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
  fun ktRunner_stateDeltaWrittenToAnAdaptedJavaSessionService_mapsOnlyTheRemovalSentinel() =
    runBlocking {
      // Kotlin event -> Java conversion must translate the Kotlin removal sentinel to the Java one
      // and leave every other value alone. Asserting both halves: translating unconditionally
      // would turn an ordinary value into a deletion.
      val javaSessions = JavaInMemorySessionService()
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
            ),
          sessionService = JavaAdkToKt.asKtSessionService(javaSessions),
        )

      runner.turn()

      val stored =
        javaSessions.getSession("app", "u", "s", Optional.empty()).blockingGet()
          ?: fail("the adapted Java service should hold the session")
      val deltas = stored.events().map { it.actions().stateDelta() }
      assertEquals(
        "yes",
        deltas.firstNotNullOfOrNull { it["kept3"] },
        "an ordinary value must cross unchanged, not become a deletion",
      )
      assertSame(
        JavaState.REMOVED,
        deltas.firstNotNullOfOrNull { it["gone3"] },
        "the Kotlin removal sentinel must be translated to the Java one",
      )
    }

  @Test
  fun ktRunner_modelPartCarryingOnlyAnUnmappedKind_isDropped() = runBlocking {
    // executableCode has no Kotlin counterpart, so a part carrying only it must be dropped rather
    // than surviving as an empty part. This is what makes the primary-payload branches in
    // PartCodec.fromJava observable: a part's thought/metadata fields are re-attached afterwards
    // either way, so the drop is the only externally visible difference.
    val model =
      object : JavaBaseLlm("java-model") {
        override fun generateContent(
          llmRequest: JavaLlmRequest,
          stream: Boolean,
        ): Flowable<JavaLlmResponse> =
          Flowable.just(
            JavaLlmResponse.builder()
              .content(
                GenaiContent.builder()
                  .role("model")
                  .parts(
                    listOf(
                      GenaiPart.builder()
                        .executableCode(GenaiExecutableCode.builder().code("print(1)").build())
                        .build(),
                      GenaiPart.builder().text("done").build(),
                    )
                  )
                  .build()
              )
              .build()
          )

        override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
          throw UnsupportedOperationException()
      }
    val agent = KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(model))
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events = runner.turn()

    val parts = events.firstNotNullOfOrNull { it.content?.parts?.takeIf { p -> p.isNotEmpty() } }
    assertEquals(
      listOf("done"),
      parts?.map { it.text },
      "the executableCode-only part should be dropped, leaving just the text part",
    )
  }

  @Test
  fun asJavaRunner_runsAKotlinRunner_throughTheJavaRunnerApi() {
    // A Kotlin-engine runner, wrapped and then driven exactly like an ADK Java Runner.
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(
            name = "a",
            model =
              JavaAdkToKt.asKtModel(
                SequentialJavaModel(listOf(modelText("done from the kotlin engine")))
              ),
          ),
        appName = "app",
      )
    val javaRunner: JavaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    val events: List<JavaEvent> =
      javaRunner
        .runAsync(
          "u",
          "s",
          GenaiContent.builder().role("user").parts(GenaiPart.fromText("hi")).build(),
          JavaRunConfig.builder().autoCreateSession(true).build(),
        )
        .toList()
        .blockingGet()

    // Events come back Java-shaped (genai Content), converted from the Kotlin engine's output.
    assertEquals(
      "done from the kotlin engine",
      events.firstNotNullOfOrNull {
        it.content().getOrNull()?.parts()?.getOrNull()?.firstOrNull()?.text()?.getOrNull()
      },
      "the Java-facing runAsync should stream the Kotlin engine's events",
    )
    assertEquals("app", javaRunner.appName(), "appName should report the Kotlin runner's")

    // The run persisted through the Kotlin runner's own session service, readable via the
    // Java-facing accessor (the reverse session-service adapter).
    val session =
      javaRunner.sessionService().getSession("app", "u", "s", Optional.empty()).blockingGet()
    assertTrue(
      session != null && session.events().isNotEmpty(),
      "the run should be persisted and visible through the Java sessionService()",
    )
  }

  @Test
  fun asJavaRunner_nonDefaultRunConfig_reachesTheKotlinEngine() {
    // A Java RunConfig set through the Java API must map onto the Kotlin engine; the plugin reads
    // the config the engine actually received.
    val plugin = RunConfigCapturingJavaPlugin()
    val ktRunner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent =
              KtLlmAgent(
                name = "a",
                model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
              ),
            plugins = listOf(JavaAdkToKt.asKtPlugin(plugin)),
          )
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    val events =
      javaRunner
        .runAsync(
          "u",
          "s",
          GenaiContent.builder().role("user").parts(GenaiPart.fromText("hi")).build(),
          JavaRunConfig.builder()
            .streamingMode(JavaRunConfig.StreamingMode.SSE)
            .maxLlmCalls(7)
            .autoCreateSession(true)
            .build(),
        )
        .toList()
        .blockingGet()

    assertTrue(events.isNotEmpty(), "the run should have produced events")
    val seen = assertNotNull(plugin.seen, "the plugin should have been handed the run config")
    assertEquals(JavaRunConfig.StreamingMode.SSE, seen.streamingMode(), "streamingMode should map")
    assertEquals(7, seen.maxLlmCalls(), "maxLlmCalls should map")
  }

  @Test
  fun asJavaRunner_runLiveErrors_andAgentReturnsAnInspectionView() {
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList()))),
        appName = "app",
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    // Live mode is not bridged; it must fail loudly through the stream (like the base Runner), not
    // throw eagerly at the call site.
    assertFailsWith<UnsupportedOperationException> {
      javaRunner
        .runLive("u", "s", LiveRequestQueue(), JavaRunConfig.builder().build())
        .toList()
        .blockingGet()
    }
    // agent() returns an inspection-only view of the Kotlin agent - readable, but not runnable.
    assertEquals("a", javaRunner.agent().name(), "agent() should expose the Kotlin agent's name")
  }

  @Test
  fun asJavaRunner_pluginManager_listsUnwrappedJavaPlugins() {
    // A Java plugin adapted onto the Kotlin runner is unwrapped back to the original instance, so a
    // Java caller (e.g. an AgentTool with includePlugins) sees the real plugins.
    val plugin = RunConfigCapturingJavaPlugin()
    val ktRunner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent =
              KtLlmAgent(
                name = "a",
                model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList())),
              ),
            plugins = listOf(JavaAdkToKt.asKtPlugin(plugin)),
          )
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    assertSame(
      plugin,
      javaRunner.pluginManager().getPlugin("run_config_plugin").getOrNull(),
      "the adapted Java plugin should be unwrapped to the original instance",
    )
  }

  @Test
  fun asJavaRunner_pluginManager_registrationThrows() {
    // The Kotlin engine's plugins are fixed at construction, so a late Java-side registration must
    // fail loudly rather than silently never run.
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList()))),
        appName = "app",
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    assertFailsWith<UnsupportedOperationException> {
      javaRunner.pluginManager().registerPlugin(CountingJavaPlugin())
    }
  }

  @Test
  fun asJavaRunner_artifactService_present_isBridgedFaithfully() {
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList()))),
        appName = "app",
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    // The runner's in-memory artifact service is bridged back faithfully: a save is visible to a
    // later load through the same service.
    val artifacts =
      assertNotNull(javaRunner.artifactService(), "artifactService() should be present")
    val version =
      artifacts.saveArtifact("app", "u", "s", "note.txt", GenaiPart.fromText("v1")).blockingGet()
    assertEquals(
      "v1",
      artifacts.loadArtifact("app", "u", "s", "note.txt").blockingGet()?.text()?.orElse(""),
      "a bridged artifact save (version $version) should be readable through the same service",
    )
  }

  @Test
  fun asJavaRunner_artifactService_absent_isNull() {
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList()))),
        appName = "app",
        artifactService = null,
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    // No artifact service on the Kotlin runner: reported as null, mirroring its nullable field.
    assertNull(
      javaRunner.artifactService(),
      "artifactService() should be null when the Kotlin runner has none",
    )
  }

  @Test
  fun asJavaRunner_memoryService_absent_isNull() {
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList()))),
        appName = "app",
        memoryService = null,
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    assertNull(
      javaRunner.memoryService(),
      "memoryService() should be null when the Kotlin runner has none",
    )
  }

  @Test
  fun asJavaRunner_memoryService_present_isBridgedFaithfully() {
    val ktRunner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(
            name = "a",
            model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("Paris")))),
          ),
        appName = "app",
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    // Run once so the Kotlin runner persists a session, then index and search it back through the
    // bridged Java memoryService() - present, and round-tripping faithfully.
    javaRunner
      .runAsync(
        "u",
        "s",
        GenaiContent.builder().role("user").parts(GenaiPart.fromText("hi")).build(),
        JavaRunConfig.builder().autoCreateSession(true).build(),
      )
      .blockingSubscribe()
    val memory = assertNotNull(javaRunner.memoryService(), "memoryService() should be present")
    val session =
      javaRunner.sessionService().getSession("app", "u", "s", Optional.empty()).blockingGet()!!
    memory.addSessionToMemory(session).blockingAwait()

    assertTrue(
      memory.searchMemory("app", "u", "Paris").blockingGet().memories().isNotEmpty(),
      "a session indexed through the bridged memoryService() should be keyword-searchable",
    )
  }

  @Test
  fun asJavaRunner_close_closesTheKotlinRunner() {
    val ktRunner =
      ClosingSpyKtRunner(
        KtInMemoryRunner(
          agent =
            KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(SequentialJavaModel(emptyList()))),
          appName = "app",
        )
      )
    val javaRunner = KotlinAdkToJava.asJavaRunner(ktRunner)

    javaRunner.close().blockingAwait()

    assertTrue(ktRunner.closed, "close() should delegate to the Kotlin runner's close()")
  }

  @Test
  fun asJavaRunner_missingSession_withoutAutoCreate_errors() {
    // The Java Runner errors on a missing session unless autoCreateSession is set; the wrapper must
    // honour that rather than silently creating one the way the Kotlin engine does.
    val javaRunner =
      KotlinAdkToJava.asJavaRunner(
        KtInMemoryRunner(
          agent =
            KtLlmAgent(
              name = "a",
              model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
            ),
          appName = "app",
        )
      )

    assertFailsWith<IllegalArgumentException> {
      javaRunner
        .runAsync(
          "u",
          "missing",
          GenaiContent.builder().role("user").parts(GenaiPart.fromText("hi")).build(),
          JavaRunConfig.builder().build(),
        )
        .toList()
        .blockingGet()
    }
  }

  @Test
  fun asJavaRunner_missingSession_withAutoCreate_runs() {
    // With autoCreateSession set, a missing session is created and the run proceeds, matching the
    // Java Runner and the Kotlin engine's own default behaviour.
    val javaRunner =
      KotlinAdkToJava.asJavaRunner(
        KtInMemoryRunner(
          agent =
            KtLlmAgent(
              name = "a",
              model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
            ),
          appName = "app",
        )
      )

    val events =
      javaRunner
        .runAsync(
          "u",
          "missing",
          GenaiContent.builder().role("user").parts(GenaiPart.fromText("hi")).build(),
          JavaRunConfig.builder().autoCreateSession(true).build(),
        )
        .toList()
        .blockingGet()

    assertTrue(events.isNotEmpty(), "the run should proceed once the session is auto-created")
  }

  @Test
  fun ktRunner_javaPluginReadsRunConfig_seesTheRunsOwnSettings() = runBlocking {
    // The Java and Kotlin defaults coincide (NONE / 500 / empty), so only a non-default config
    // distinguishes a bridged run config from the Java builder's default.
    val plugin = RunConfigCapturingJavaPlugin()
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
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

    runner
      .runAsync(
        userId = "u",
        sessionId = "s",
        newMessage = KtContent.fromText("user", "go"),
        runConfig =
          KtRunConfig(
            streamingMode = KtStreamingMode.SSE,
            maxLlmCalls = 7,
            customMetadata = mapOf("tenant" to "acme"),
          ),
      )
      .toList()

    val seen = assertNotNull(plugin.seen, "the plugin should have been handed a run config")
    assertEquals(JavaRunConfig.StreamingMode.SSE, seen.streamingMode(), "streamingMode")
    assertEquals(7, seen.maxLlmCalls(), "maxLlmCalls")
    assertEquals<Map<String, Any>>(
      mapOf("tenant" to "acme"),
      seen.customMetadata(),
      "customMetadata",
    )
  }

  @Test
  fun ktRunner_javaToolUsingInheritedActionMutators_writesReachTheKotlinSide() = runBlocking {
    val model =
      SequentialJavaModel(
        listOf(modelFunctionCall("inherited_mutators", emptyMap()), modelText("done"))
      )
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaInheritedActionMutatorsTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events = runner.turn()

    // removeStateByKey must land as the *Kotlin* sentinel, or the engine will not delete the key.
    assertEquals(
      KtState.REMOVED,
      events.firstNotNullOfOrNull { it.actions.stateDelta["doomed"] },
      "removeStateByKey should reach the Kotlin delta as its removal sentinel",
    )
    assertEquals(
      9,
      events.firstNotNullOfOrNull { it.actions.artifactDelta["replaced.txt"] },
      "setArtifactDelta should reach the Kotlin delta",
    )
    assertEquals(
      1L to 2L,
      events.firstNotNullOfOrNull { e ->
        e.actions.compaction?.let { it.startTimestamp to it.endTimestamp }
      },
      "setCompaction should reach the Kotlin actions",
    )
  }

  @Test
  fun ktRunner_javaPluginSetsBranch_throwsRatherThanSilentlyIgnoringIt() = runBlocking {
    // The Kotlin branch is immutable, so the write cannot be honoured. Failing loudly is the
    // point: a silent no-op would leave the plugin believing it had re-scoped the invocation.
    val plugin = BranchSettingJavaPlugin()
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
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

    val events = runner.turn()

    assertTrue(
      plugin.error is UnsupportedOperationException,
      "setting the branch should be rejected, got ${plugin.error}",
    )
    assertTrue(events.isNotEmpty(), "the run itself should still complete")
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

    val events = runner.turn()

    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "done from java model" } == true }
    )
    assertTrue(
      events.any { e -> e.content?.parts?.any { it.functionResponse?.name == "java_echo" } == true }
    )

    // FunctionDeclarationCodec + SchemaCodec: the tool's declaration made the Java -> Kotlin ->
    // Java round trip intact. Asserting the schema, not just that the call happened, is what makes
    // deleting a field mapping fail this test.
    val declared =
      assertNotNull(
        model.requests
          .first()
          .config()
          .getOrNull()
          ?.tools()
          ?.getOrNull()
          ?.flatMap { it.functionDeclarations().getOrNull().orEmpty() }
          ?.firstOrNull { it.name().getOrNull() == "java_echo" },
        "the Java tool's declaration should reach the Java model",
      )
    val params = assertNotNull(declared.parameters().getOrNull(), "parameters")
    assertEquals("OBJECT", params.type().getOrNull()?.toString(), "root schema type")
    assertEquals(listOf("text"), params.required().getOrNull(), "required")
    val props = assertNotNull(params.properties().getOrNull(), "properties")
    assertEquals("text to echo", props["text"]?.description()?.getOrNull(), "nested description")
    assertEquals(
      "STRING",
      props["tags"]?.items()?.getOrNull()?.type()?.getOrNull()?.toString(),
      "array item type",
    )
    assertEquals(listOf("upper", "lower"), props["mode"]?.enum_()?.getOrNull(), "enum values")
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

    val events = runner.turn("hi")

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

    runner.turn("hi")

    // The callback context wires the artifact service, so saving from a model callback succeeds
    // rather than failing with "Artifact service is not initialized".
    assertTrue(
      plugin.saved,
      "saveArtifact in a model callback should complete; error=${plugin.saveError}",
    )
    // Completing is not enough: the write must have landed in the engine's own artifact service,
    // not in some second service the adapter created for the callback context.
    assertEquals(
      "hi",
      runner.artifactService!!.loadArtifact(KtSessionKey("app", "u", "s"), "note.txt")?.text,
      "an artifact saved from a Java callback must be readable from the engine's service",
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

    val events = runner.turn()

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
    assertEquals(0, version, "the first save of a filename is version 0")
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
  fun ktRunner_richGenerateContentConfigAndParts_crossToTheJavaModel() = runBlocking {
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
    // customMetadata rides on the LlmResponse and the engine does not copy it onto the event, so
    // a callback is the only place it is observable.
    val modelResponses = mutableListOf<KtLlmResponse>()
    val agent =
      KtLlmAgent(
        name = "a",
        model = JavaAdkToKt.asKtModel(model),
        tools = listOf(JavaAdkToKt.asKtTool(JavaEchoTool())),
        generateContentConfig = ktConfig,
        afterModelCallbacks =
          listOf(
            KtAfterModelCallback { _, response ->
              modelResponses += response
              response
            }
          ),
      )
    val runner =
      KtInMemoryRunner(
        app = KtApp(appName = "app", rootAgent = agent),
        sessionService = JavaAdkToKt.asKtSessionService(javaSessions),
        artifactService = JavaAdkToKt.asKtArtifactService(javaArtifacts),
      )

    val events = runner.turn()
    assertTrue(events.isNotEmpty(), "expected the rich-config run to produce events")

    // GenerateContentConfigCodec: assert the fields actually crossed. Without this the whole codec
    // could be deleted and the run would still succeed.
    val javaConfig =
      assertNotNull(
        model.requests.first().config().getOrNull(),
        "the converted request should carry a generation config",
      )
    assertEquals(0.5f, javaConfig.temperature().getOrNull(), "temperature")
    assertEquals(0.9f, javaConfig.topP().getOrNull(), "topP")
    assertEquals(40.0f, javaConfig.topK().getOrNull(), "topK")
    assertEquals(256, javaConfig.maxOutputTokens().getOrNull(), "maxOutputTokens")
    assertEquals(listOf("STOP"), javaConfig.stopSequences().getOrNull(), "stopSequences")
    assertEquals("text/plain", javaConfig.responseMimeType().getOrNull(), "responseMimeType")
    assertEquals(mapOf("env" to "test"), javaConfig.labels().getOrNull(), "labels")
    assertEquals(0.1f, javaConfig.presencePenalty().getOrNull(), "presencePenalty")
    assertEquals(0.2f, javaConfig.frequencyPenalty().getOrNull(), "frequencyPenalty")
    assertEquals(true, javaConfig.responseLogprobs().getOrNull(), "responseLogprobs")
    assertTrue(
      javaConfig.systemInstruction().getOrNull() != null,
      "systemInstruction should cross as content",
    )
    // Enums are asserted on toString() (the wire value), not knownEnum(): knownEnum() matches
    // case-insensitively, so it would accept a wrongly-cased value. serviceTier is the one whose
    // wire form differs from its constant name.
    assertEquals(
      "STRING",
      javaConfig.responseSchema().getOrNull()?.type()?.getOrNull()?.toString(),
      "responseSchema",
    )
    assertEquals(
      "MEDIA_RESOLUTION_LOW",
      javaConfig.mediaResolution().getOrNull()?.toString(),
      "mediaResolution",
    )
    assertEquals("standard", javaConfig.serviceTier().getOrNull()?.toString(), "serviceTier")
    assertEquals(
      "router-model",
      javaConfig.routingConfig().getOrNull()?.manualMode()?.getOrNull()?.modelName()?.getOrNull(),
      "routingConfig manual model",
    )
    val functionCalling =
      assertNotNull(
        javaConfig.toolConfig().getOrNull()?.functionCallingConfig()?.getOrNull(),
        "toolConfig functionCallingConfig",
      )
    assertEquals(
      listOf("java_echo"),
      functionCalling.allowedFunctionNames().getOrNull(),
      "allowedFunctionNames",
    )
    assertEquals(
      true,
      functionCalling.streamFunctionCallArguments().getOrNull(),
      "streamFunctionCallArguments",
    )
    val safetySetting =
      assertNotNull(javaConfig.safetySettings().getOrNull()?.singleOrNull(), "safetySettings")
    assertEquals(
      "HARM_CATEGORY_HATE_SPEECH",
      safetySetting.category().getOrNull()?.toString(),
      "safetySetting category",
    )
    assertEquals(
      "BLOCK_ONLY_HIGH",
      safetySetting.threshold().getOrNull()?.toString(),
      "safetySetting threshold",
    )
    val thinking = assertNotNull(javaConfig.thinkingConfig().getOrNull(), "thinkingConfig")
    assertEquals(true, thinking.includeThoughts().getOrNull(), "includeThoughts")
    assertEquals(128, thinking.thinkingBudget().getOrNull(), "thinkingBudget")
    assertEquals("MEDIUM", thinking.thinkingLevel().getOrNull()?.toString(), "thinkingLevel")

    // The five built-in tool kinds: toolToJava / retrievalToJava / vertexRagStoreToJava.
    val javaTools = assertNotNull(javaConfig.tools().getOrNull(), "tools")
    assertEquals(
      listOf("example.com"),
      javaTools
        .firstNotNullOfOrNull { it.googleSearch().getOrNull() }
        ?.excludeDomains()
        ?.getOrNull(),
      "googleSearch excludeDomains",
    )
    assertEquals(
      true,
      javaTools.firstNotNullOfOrNull { it.googleMaps().getOrNull() }?.enableWidget()?.getOrNull(),
      "googleMaps enableWidget",
    )
    assertTrue(
      javaTools.any { it.urlContext().getOrNull() != null },
      "urlContext should cross as a tool",
    )
    val vertexSearch =
      assertNotNull(
        javaTools.firstNotNullOfOrNull {
          it.retrieval().getOrNull()?.vertexAiSearch()?.getOrNull()
        },
        "vertexAiSearch retrieval",
      )
    assertEquals("ds", vertexSearch.datastore().getOrNull(), "vertexAiSearch datastore")
    assertEquals("eng", vertexSearch.engine().getOrNull(), "vertexAiSearch engine")
    assertEquals("f", vertexSearch.filter().getOrNull(), "vertexAiSearch filter")
    assertEquals(5, vertexSearch.maxResults().getOrNull(), "vertexAiSearch maxResults")
    assertEquals(
      listOf("d" to "df"),
      vertexSearch.dataStoreSpecs().getOrNull()?.map {
        it.dataStore().getOrNull() to it.filter().getOrNull()
      },
      "vertexAiSearch dataStoreSpecs",
    )
    val ragStore =
      assertNotNull(
        javaTools.firstNotNullOfOrNull {
          it.retrieval().getOrNull()?.vertexRagStore()?.getOrNull()
        },
        "vertexRagStore retrieval",
      )
    assertEquals(listOf("corpora/1"), ragStore.ragCorpora().getOrNull(), "ragCorpora")
    assertEquals(
      listOf("corpora/2" to listOf("f1")),
      ragStore.ragResources().getOrNull()?.map {
        it.ragCorpus().getOrNull() to it.ragFileIds().getOrNull()
      },
      "ragResources",
    )
    assertEquals(3, ragStore.similarityTopK().getOrNull(), "similarityTopK")
    assertEquals(0.7, ragStore.vectorDistanceThreshold().getOrNull(), "vectorDistanceThreshold")

    // LlmResponseCodec / UsageMetadataCodec: the model attaches both to every turn.
    val usage = assertNotNull(events.firstNotNullOfOrNull { it.usageMetadata }, "usageMetadata")
    assertEquals(8, usage.totalTokenCount, "totalTokenCount")
    assertEquals(3, usage.promptTokenCount, "promptTokenCount")
    assertEquals("ON_DEMAND", usage.trafficType, "trafficType")
    assertEquals(
      listOf(1),
      usage.toolUsePromptTokensDetails?.map { it.tokenCount },
      "toolUsePromptTokensDetails",
    )
    assertTrue(
      events.any { it.finishReason != null },
      "finishReason should cross via LlmResponseCodec",
    )

    // PartCodec: the rich parts the model produced must be readable on the Kotlin side, and the
    // partial args must convert back for the next request.
    val partialArg =
      assertNotNull(
        events.firstNotNullOfOrNull { e ->
          e.content?.parts?.firstNotNullOfOrNull { it.functionCall?.partialArgs?.firstOrNull() }
        },
        "partialArgs should cross to the Kotlin side",
      )
    assertEquals("hi", (partialArg.value as? KtPartialArgValue.StringValue)?.value, "partialArg")
    assertEquals("$.text", partialArg.jsonPath, "partialArg jsonPath")
    val video =
      assertNotNull(
        events.firstNotNullOfOrNull { e ->
          e.content?.parts?.firstNotNullOfOrNull { it.videoMetadata }
        },
        "videoMetadata should cross to the Kotlin side",
      )
    assertEquals(1.seconds, video.startOffset, "videoMetadata startOffset")
    assertEquals(2.seconds, video.endOffset, "videoMetadata endOffset")
    assertEquals(24.0, video.fps, "videoMetadata fps")
    val expectedCustomMetadata: Map<String, Any> = mapOf("tag" to "v1", "score" to 0.5f)
    assertEquals(
      expectedCustomMetadata,
      modelResponses.firstNotNullOfOrNull { it.customMetadata?.takeIf { m -> m.isNotEmpty() } },
      "customMetadata should cross to the Kotlin side",
    )
    // toJava: the partial args survive back into the history the Java model is handed.
    assertEquals(
      "hi",
      model.requests
        .last()
        .contents()
        .firstNotNullOfOrNull { c ->
          c.parts().getOrNull().orEmpty().firstNotNullOfOrNull { part ->
            part.functionCall().getOrNull()?.partialArgs()?.getOrNull()?.firstOrNull()
          }
        }
        ?.stringValue()
        ?.getOrNull(),
      "partialArgs should convert back for the Java request",
    )
  }

  @Test
  fun ktRunner_artifactServiceApis_throughTheAdapter() = runBlocking {
    val javaArtifacts = JavaInMemoryArtifactService()
    val runner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(
            name = "a",
            model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
          ),
        appName = "app",
        artifactService = JavaAdkToKt.asKtArtifactService(javaArtifacts),
      )
    val key = KtSessionKey("app", "u", "s")
    val artifacts = runner.artifactService!!

    // PartCodec inlineData and fileData both round-trip through the adapted service.
    assertEquals(
      0,
      artifacts.saveArtifact(
        key,
        "img.png",
        KtPart(inlineData = KtBlob(mimeType = "image/png", data = byteArrayOf(1, 2, 3))),
      ),
      "the first save of a filename is version 0",
    )
    assertEquals(
      0,
      artifacts.saveArtifact(
        key,
        "doc.txt",
        KtPart(fileData = KtFileData(fileUri = "gs://b/doc.txt", mimeType = "text/plain")),
      ),
      "the first save of a filename is version 0",
    )
    val loadedInline = assertNotNull(artifacts.loadArtifact(key, "img.png"), "inlineData artifact")
    assertEquals("image/png", loadedInline.inlineData?.mimeType, "inlineData mimeType")
    assertContentEquals(byteArrayOf(1, 2, 3), loadedInline.inlineData?.data, "inlineData bytes")
    val loadedFile = assertNotNull(artifacts.loadArtifact(key, "doc.txt"), "fileData artifact")
    assertEquals("gs://b/doc.txt", loadedFile.fileData?.fileUri, "fileData fileUri")
    assertEquals("text/plain", loadedFile.fileData?.mimeType, "fileData mimeType")

    assertTrue(artifacts.listArtifactKeys(key).contains("img.png"), "listArtifactKeys")
    assertTrue(artifacts.listVersions(key, "img.png").isNotEmpty(), "listVersions")
    artifacts.deleteArtifact(key, "img.png")
    assertTrue(
      !artifacts.listArtifactKeys(key).contains("img.png"),
      "a deleted artifact should be gone from the adapted service",
    )
  }

  @Test
  fun ktRunner_sessionServiceApis_throughTheAdapter() = runBlocking {
    val javaSessions = JavaInMemorySessionService()
    val runner =
      KtInMemoryRunner(
        agent =
          KtLlmAgent(
            name = "a",
            model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
          ),
        appName = "app",
        sessionService = JavaAdkToKt.asKtSessionService(javaSessions),
      )
    val key = KtSessionKey("app", "u", "s")

    runner.turn()

    assertTrue(runner.sessionService.listSessions("app", "u").sessions.isNotEmpty(), "listSessions")
    assertNotNull(
      runner.sessionService.getSession(key, KtGetSessionConfig(numRecentEvents = 1)),
      "getSession with a config",
    )
    assertTrue(
      runner.sessionService.listEvents(key).events.isNotEmpty(),
      "listEvents should return the run's events through the adapted service",
    )
    runner.sessionService.deleteSession(key)
    assertTrue(
      runner.sessionService.getSession(key) == null,
      "a deleted session should be gone from the adapted service",
    )
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

    val turn1 = runner.turn("first")
    val turn2 = runner.turn("second")
    val turn3 = runner.turn("third")

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
    val turn1 = runner.turn()
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

    val events = runner.turn()

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
        .retrievalQueries(listOf("adk kotlin retrieval"))
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
    // The engine does not re-surface a response's grounding on the emitted event, so capture it in
    // a bridged Java plugin's afterModel instead: that hands the plugin the Kotlin response
    // converted back to Java, exercising GroundingMetadataCodec in both directions.
    val seen = AtomicReference<GenaiGroundingMetadata>()
    val capturingPlugin =
      object : JavaBasePlugin("grounding-capture") {
        override fun afterModelCallback(
          callbackContext: JavaCallbackContext,
          llmResponse: JavaLlmResponse,
        ): Maybe<JavaLlmResponse> {
          llmResponse.groundingMetadata().ifPresent { seen.set(it) }
          return Maybe.empty()
        }
      }
    val runner =
      KtInMemoryRunner(
        KtApp(
          appName = "app",
          rootAgent = KtLlmAgent(name = "a", model = JavaAdkToKt.asKtModel(model)),
          plugins = listOf(JavaAdkToKt.asKtPlugin(capturingPlugin)),
        )
      )

    val events = runner.turn()

    assertTrue(
      events.any { e -> e.content?.parts?.any { it.text == "grounded" } == true },
      "expected the grounded model reply",
    )

    // GroundingMetadataCodec: assert the payload survived, not merely that nothing threw.
    val g = assertNotNull(seen.get(), "the bridged plugin should see the grounding metadata")
    assertEquals(listOf("adk kotlin"), g.webSearchQueries().getOrNull(), "webSearchQueries")
    assertEquals(
      listOf("adk kotlin retrieval"),
      g.retrievalQueries().getOrNull(),
      "retrievalQueries",
    )
    val chunks = assertNotNull(g.groundingChunks().getOrNull(), "groundingChunks")
    assertEquals("https://x.test", chunks[0].web().getOrNull()?.uri()?.getOrNull(), "web chunk uri")
    assertEquals(
      "ctx",
      chunks[1].retrievedContext().getOrNull()?.text()?.getOrNull(),
      "retrieved-context chunk text",
    )
    val support = assertNotNull(g.groundingSupports().getOrNull()?.firstOrNull(), "support")
    assertEquals("hello", support.segment().getOrNull()?.text()?.getOrNull(), "segment text")
    assertEquals(listOf(0), support.groundingChunkIndices().getOrNull(), "chunk indices")
    assertEquals(
      "<div/>",
      g.searchEntryPoint().getOrNull()?.renderedContent()?.getOrNull(),
      "searchEntryPoint",
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

    val events = runner.turn()

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

    val events = runner.turn()

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

    runner.turn()

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

    val events = runner.turn()

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

    val events = runner.turn()

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

    runner.turn()

    // The plugin's afterTool removal writes the Java sentinel into the live delta; it must be
    // translated so the engine deletes the key rather than persisting a foreign sentinel.
    val session = runner.sessionService.getSession(KtSessionKey("app", "u", "s"))!!
    assertEquals("yes", session.state["kept3"], "a kept key should persist")
    assertTrue(
      !session.state.containsKey("gone3"),
      "a key removed in a plugin afterTool callback should not be in state",
    )
  }

  @Test
  fun ktRunner_javaToolSeesLiveSession_heldAcrossSteps() = runBlocking {
    // A Java tool HOLDS the Session from step 1 and re-reads it in step 2. Being a live view it
    // must reflect events and state added since; ReadonlyContext memoizes over it, so a
    // re-projected snapshot would go stale.
    val held = AtomicReference<JavaSession?>(null)
    val eventsAtHold = AtomicInteger(-1)

    val holdTool =
      object : JavaBaseTool("hold_session", "captures the session") {
        override fun declaration(): Optional<GenaiFunctionDeclaration> =
          Optional.of(GenaiFunctionDeclaration.builder().name("hold_session").build())

        @JvmSuppressWildcards
        override fun runAsync(
          args: Map<String, Any>,
          toolContext: JavaToolContext,
        ): Single<Map<String, Any>> {
          val session = toolContext.invocationContext().session()
          held.set(session)
          eventsAtHold.set(session.events().size)
          toolContext.state()["color"] = "teal"
          return Single.just(mapOf("ok" to true))
        }
      }
    val readHeldTool =
      object : JavaBaseTool("read_held", "reads the held session") {
        override fun declaration(): Optional<GenaiFunctionDeclaration> =
          Optional.of(GenaiFunctionDeclaration.builder().name("read_held").build())

        @JvmSuppressWildcards
        override fun runAsync(
          args: Map<String, Any>,
          toolContext: JavaToolContext,
        ): Single<Map<String, Any>> {
          val session = held.get() ?: return Single.just(mapOf("error" to "nothing held"))
          return Single.just(
            mapOf(
              "grew" to (session.events().size > eventsAtHold.get()),
              "color" to (session.state()["color"] ?: "MISSING"),
            )
          )
        }
      }

    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(
                modelFunctionCall("hold_session", emptyMap()),
                modelFunctionCall("read_held", emptyMap()),
                modelText("done"),
              )
            )
          ),
        tools = JavaAdkToKt.asKtTools(listOf(holdTool, readHeldTool)),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events = runner.turn()

    val response =
      events
        .flatMap { it.content?.parts.orEmpty() }
        .mapNotNull { it.functionResponse }
        .firstOrNull { it.name == "read_held" }
        ?.response
    assertEquals(true, response?.get("grew"), "a held Session's events() must grow in place")
    assertEquals(
      "teal",
      response?.get("color"),
      "a held Session's state() must reflect a value set after it was captured",
    )
  }

  @Test
  fun ktRunner_javaToolDrivesNativeKotlinServices_throughItsInvocationContext() = runBlocking {
    // The primary one-way scenario: keep the Kotlin services, adapt only the Java tool. Because
    // nothing here is an adapted Java service, ServiceAdapters cannot unwrap, so the tool really
    // goes through KtSessionServiceToJava / KtArtifactServiceToJava / KtMemoryServiceToJava.
    val observed = ConcurrentHashMap<String, Any>()
    val serviceTool =
      object : JavaBaseTool("use_services", "drives the engine's services from Java") {
        override fun declaration(): Optional<GenaiFunctionDeclaration> =
          Optional.of(GenaiFunctionDeclaration.builder().name("use_services").build())

        @JvmSuppressWildcards
        override fun runAsync(
          args: Map<String, Any>,
          toolContext: JavaToolContext,
        ): Single<Map<String, Any>> {
          val ic = toolContext.invocationContext()
          val artifacts = ic.artifactService()
          val sessions = ic.sessionService()

          // KtArtifactServiceToJava: save -> load -> list -> versions.
          val version =
            artifacts
              .saveArtifact("app", "u", "s", "note.txt", GenaiPart.fromText("v1"))
              .blockingGet()
          observed["version"] = version
          observed["loaded"] =
            artifacts.loadArtifact("app", "u", "s", "note.txt").blockingGet()?.text()?.orElse("")
              ?: ""
          observed["keys"] = artifacts.listArtifactKeys("app", "u", "s").blockingGet().filenames()
          observed["versions"] = artifacts.listVersions("app", "u", "s", "note.txt").blockingGet()

          // KtSessionServiceToJava: getSession, then appendEvent, whose store-mirroring is the
          // most intricate method in the module.
          val javaSession = sessions.getSession("app", "u", "s", Optional.empty()).blockingGet()!!
          val eventsBefore = javaSession.events().size
          val appended =
            sessions
              .appendEvent(
                javaSession,
                JavaEvent.builder()
                  .id(JavaEvent.generateEventId())
                  .invocationId(ic.invocationId())
                  .author("use_services")
                  .content(GenaiContent.fromParts(GenaiPart.fromText("from the java tool")))
                  .build(),
              )
              .blockingGet()
          observed["appended_author"] = appended.author()
          // appendEvent mirrors the Kotlin store back into this very Java session object.
          observed["events_grew"] = javaSession.events().size > eventsBefore
          observed["sessions"] = sessions.listSessions("app", "u").blockingGet().sessions().size

          // KtMemoryServiceToJava: add then search.
          ic.memoryService().addSessionToMemory(javaSession).blockingAwait()
          observed["memories"] =
            ic.memoryService().searchMemory("app", "u", "java").blockingGet().memories().size
          return Single.just(mapOf("ok" to true))
        }
      }

    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("use_services", emptyMap()), modelText("done"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(serviceTool)),
      )
    // Native Kotlin services throughout: this is what makes the Kt -> Java adapters reachable.
    val runner = KtInMemoryRunner(agent, appName = "app")

    runner.turn()

    assertEquals(0, observed["version"], "first save of a filename is version 0")
    assertEquals("v1", observed["loaded"], "load must return what save stored")
    assertEquals(listOf("note.txt"), observed["keys"], "listArtifactKeys")
    assertEquals(listOf(0), observed["versions"], "listVersions")
    assertEquals(
      true,
      observed["events_grew"],
      "appendEvent must mirror the store into the session",
    )
    assertEquals(1, observed["sessions"], "listSessions")
    assertEquals(1, observed["memories"], "searchMemory should hit the appended text")
  }

  @Test
  fun ktRunner_javaToolReadsPluginManager_throughItsInvocationContext() = runBlocking {
    // A Java tool reads toolContext.invocationContext().pluginManager(): the one-way bridge exposes
    // the engine's plugins with each adapted Java plugin unwrapped to its original, so a Java
    // component (e.g. an AgentTool with includePlugins) sees the real plugins. Reverting the
    // invocation-context bridge leaves the base's empty PluginManager, so getPlugin finds nothing.
    val plugin = RunConfigCapturingJavaPlugin()
    val observed = ConcurrentHashMap<String, Any>()
    val pluginReadingTool =
      object : JavaBaseTool("read_plugins", "reads the plugin manager") {
        override fun declaration(): Optional<GenaiFunctionDeclaration> =
          Optional.of(GenaiFunctionDeclaration.builder().name("read_plugins").build())

        @JvmSuppressWildcards
        override fun runAsync(
          args: Map<String, Any>,
          toolContext: JavaToolContext,
        ): Single<Map<String, Any>> {
          val manager = toolContext.invocationContext().pluginManager() as JavaPluginManager
          observed["found"] = manager.getPlugin("run_config_plugin").getOrNull() ?: "MISSING"
          return Single.just(mapOf("ok" to true))
        }
      }

    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("read_plugins", emptyMap()), modelText("done"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(pluginReadingTool)),
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

    runner.turn()

    // The adapted Java plugin is unwrapped to the original instance, proving the tool read the
    // engine's bridged plugin manager and not the base's empty default.
    assertSame(
      plugin,
      observed["found"],
      "a Java tool should see the engine's plugins (unwrapped) through its invocation context",
    )
  }

  @Test
  fun ktRunner_javaToolReplacingItsActions_deltasStillReachTheKotlinSide() = runBlocking {
    // A Java tool may build a fresh EventActions and setActions(...) it, rather than mutating the
    // live Kotlin-backed maps. Then the two delta maps are different objects and the adapter has to
    // copy them across; without that copy the writes are silently lost.
    val agent =
      KtLlmAgent(
        name = "a",
        model =
          JavaAdkToKt.asKtModel(
            SequentialJavaModel(
              listOf(modelFunctionCall("replace_actions", emptyMap()), modelText("done"))
            )
          ),
        tools = listOf(JavaAdkToKt.asKtTool(JavaActionsReplacingTool())),
      )
    val runner = KtInMemoryRunner(agent, appName = "app")

    val events = runner.turn()

    assertEquals(
      "from_actions",
      runner.sessionService.getSession(KtSessionKey("app", "u", "s"))?.state?.get("replaced_state"),
      "a wholesale setActions() state delta must reach the Kotlin session",
    )
    assertEquals(
      7,
      events.firstNotNullOfOrNull { it.actions.artifactDelta["replaced_artifact.txt"] },
      "a wholesale setActions() artifact delta must reach the Kotlin actions",
    )
  }

  @Test
  fun ktRunner_javaPluginRemovingStateInAgentCallback_removalReachesTheKotlinSide() = runBlocking {
    // The Java removal sentinel differs from the Kotlin one, so every callback that lets a plugin
    // write state has to reconcile it. Without that, the key survives as a Java sentinel object
    // instead of being deleted.
    val runner =
      KtInMemoryRunner(
        KtApp(
          appName = "app",
          rootAgent =
            KtLlmAgent(
              name = "a",
              model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
            ),
          plugins = listOf(JavaAdkToKt.asKtPlugin(AgentStateRemovingPlugin())),
        )
      )

    runner.turn()

    val state = runner.sessionService.getSession(KtSessionKey("app", "u", "s"))?.state
    for (phase in listOf("before", "after")) {
      assertEquals(
        "yes",
        state?.get("agent_kept_$phase"),
        "the plugin's ${phase}Agent write must persist",
      )
      assertTrue(
        state?.containsKey("agent_gone_$phase") != true,
        "a key removed in ${phase}Agent must be deleted, not left as a Java sentinel",
      )
    }
  }

  @Test
  fun asKtSessionService_bridgesCloseSession_toTheJavaService() = runBlocking {
    // closeSession has a default no-op on both SPIs, so an unbridged override fails silently: the
    // Kotlin engine would close a session and the Java service would simply never hear about it.
    val closed = CopyOnWriteArrayList<String>()
    val delegate = JavaInMemorySessionService()
    val javaSessions =
      object : JavaBaseSessionService {
        override fun closeSession(session: JavaSession): Completable {
          closed.add(session.id())
          return Completable.complete()
        }

        // The deprecated ConcurrentMap overload is the abstract one; the Map overload is a default
        // that delegates to it, so an implementor has to override this one.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun createSession(
          appName: String,
          userId: String,
          state: ConcurrentMap<String, Any>?,
          sessionId: String?,
        ): Single<JavaSession> = delegate.createSession(appName, userId, state, sessionId)

        override fun getSession(
          appName: String,
          userId: String,
          sessionId: String,
          config: Optional<JavaGetSessionConfig>,
        ): Maybe<JavaSession> = delegate.getSession(appName, userId, sessionId, config)

        override fun listSessions(appName: String, userId: String) =
          delegate.listSessions(appName, userId)

        override fun deleteSession(appName: String, userId: String, sessionId: String) =
          delegate.deleteSession(appName, userId, sessionId)

        override fun listEvents(appName: String, userId: String, sessionId: String) =
          delegate.listEvents(appName, userId, sessionId)
      }
    val ktService = JavaAdkToKt.asKtSessionService(javaSessions)
    val session = ktService.createSession(KtSessionKey("app", "u", "s"))

    ktService.closeSession(session)

    assertEquals(listOf("s"), closed, "closing through the bridge must reach the Java service")
  }

  @Test
  fun schemaCodec_carriesEveryFacet_roundTrips() {
    // Every facet the Kotlin Schema models must survive Java -> Kotlin -> Java, or structured
    // output (responseSchema) and tool parameter constraints are silently lost.
    val original =
      GenaiSchema.builder()
        .type("OBJECT")
        .description("root")
        .title("Root")
        .nullable(true)
        .format("custom")
        .pattern("[a-z]+")
        .minLength(1L)
        .maxLength(9L)
        .minItems(2L)
        .maxItems(8L)
        .minProperties(1L)
        .maxProperties(5L)
        .minimum(0.5)
        .maximum(9.5)
        .required(listOf("name"))
        .enum_(listOf("a", "b"))
        .default_("a")
        .properties(
          mapOf(
            "name" to GenaiSchema.builder().type("STRING").build(),
            "tags" to
              GenaiSchema.builder()
                .type("ARRAY")
                .items(GenaiSchema.builder().type("STRING").build())
                .build(),
          )
        )
        .anyOf(
          listOf(
            GenaiSchema.builder().type("STRING").build(),
            GenaiSchema.builder().type("INTEGER").build(),
          )
        )
        .build()

    val rt = SchemaCodec.toJava(SchemaCodec.fromJava(original))

    assertEquals("OBJECT", rt.type().getOrNull()?.knownEnum()?.name, "type")
    assertEquals("root", rt.description().getOrNull(), "description")
    assertEquals("Root", rt.title().getOrNull(), "title")
    assertEquals(true, rt.nullable().getOrNull(), "nullable")
    assertEquals("custom", rt.format().getOrNull(), "format")
    assertEquals("[a-z]+", rt.pattern().getOrNull(), "pattern")
    assertEquals(1L, rt.minLength().getOrNull(), "minLength")
    assertEquals(9L, rt.maxLength().getOrNull(), "maxLength")
    assertEquals(2L, rt.minItems().getOrNull(), "minItems")
    assertEquals(8L, rt.maxItems().getOrNull(), "maxItems")
    assertEquals(1L, rt.minProperties().getOrNull(), "minProperties")
    assertEquals(5L, rt.maxProperties().getOrNull(), "maxProperties")
    assertEquals(0.5, rt.minimum().getOrNull(), "minimum")
    assertEquals(9.5, rt.maximum().getOrNull(), "maximum")
    assertEquals(listOf("name"), rt.required().getOrNull(), "required")
    assertEquals(listOf("a", "b"), rt.enum_().getOrNull(), "enum")
    assertEquals("a", rt.default_().getOrNull(), "default")
    val props = assertNotNull(rt.properties().getOrNull(), "properties")
    assertEquals("STRING", props["name"]?.type()?.getOrNull()?.knownEnum()?.name, "nested property")
    assertEquals(
      "STRING",
      props["tags"]?.items()?.getOrNull()?.type()?.getOrNull()?.knownEnum()?.name,
      "nested array items",
    )
    assertEquals(
      listOf("STRING", "INTEGER"),
      rt.anyOf().getOrNull()?.map { it.type().getOrNull()?.knownEnum()?.name },
      "anyOf",
    )
  }

  @Test
  fun functionDeclarationCodec_carriesResponseSchema_roundTrip() {
    // A tool declaration's response schema must survive Java -> Kotlin -> Java, not just its
    // parameters, so a model prompted with the tool sees the declared return shape.
    val declaration =
      GenaiFunctionDeclaration.builder()
        .name("get_weather")
        .description("looks up weather")
        .parameters(GenaiSchema.builder().type("OBJECT").build())
        .response(GenaiSchema.builder().type("STRING").description("the forecast").build())
        .build()

    val rt = FunctionDeclarationCodec.toJava(FunctionDeclarationCodec.fromJava(declaration))

    val response = assertNotNull(rt.response().getOrNull(), "response schema should round-trip")
    assertEquals("STRING", response.type().getOrNull()?.knownEnum()?.name, "response type")
    assertEquals("the forecast", response.description().getOrNull(), "response description")
  }

  @Test
  fun groundingMetadataCodec_carriesMapsChunkText_roundTrip() {
    // A maps grounding chunk's text (the place answer) must survive Java -> Kotlin -> Java; the
    // Kotlin type models it, so dropping it loses the maps answer.
    val metadata =
      GenaiGroundingMetadata.builder()
        .groundingChunks(
          listOf(
            GenaiGroundingChunk.builder()
              .maps(
                GenaiGroundingChunkMaps.builder()
                  .uri("https://maps.test/p")
                  .title("Cafe")
                  .placeId("places/abc")
                  .text("Open until 9pm.")
                  .build()
              )
              .build()
          )
        )
        .build()

    val rt = GroundingMetadataCodec.toJava(GroundingMetadataCodec.fromJava(metadata))

    val maps =
      assertNotNull(
        rt.groundingChunks().getOrNull()?.firstNotNullOfOrNull { it.maps().getOrNull() },
        "maps chunk should round-trip",
      )
    assertEquals("Open until 9pm.", maps.text().getOrNull(), "maps chunk text")
    assertEquals("places/abc", maps.placeId().getOrNull(), "maps chunk placeId")
  }

  @Test
  fun asKtSessionService_listEvents_carriesNextPageToken() = runBlocking {
    // The listEvents bridge must carry nextPageToken, or a paged Java service's continuation token
    // is silently lost crossing to the Kotlin engine.
    val delegate = JavaInMemorySessionService()
    val javaSessions =
      object : JavaBaseSessionService {
        override fun listEvents(appName: String, userId: String, sessionId: String) =
          Single.just(
            JavaListEventsResponse.builder()
              .events(emptyList<JavaEvent>())
              .nextPageToken("next-page")
              .build()
          )

        @Suppress("OVERRIDE_DEPRECATION")
        override fun createSession(
          appName: String,
          userId: String,
          state: ConcurrentMap<String, Any>?,
          sessionId: String?,
        ): Single<JavaSession> = delegate.createSession(appName, userId, state, sessionId)

        override fun getSession(
          appName: String,
          userId: String,
          sessionId: String,
          config: Optional<JavaGetSessionConfig>,
        ): Maybe<JavaSession> = delegate.getSession(appName, userId, sessionId, config)

        override fun listSessions(appName: String, userId: String) =
          delegate.listSessions(appName, userId)

        override fun deleteSession(appName: String, userId: String, sessionId: String) =
          delegate.deleteSession(appName, userId, sessionId)
      }

    val response =
      JavaAdkToKt.asKtSessionService(javaSessions).listEvents(KtSessionKey("app", "u", "s"))

    assertEquals(
      "next-page",
      response.nextPageToken,
      "nextPageToken must cross the Java -> Kotlin listEvents bridge",
    )
  }

  @Test
  fun partCodec_toolCallAndToolResponse_roundTrip() {
    // Server-side tool call/response parts are modelled by the Kotlin Part, so they must survive
    // Java -> Kotlin -> Java rather than being dropped as an unmapped kind.
    val call =
      GenaiPart.builder()
        .toolCall(
          GenaiToolCall.builder()
            .id("call-1")
            .toolType("GOOGLE_SEARCH_WEB")
            .args(mapOf("q" to "adk"))
            .build()
        )
        .build()
    val response =
      GenaiPart.builder()
        .toolResponse(
          GenaiToolResponse.builder()
            .id("call-1")
            .toolType("URL_CONTEXT")
            .response(mapOf("ok" to true))
            .build()
        )
        .build()

    val rtCall =
      assertNotNull(PartCodec.toJavaOrThrow(PartCodec.fromJavaOrThrow(call)).toolCall().getOrNull())
    assertEquals("call-1", rtCall.id().getOrNull(), "toolCall id")
    assertEquals(
      "GOOGLE_SEARCH_WEB",
      rtCall.toolType().getOrNull()?.toString(),
      "toolCall toolType",
    )
    assertEquals(
      mapOf<String, Any>("q" to "adk"),
      assertNotNull(rtCall.args().getOrNull()),
      "toolCall args",
    )

    val rtResponse =
      assertNotNull(
        PartCodec.toJavaOrThrow(PartCodec.fromJavaOrThrow(response)).toolResponse().getOrNull()
      )
    assertEquals("call-1", rtResponse.id().getOrNull(), "toolResponse id")
    assertEquals(
      "URL_CONTEXT",
      rtResponse.toolType().getOrNull()?.toString(),
      "toolResponse toolType",
    )
    assertEquals(
      mapOf<String, Any>("ok" to true),
      assertNotNull(rtResponse.response().getOrNull()),
      "toolResponse response",
    )
  }

  @Test
  fun ktRunner_javaPluginModelCallback_readsRunConfig_throughItsInvocationContext() = runBlocking {
    // A Java plugin's beforeModel callback reads callbackContext.invocationContext().runConfig();
    // the callback-context bridge must carry the run's RunConfig, not a default one.
    val seen = AtomicReference<JavaRunConfig?>()
    val plugin =
      object : JavaBasePlugin("model_run_config_plugin") {
        override fun beforeModelCallback(
          callbackContext: JavaCallbackContext,
          llmRequestBuilder: JavaLlmRequest.Builder,
        ): Maybe<JavaLlmResponse> {
          seen.set(callbackContext.invocationContext().runConfig())
          return Maybe.empty()
        }
      }
    val runner =
      KtInMemoryRunner(
        app =
          KtApp(
            appName = "app",
            rootAgent =
              KtLlmAgent(
                name = "a",
                model = JavaAdkToKt.asKtModel(SequentialJavaModel(listOf(modelText("done")))),
              ),
            plugins = listOf(JavaAdkToKt.asKtPlugin(plugin)),
          )
      )

    runner
      .runAsync(
        userId = "u",
        sessionId = "s",
        newMessage = KtContent.fromText("user", "go"),
        runConfig = KtRunConfig(streamingMode = KtStreamingMode.SSE, maxLlmCalls = 7),
      )
      .toList()

    val config =
      assertNotNull(seen.get(), "the model callback should have been handed a run config")
    assertEquals(
      JavaRunConfig.StreamingMode.SSE,
      config.streamingMode(),
      "streamingMode should map",
    )
    assertEquals(7, config.maxLlmCalls(), "maxLlmCalls should map")
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
