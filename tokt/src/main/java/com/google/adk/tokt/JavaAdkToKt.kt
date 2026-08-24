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
import com.google.adk.artifacts.BaseArtifactService as JavaArtifactService
import com.google.adk.kt.agents.BaseAgent as KtBaseAgent
import com.google.adk.kt.artifacts.ArtifactService as KtArtifactService
import com.google.adk.kt.memory.MemoryService as KtMemoryService
import com.google.adk.kt.models.Model as KtModel
import com.google.adk.kt.plugins.Plugin as KtPlugin
import com.google.adk.kt.sessions.SessionService as KtSessionService
import com.google.adk.kt.tools.BaseTool as KtBaseTool
import com.google.adk.kt.tools.Toolset as KtToolset
import com.google.adk.memory.BaseMemoryService as JavaMemoryService
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.plugins.Plugin as JavaPlugin
import com.google.adk.sessions.BaseSessionService as JavaSessionService
import com.google.adk.tokt.adapters.JavaModelToKt
import com.google.adk.tokt.adapters.JavaPluginToKt
import com.google.adk.tokt.adapters.JavaToolToKt
import com.google.adk.tokt.adapters.JavaToolsetToKt
import com.google.adk.tokt.adapters.javaAgentAsKt
import com.google.adk.tokt.services.javaArtifactServiceAsKt
import com.google.adk.tokt.services.javaMemoryServiceAsKt
import com.google.adk.tokt.services.javaSessionServiceAsKt
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.BaseToolset as JavaBaseToolset

/**
 * Forward interop entry point: adapts ADK Java agents, tools, toolsets, plugins, services, and
 * models so they can run on the ADK Kotlin engine. [asKtAgent] adapts a whole Java agent,
 * translating ADK's own agent types to native engine agents and running any other agent opaquely;
 * alternatively wrap the individual Java pieces in a Kotlin `LlmAgent`.
 *
 * An adapted component behaves as it does on ADK Java. It sees the session as it currently stands,
 * including events and state written earlier in the same turn, and its state, artifact and
 * control-flow writes reach the engine. Blocking work is fine: calls are dispatched off the thread
 * driving the agent.
 *
 * Two things to know before relying on them:
 * - Setting `branch` on a bridged context throws. The branch is the engine's to set.
 * - Behind an adapted Java session service ([asKtSessionService]), a resumable workflow restarts
 *   instead of resuming, because ADK Java has nowhere to store the engine's resumption state. Keep
 *   the Kotlin session service if you rely on resumability.
 */
object JavaAdkToKt {

  /**
   * Adapts a whole ADK Java agent so it runs on the Kotlin engine. An exact `LlmAgent` and the
   * exact `Sequential` / `Parallel` / `Loop` workflow agents are translated to native engine
   * agents, so the engine drives their model, tool, and agent callbacks directly. Any subclass of
   * those, any other agent, or an `LlmAgent` using a feature the engine cannot reproduce runs
   * opaquely as a leaf via `JavaAgentToKt`, preserving its own behavior.
   */
  @JvmStatic fun asKtAgent(javaAgent: JavaBaseAgent): KtBaseAgent = javaAgentAsKt(javaAgent)

  /**
   * Adapts a whole collection of ADK Java agents (e.g. to hand a Kotlin agent its `subAgents`).
   * Kept alongside [asKtAgent] for Java callers, as with [asKtTools].
   */
  @JvmStatic
  fun asKtAgents(javaAgents: List<JavaBaseAgent>): List<KtBaseAgent> = javaAgents.map {
    asKtAgent(it)
  }

  /** Adapts an ADK Java tool. */
  @JvmStatic fun asKtTool(javaTool: JavaBaseTool): KtBaseTool = JavaToolToKt(javaTool)

  /**
   * Adapts a whole collection of ADK Java tools (e.g. an `LlmAgent`'s `tools`). Kept alongside
   * [asKtTool] for Java callers, who would otherwise write `stream().map(...).toList()`.
   */
  @JvmStatic
  fun asKtTools(javaTools: List<JavaBaseTool>): List<KtBaseTool> = javaTools.map { asKtTool(it) }

  /** Adapts an ADK Java toolset. */
  @JvmStatic fun asKtToolset(javaToolset: JavaBaseToolset): KtToolset = JavaToolsetToKt(javaToolset)

  /** Adapts a whole collection of ADK Java toolsets. */
  @JvmStatic
  fun asKtToolsets(javaToolsets: List<JavaBaseToolset>): List<KtToolset> = javaToolsets.map {
    asKtToolset(it)
  }

  /** Adapts an ADK Java plugin. */
  @JvmStatic fun asKtPlugin(javaPlugin: JavaPlugin): KtPlugin = JavaPluginToKt(javaPlugin)

  /** Adapts a whole collection of ADK Java plugins (e.g. a `Runner`'s `plugins`). */
  @JvmStatic
  fun asKtPlugins(javaPlugins: List<JavaPlugin>): List<KtPlugin> = javaPlugins.map {
    asKtPlugin(it)
  }

  /** Adapts an ADK Java model so the Kotlin engine can call it. */
  @JvmStatic fun asKtModel(javaLlm: JavaBaseLlm): KtModel = JavaModelToKt(javaLlm)

  /**
   * Adapts an ADK Java session service for the Kotlin engine. Passing a service that is itself an
   * adapted Kotlin one returns the original rather than stacking a second adapter. Note the
   * `agentState` / `rewindBeforeInvocationId` loss described in [JavaAdkToKt].
   */
  @JvmStatic
  fun asKtSessionService(service: JavaSessionService): KtSessionService =
    javaSessionServiceAsKt(service)

  /**
   * Adapts an ADK Java artifact service for the Kotlin engine, unwrapping a round-tripped Kotlin
   * one rather than stacking adapters. An empty or unmapped artifact part is rejected outright.
   */
  @JvmStatic
  fun asKtArtifactService(service: JavaArtifactService): KtArtifactService =
    javaArtifactServiceAsKt(service)

  /**
   * Adapts an ADK Java memory service for the Kotlin engine, unwrapping a round-tripped Kotlin one
   * rather than stacking adapters.
   */
  @JvmStatic
  fun asKtMemoryService(service: JavaMemoryService): KtMemoryService =
    javaMemoryServiceAsKt(service)
}
