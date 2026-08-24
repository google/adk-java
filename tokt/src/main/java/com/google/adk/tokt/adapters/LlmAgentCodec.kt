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

package com.google.adk.tokt.adapters

import com.google.adk.agents.Instruction as JavaInstruction
import com.google.adk.agents.LlmAgent as JavaLlmAgent
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent as KtLlmAgent
import com.google.adk.kt.models.Model as KtModel
import com.google.adk.kt.tools.BaseTool as KtBaseTool
import com.google.adk.kt.tools.GoogleSearchTool as KtGoogleSearchTool
import com.google.adk.kt.tools.LoadArtifactsTool as KtLoadArtifactsTool
import com.google.adk.kt.tools.LoadMemoryTool as KtLoadMemoryTool
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.tokt.codecs.GenerateContentConfigCodec
import com.google.adk.tokt.codecs.SchemaCodec
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.GoogleSearchTool as JavaGoogleSearchTool
import com.google.adk.tools.LoadArtifactsTool as JavaLoadArtifactsTool
import com.google.adk.tools.LoadMemoryTool as JavaLoadMemoryTool

/**
 * Translates an exact ADK Java [JavaLlmAgent] into a native ADK Kotlin [KtLlmAgent] so the engine
 * runs the agent loop directly (model/tool/agent callbacks, streaming, and transfer all work)
 * rather than the opaque [JavaAgentToKt] wrap. Subclasses are never translated (the caller matches
 * on exact class), so any behavior they override is preserved by running them opaquely.
 */
internal object LlmAgentCodec {

  /**
   * Whether the exact [agent] can run natively. Config the engine cannot reproduce - a
   * `codeExecutor`, `executor`, a provider or non-empty `globalInstruction`, or an unresolvable
   * model - falls back to the opaque wrap.
   */
  fun translatable(agent: JavaLlmAgent): Boolean {
    if (agent.codeExecutor().isPresent) return false
    // No engine equivalent for a custom executor.
    if (agent.executor().isPresent) return false
    // A provider instruction bypasses state injection on the Java side but not on the engine.
    if (agent.instruction() is JavaInstruction.Provider) return false
    when (val global = agent.globalInstruction()) {
      null -> {}
      is JavaInstruction.Static -> if (global.instruction().isNotEmpty()) return false
      else -> return false
    }
    // An unresolvable model throws here; fall back to opaque so the Java flow surfaces it.
    return try {
      agent.resolvedModel().model().isPresent
    } catch (e: RuntimeException) {
      false
    }
  }

  /**
   * Returns the native engine [KtLlmAgent] equivalent of [agent]; call only when [translatable].
   */
  fun toKotlin(agent: JavaLlmAgent): KtLlmAgent {
    val baseLlm =
      agent.resolvedModel().model().orElseThrow {
        IllegalStateException("An LlmAgent routed to the Kotlin engine has no resolvable model")
      }
    // Keep executed built-ins as adapted Java tools when a Java tool callback needs to observe
    // them.
    val hasToolCallbacks =
      agent.beforeToolCallback().isNotEmpty() ||
        agent.afterToolCallback().isNotEmpty() ||
        agent.onToolErrorCallback().isNotEmpty()
    return KtLlmAgent(
      name = agent.name(),
      model = engineModel(baseLlm),
      description = agent.description() ?: "",
      instruction = instruction(agent),
      tools =
        agent.toolsUnion().filterIsInstance<JavaBaseTool>().map {
          engineTool(it, hasToolCallbacks)
        },
      toolsets = agent.toolsets().map { JavaToolsetToKt(it) },
      includeContents = includeContents(agent.includeContents()),
      generateContentConfig =
        agent.generateContentConfig().map { GenerateContentConfigCodec.fromJava(it) }.orElse(null),
      inputSchema = agent.inputSchema().map { SchemaCodec.fromJava(it) }.orElse(null),
      outputSchema = agent.outputSchema().map { SchemaCodec.fromJava(it) }.orElse(null),
      outputKey = agent.outputKey().orElse(null),
      maxSteps = agent.maxSteps().orElse(null),
      subAgents = AgentCodec.subAgents(agent),
      disallowTransferToParent = agent.disallowTransferToParent(),
      disallowTransferToPeers = agent.disallowTransferToPeers(),
      beforeAgentCallbacks = agent.beforeAgentCallback().map { it.toEngine(agent) },
      afterAgentCallbacks = agent.afterAgentCallback().map { it.toEngine(agent) },
      beforeModelCallbacks = agent.beforeModelCallback().map { it.toEngine(agent) },
      afterModelCallbacks = agent.afterModelCallback().map { it.toEngine(agent) },
      beforeToolCallbacks = agent.beforeToolCallback().map { it.toEngine() },
      afterToolCallbacks = agent.afterToolCallback().map { it.toEngine() },
      onModelErrorCallbacks = agent.onModelErrorCallback().map { it.toEngine(agent) },
      onToolErrorCallbacks = agent.onToolErrorCallback().map { it.toEngine() },
    )
  }

  /** Adapts the Java model so the user's code runs; the engine calls it through [JavaModelToKt]. */
  private fun engineModel(baseLlm: JavaBaseLlm): KtModel = JavaModelToKt(baseLlm)

  /**
   * Reuses the engine's native tool when the Java tool is *exactly* a standard built-in carrying no
   * user code, avoiding a Java round-trip. An executed built-in is still adapted ([JavaToolToKt])
   * when the agent has tool callbacks, so those callbacks fire for it; any other tool - including a
   * user subclass of a built-in, whose overrides config translation cannot capture - is adapted so
   * the user's code runs.
   */
  private fun engineTool(tool: JavaBaseTool, hasToolCallbacks: Boolean): KtBaseTool =
    when (tool.javaClass) {
      // Model-side only (never executed as a function), so tool callbacks never apply.
      JavaGoogleSearchTool::class.java -> KtGoogleSearchTool()
      JavaLoadArtifactsTool::class.java ->
        if (hasToolCallbacks) JavaToolToKt(tool) else KtLoadArtifactsTool()
      JavaLoadMemoryTool::class.java ->
        if (hasToolCallbacks) JavaToolToKt(tool) else KtLoadMemoryTool()
      else -> JavaToolToKt(tool)
    }

  // A provider instruction is gated to the opaque wrap by translatable(), so only a static one
  // reaches here; an empty static instruction is dropped, matching the Java flow.
  private fun instruction(agent: JavaLlmAgent): Instruction? =
    (agent.instruction() as? JavaInstruction.Static)
      ?.instruction()
      ?.takeIf { it.isNotEmpty() }
      ?.let { Instruction(it) }

  private fun includeContents(value: JavaLlmAgent.IncludeContents): KtLlmAgent.IncludeContents =
    when (value) {
      JavaLlmAgent.IncludeContents.NONE -> KtLlmAgent.IncludeContents.NONE
      else -> KtLlmAgent.IncludeContents.DEFAULT
    }
}
