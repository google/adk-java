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
 * they can run on the ADK Kotlin engine. Assemble the adapted pieces into a Kotlin `LlmAgent`,
 * `App`, and `Runner`; this does not convert a whole Java agent.
 *
 * An adapted component behaves as it does on ADK Java. It sees the session as it currently stands,
 * including events and state written earlier in the same turn, and its state, artifact and
 * control-flow writes reach the engine. Blocking work is fine because calls run on the optional
 * `dispatcher` (default `Dispatchers.IO`) that each conversion accepts. That dispatcher must be
 * able to run nested bridged calls in parallel: a bridged tool or plugin that blocks on another
 * bridged call, such as a service call, holds its thread until that call returns, so a
 * single-threaded or tightly bounded dispatcher can deadlock. `Dispatchers.IO` deadlocks only if
 * all of its threads (at least 64 by default) block at once.
 *
 * A bridged plugin's error callbacks fire: `onRunErrorCallback` is notification-only -- the engine
 * re-raises the run's error to the caller afterwards regardless, so it cannot recover the run (it
 * is for logging, telemetry, or cleanup) -- while the `onModelErrorCallback` and
 * `onToolErrorCallback` recovery hooks fire and can recover.
 *
 * When a bridged tool call or plugin callback writes one of the following signals to its context,
 * the interop throws rather than silently dropping it:
 * - Setting `branch` on the invocation context throws. The branch is the engine's to set.
 * - If Java code writes `requestedAuthConfigs` or `deletedArtifactIds` to the context's
 *   `EventActions`, the adapter throws once the Java call returns - the engine's event actions have
 *   no equivalent.
 *
 * Writes to the context's `skipSummarization`, `requestedToolConfirmations`, `agentState`, and
 * `rewindBeforeInvocationId` do cross to the engine.
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
   * Adapts a whole collection of ADK Java tools (such as an `LlmAgent`'s `tools`), each on
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
   * Adapts a whole collection of ADK Java plugins (such as an `App`'s `plugins()`), each on
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
   * Adapts an ADK Java session service for the Kotlin engine, running its calls on `dispatcher`. A
   * Java view of a Kotlin service is unwrapped to that Kotlin service, with no `dispatcher` hop.
   * Resumption works across the adapter (`EventActions.agentState` crosses);
   * `rewindBeforeInvocationId` crosses too, but a rewind drops the rewound turns only if the Java
   * service stores it, which ADK Java's `VertexAiSessionService` does not.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtSessionService(
    service: JavaSessionService,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtSessionService = javaSessionServiceAsKt(service, dispatcher)

  /**
   * Adapts an ADK Java artifact service for the Kotlin engine, running its calls on `dispatcher`. A
   * Java view of a Kotlin service is unwrapped to that Kotlin service, with no `dispatcher` hop.
   * The adapter throws on an empty artifact part or one it cannot convert.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtArtifactService(
    service: JavaArtifactService,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtArtifactService = javaArtifactServiceAsKt(service, dispatcher)

  /**
   * Adapts an ADK Java memory service for the Kotlin engine, running its calls on `dispatcher`. A
   * Java view of a Kotlin service is unwrapped to that Kotlin service, with no `dispatcher` hop.
   */
  @JvmStatic
  @JvmOverloads
  fun asKtMemoryService(
    service: JavaMemoryService,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): KtMemoryService = javaMemoryServiceAsKt(service, dispatcher)
}
