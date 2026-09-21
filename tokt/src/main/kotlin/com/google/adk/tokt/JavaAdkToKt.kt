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

import com.google.adk.artifacts.BaseArtifactService as JavaArtifactService
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
import com.google.adk.tokt.services.javaArtifactServiceAsKt
import com.google.adk.tokt.services.javaMemoryServiceAsKt
import com.google.adk.tokt.services.javaSessionServiceAsKt
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.BaseToolset as JavaBaseToolset
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Forward interop entry point: adapts ADK Java tools, toolsets, plugins, services, and models so
 * they can run on the ADK Kotlin engine. Wrap the adapted pieces in a Kotlin `LlmAgent`; this does
 * not convert a whole Java agent.
 *
 * An adapted component behaves as it does on ADK Java. It sees the session as it currently stands,
 * including events and state written earlier in the same turn, and its state, artifact and
 * control-flow writes reach the engine. Blocking work is fine: calls are dispatched off the thread
 * driving the agent, onto the optional `dispatcher` (default `Dispatchers.IO`) each conversion
 * accepts. That dispatcher must be able to run nested bridged calls concurrently -- a bridged tool
 * or plugin that itself makes a blocking bridged call (e.g. one that blocks on a bridged service)
 * holds its thread until that call returns, so a single-threaded or tightly bounded dispatcher can
 * deadlock; the default `Dispatchers.IO` grows its pool and avoids this.
 *
 * A bridged plugin's error callbacks fire: `onRunErrorCallback` is notification-only -- the engine
 * re-raises the run's error to the caller afterwards regardless, so it cannot recover the run (it
 * is for logging, telemetry, or cleanup) -- while the `onModelErrorCallback` and
 * `onToolErrorCallback` recovery hooks fire and can recover.
 *
 * The interop surfaces a signal the engine cannot honor rather than silently dropping it:
 * - Setting `branch` on a bridged context throws. The branch is the engine's to set.
 * - A bridged tool's or plugin's `requestedAuthConfigs` or `deletedArtifactIds` write throws - the
 *   engine's event actions have no equivalent. Its `skipSummarization`,
 *   `requestedToolConfirmations` and `agentState` writes do cross, from a tool and a plugin alike.
 * - Behind an adapted Java session service ([asKtSessionService]), a resumable workflow's engine
 *   state (`EventActions.agentState`) crosses and is restored, so it resumes rather than restarts.
 *   A rewind request (`rewindBeforeInvocationId`) still does not cross - ADK Java's `EventActions`
 *   has no such field.
 */
object JavaAdkToKt {

  /** Adapts an ADK Java tool, hopping to `dispatcher` for its (possibly blocking) calls. */
  @JvmStatic
  @JvmOverloads
  fun asKtTool(
    javaTool: JavaBaseTool,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtBaseTool = JavaToolToKt(javaTool, dispatcher)

  /**
   * Adapts a whole collection of ADK Java tools (e.g. an `LlmAgent`'s `tools`), each on
   * `dispatcher`. Kept alongside [asKtTool] for Java callers, who would otherwise write
   * `stream().map(...).toList()`.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtTools(
    javaTools: List<JavaBaseTool>,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): List<KtBaseTool> = javaTools.map { asKtTool(it, dispatcher) }

  /** Adapts an ADK Java toolset, hopping to `dispatcher` for its (possibly blocking) calls. */
  @JvmStatic
  @JvmOverloads
  fun asKtToolset(
    javaToolset: JavaBaseToolset,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtToolset = JavaToolsetToKt(javaToolset, dispatcher)

  /** Adapts a whole collection of ADK Java toolsets, each on `dispatcher`. */
  @JvmStatic
  @JvmOverloads
  fun asKtToolsets(
    javaToolsets: List<JavaBaseToolset>,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): List<KtToolset> = javaToolsets.map { asKtToolset(it, dispatcher) }

  /** Adapts an ADK Java plugin, hopping to `dispatcher` for its (possibly blocking) callbacks. */
  @JvmStatic
  @JvmOverloads
  fun asKtPlugin(
    javaPlugin: JavaPlugin,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtPlugin = JavaPluginToKt(javaPlugin, dispatcher)

  /**
   * Adapts a whole collection of ADK Java plugins (e.g. a `Runner`'s `plugins`), each on
   * `dispatcher`.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtPlugins(
    javaPlugins: List<JavaPlugin>,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): List<KtPlugin> = javaPlugins.map { asKtPlugin(it, dispatcher) }

  /**
   * Adapts an ADK Java model so the Kotlin engine can call it, running its generation on
   * `dispatcher`.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtModel(
    javaLlm: JavaBaseLlm,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtModel = JavaModelToKt(javaLlm, dispatcher)

  /**
   * Adapts an ADK Java session service for the Kotlin engine, unwrapping a round-tripped Kotlin one
   * rather than stacking a second adapter. Its calls run on `dispatcher`. A
   * `rewindBeforeInvocationId` does not survive, since ADK Java has no such field.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtSessionService(
    service: JavaSessionService,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtSessionService = javaSessionServiceAsKt(service, dispatcher)

  /**
   * Adapts an ADK Java artifact service for the Kotlin engine, unwrapping a round-tripped Kotlin
   * one rather than stacking adapters. Its calls run on `dispatcher`. An empty or unmapped artifact
   * part is rejected outright.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtArtifactService(
    service: JavaArtifactService,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtArtifactService = javaArtifactServiceAsKt(service, dispatcher)

  /**
   * Adapts an ADK Java memory service for the Kotlin engine, unwrapping a round-tripped Kotlin one
   * rather than stacking adapters. Its calls run on `dispatcher`.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtMemoryService(
    service: JavaMemoryService,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtMemoryService = javaMemoryServiceAsKt(service, dispatcher)
}
