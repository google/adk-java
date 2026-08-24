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
import com.google.adk.agents.Callbacks.AfterToolCallback as JavaAfterToolCallback
import com.google.adk.agents.Callbacks.BeforeAgentCallback as JavaBeforeAgentCallback
import com.google.adk.agents.Callbacks.BeforeModelCallback as JavaBeforeModelCallback
import com.google.adk.agents.Instruction as JavaInstruction
import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.agents.LlmAgent as JavaLlmAgent
import com.google.adk.agents.LoopAgent as JavaLoopAgent
import com.google.adk.agents.ParallelAgent as JavaParallelAgent
import com.google.adk.agents.SequentialAgent as JavaSequentialAgent
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.agents.Instruction as KtInstruction
import com.google.adk.kt.agents.LlmAgent as KtLlmAgent
import com.google.adk.kt.agents.LoopAgent as KtLoopAgent
import com.google.adk.kt.agents.ParallelAgent as KtParallelAgent
import com.google.adk.kt.agents.SequentialAgent as KtSequentialAgent
import com.google.adk.kt.runners.InMemoryRunner as KtInMemoryRunner
import com.google.adk.kt.tools.GoogleSearchTool as KtGoogleSearchTool
import com.google.adk.kt.tools.LoadMemoryTool as KtLoadMemoryTool
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.models.BaseLlmConnection as JavaBaseLlmConnection
import com.google.adk.models.LlmRequest as JavaLlmRequest
import com.google.adk.models.LlmResponse as JavaLlmResponse
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.GoogleSearchTool as JavaGoogleSearchTool
import com.google.adk.tools.LoadMemoryTool as JavaLoadMemoryTool
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.genai.types.Content as GenaiContent
import com.google.genai.types.Part as GenaiPart
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function as JavaFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [JavaAdkToKt.asKtAgent]'s native translation of ADK Java agents: ADK's own agent
 * types become native engine agents, and everything else falls back to the opaque wrap.
 */
class LlmAgentTranslationTest {

  /** A Java model that never generates; these tests only inspect the translated structure. */
  private class FakeLlm : JavaBaseLlm("fake-model") {
    override fun generateContent(
      llmRequest: JavaLlmRequest,
      stream: Boolean,
    ): Flowable<JavaLlmResponse> = Flowable.empty()

    override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
      throw UnsupportedOperationException()
  }

  /** A trivial Java tool. */
  private class FakeTool : JavaBaseTool("fake_tool", "fake") {
    @JvmSuppressWildcards
    override fun runAsync(
      args: Map<String, Any>,
      toolContext: JavaToolContext,
    ): Single<Map<String, Any>> = Single.just(emptyMap())
  }

  /** An LlmAgent subclass that only presets config - a subclass, so it must run opaquely. */
  private class ConfigOnlyAgent(builder: JavaLlmAgent.Builder) : JavaLlmAgent(builder)

  /** An LlmAgent subclass that overrides the run loop - must fall back to the opaque wrap. */
  private class RunLoopOverridingAgent(builder: JavaLlmAgent.Builder) : JavaLlmAgent(builder) {
    override fun runAsyncImpl(invocationContext: JavaInvocationContext): Flowable<JavaEvent> =
      Flowable.empty()
  }

  /** A non-ADK Java agent - must fall back to the opaque wrap. */
  private class CustomAgent : JavaBaseAgent("custom_agent", "custom", null, null, null) {
    override fun runAsyncImpl(invocationContext: JavaInvocationContext): Flowable<JavaEvent> =
      Flowable.empty()

    override fun runLiveImpl(invocationContext: JavaInvocationContext): Flowable<JavaEvent> =
      Flowable.empty()
  }

  private fun llmAgent(name: String) = JavaLlmAgent.builder().name(name).model(FakeLlm())

  private fun modelText(text: String): GenaiContent =
    GenaiContent.builder().role("model").parts(listOf(GenaiPart.fromText(text))).build()

  @Test
  fun asKtAgent_plainLlmAgent_translatesToNativeLlmAgent() {
    val javaAgent =
      llmAgent("planner")
        .description("plans things")
        .instruction("be concise")
        .tools(listOf(FakeTool()))
        .outputKey("plan")
        .includeContents(JavaLlmAgent.IncludeContents.NONE)
        .disallowTransferToPeers(true)
        .build()

    val kt = assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(javaAgent))
    assertEquals("planner", kt.name)
    assertEquals("plans things", kt.description)
    assertEquals(KtInstruction("be concise"), kt.instruction)
    assertEquals("fake-model", kt.model.name)
    assertEquals(listOf("fake_tool"), kt.tools.map { it.name })
    assertEquals("plan", kt.outputKey)
    assertEquals(KtLlmAgent.IncludeContents.NONE, kt.includeContents)
    assertTrue(kt.disallowTransferToPeers)
  }

  @Test
  fun asKtAgent_exactLlmAgentWithoutInstruction_hasNullInstruction() {
    // An exact LlmAgent with no (or an empty) instruction maps to null, matching the Java flow.
    val kt = assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(llmAgent("bare").build()))
    assertNull(kt.instruction)
  }

  @Test
  fun asKtAgent_configOnlyLlmAgentSubclass_fallsBackToOpaqueWrap() {
    // Only the exact LlmAgent class is translated; even a config-only subclass runs opaquely, since
    // its run methods are not final and could override behavior the translation cannot see.
    val result = JavaAdkToKt.asKtAgent(ConfigOnlyAgent(llmAgent("preset")))
    assertFalse(result is KtLlmAgent, "a subclass must run its own Java flow")
    assertEquals("preset", result.name)
  }

  @Test
  fun asKtAgent_llmAgentOverridingRunLoop_fallsBackToOpaqueWrap() {
    val result = JavaAdkToKt.asKtAgent(RunLoopOverridingAgent(llmAgent("override")))
    assertFalse(result is KtLlmAgent, "a subclass must run its own Java flow")
    assertEquals("override", result.name)
  }

  @Test
  fun asKtAgent_llmAgentWithGlobalInstruction_fallsBackToOpaqueWrap() {
    val javaAgent = llmAgent("global").globalInstruction("be global").build()
    assertFalse(JavaAdkToKt.asKtAgent(javaAgent) is KtLlmAgent)
  }

  @Test
  fun asKtAgent_builtInGoogleSearchTool_reusesNativeEngineTool() {
    val javaAgent = llmAgent("searcher").tools(listOf(JavaGoogleSearchTool())).build()
    val kt = assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(javaAgent))
    assertIs<KtGoogleSearchTool>(kt.tools.single())
  }

  @Test
  fun asKtAgent_executedBuiltInTool_isNativeAloneButAdaptedWhenToolCallbacksArePresent() {
    // An executed built-in is reused natively on its own, but adapted (so the user's Java tool
    // callbacks fire for it) when the agent has tool callbacks.
    val noCallbacks = llmAgent("m1").tools(listOf(JavaLoadMemoryTool())).build()
    assertIs<KtLoadMemoryTool>(
      assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(noCallbacks)).tools.single()
    )

    val withCallback =
      llmAgent("m2")
        .tools(listOf(JavaLoadMemoryTool()))
        .afterToolCallback(JavaAfterToolCallback { _, _, _, _, _ -> Maybe.empty() })
        .build()
    val tool = assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(withCallback)).tools.single()
    assertFalse(tool is KtLoadMemoryTool, "with tool callbacks the built-in must be adapted")
  }

  @Test
  fun asKtAgent_providerInstruction_fallsBackToOpaqueWrap() {
    // The engine injects `{state}` variables into all instruction text and can fail on a missing
    // one, whereas the Java flow leaves a provider's output verbatim; keep such agents opaque.
    val javaAgent =
      llmAgent("dynamic")
        .instruction(JavaInstruction.Provider(JavaFunction { Single.just("resolved per turn") }))
        .build()
    assertFalse(JavaAdkToKt.asKtAgent(javaAgent) is KtLlmAgent)
  }

  @Test
  fun asKtAgent_llmAgentWithExecutor_fallsBackToOpaqueWrap() {
    val javaAgent = llmAgent("threaded").executor(Executor { it.run() }).build()
    assertFalse(JavaAdkToKt.asKtAgent(javaAgent) is KtLlmAgent)
  }

  @Test
  fun asKtAgent_llmAgentWithCallbacks_wiresThemOntoTheEngineAgent() {
    val javaAgent =
      llmAgent("with_callbacks")
        .beforeAgentCallback(JavaBeforeAgentCallback { Maybe.empty() })
        .beforeModelCallback(JavaBeforeModelCallback { _, _ -> Maybe.empty() })
        .build()
    val kt = assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(javaAgent))
    assertEquals(1, kt.beforeAgentCallbacks.size)
    assertEquals(1, kt.beforeModelCallbacks.size)
  }

  @Test
  fun asKtAgent_sequentialAgentOfLlmAgents_translatesNativelyWithNativeSubAgents() {
    val seq =
      JavaSequentialAgent.builder()
        .name("pipeline")
        .subAgents(llmAgent("first").build(), llmAgent("second").build())
        .build()
    val kt = assertIs<KtSequentialAgent>(JavaAdkToKt.asKtAgent(seq))
    assertEquals(listOf("first", "second"), kt.subAgents.map { it.name })
    assertTrue(kt.subAgents.all { it is KtLlmAgent }, "sub-agents should be native LlmAgents")
  }

  @Test
  fun asKtAgent_workflowSubAgentThatCannotTranslate_isWrappedOpaquely() {
    val seq =
      JavaSequentialAgent.builder()
        .name("mixed")
        .subAgents(llmAgent("native").build(), CustomAgent())
        .build()
    val kt = assertIs<KtSequentialAgent>(JavaAdkToKt.asKtAgent(seq))
    assertIs<KtLlmAgent>(kt.subAgents[0])
    assertFalse(kt.subAgents[1] is KtLlmAgent, "the non-ADK sub-agent runs opaquely")
    assertEquals("custom_agent", kt.subAgents[1].name)
  }

  @Test
  fun asKtAgent_loopAgent_translatesMaxIterations() {
    val loop =
      JavaLoopAgent.builder()
        .name("loop")
        .maxIterations(3)
        .subAgents(llmAgent("body").build())
        .build()
    val kt = assertIs<KtLoopAgent>(JavaAdkToKt.asKtAgent(loop))
    assertEquals(3, kt.maxIterations)
  }

  @Test
  fun asKtAgent_parallelAgentOfLlmAgents_translatesNatively() {
    val parallel =
      JavaParallelAgent.builder()
        .name("fanout")
        .subAgents(llmAgent("a").build(), llmAgent("b").build())
        .build()
    val kt = assertIs<KtParallelAgent>(JavaAdkToKt.asKtAgent(parallel))
    assertEquals(listOf("a", "b"), kt.subAgents.map { it.name })
  }

  @Test
  fun asKtAgent_parallelAgentWithOpaqueSubAgent_fallsBackToOpaqueWrap() {
    // A native ParallelAgent's merge buffers, breaking an opaque multi-step sub-agent's persist
    // handshake, so a Parallel whose subtree is not fully translatable stays opaque as a whole.
    val parallel =
      JavaParallelAgent.builder()
        .name("mixed_fanout")
        .subAgents(llmAgent("native").build(), CustomAgent())
        .build()
    val result = JavaAdkToKt.asKtAgent(parallel)
    assertFalse(result is KtParallelAgent)
    assertEquals("mixed_fanout", result.name)
  }

  @Test
  fun asKtAgent_translatedAgent_runsJavaBeforeModelCallbackThatShortCircuitsTheModel() =
    runBlocking {
      // A Java beforeModelCallback that returns a response short-circuits the model: its text is
      // emitted and the model itself never runs. Exercises the callback bridge end to end.
      val modelCalls = AtomicInteger()
      val model =
        object : JavaBaseLlm("fake-model") {
          override fun generateContent(
            llmRequest: JavaLlmRequest,
            stream: Boolean,
          ): Flowable<JavaLlmResponse> {
            modelCalls.incrementAndGet()
            return Flowable.just(JavaLlmResponse.builder().content(modelText("from model")).build())
          }

          override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
            throw UnsupportedOperationException()
        }
      val javaAgent =
        JavaLlmAgent.builder()
          .name("short_circuit")
          .model(model)
          .beforeModelCallback(
            JavaBeforeModelCallback { _, _ ->
              Maybe.just(JavaLlmResponse.builder().content(modelText("from callback")).build())
            }
          )
          .build()
      val kt = assertIs<KtLlmAgent>(JavaAdkToKt.asKtAgent(javaAgent))
      val runner = KtInMemoryRunner(kt, appName = "app")

      val events =
        runner
          .runAsync(userId = "u", sessionId = "s", newMessage = KtContent.fromText("user", "go"))
          .toList()

      assertTrue(
        events.any { e -> e.content?.parts?.any { it.text == "from callback" } == true },
        "the Java beforeModelCallback response should short-circuit the model",
      )
      assertEquals(
        0,
        modelCalls.get(),
        "the model must not run when the callback returns a response",
      )
    }

  @Test
  fun asKtAgent_customBaseAgent_fallsBackToOpaqueWrap() {
    val result = JavaAdkToKt.asKtAgent(CustomAgent())
    assertFalse(result is KtLlmAgent)
    assertFalse(result is KtSequentialAgent)
    assertEquals("custom_agent", result.name)
  }
}
