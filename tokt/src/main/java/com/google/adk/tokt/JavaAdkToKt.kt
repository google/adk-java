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

/**
 * Forward interop entry point: adapt ADK Java tools, toolsets, plugins, services, and models so
 * they can run on the ADK Kotlin engine. Whole-agent conversion is intentionally omitted (running a
 * Java agent's own multi-step flow on the Kotlin runner is not yet supported); wrap the Java SPI
 * pieces into a Kotlin `LlmAgent` instead.
 *
 * **Session is re-projected, not a live handle.** An adapted Java tool / toolset / plugin sees the
 * session through `context.session()`, which is re-projected on each call, so a *fresh* read is
 * always current. The returned `Session` is a snapshot though: unlike native ADK Java its
 * `events()` list does not grow in place and its state is a copy. Re-read `context.session()` each
 * time; do not cache a `Session` (or its `events()`) across tool calls or turns. State *writes* via
 * `toolContext.state()` / `callbackContext.state()` still propagate to the Kotlin.
 */
object JavaAdkToKt {

  /**
   * Adapts an ADK Java tool. Its `ToolContext.session()` is a per-call snapshot; see [JavaAdkToKt].
   */
  @JvmStatic fun asKtTool(javaTool: JavaBaseTool): KtBaseTool = JavaToolToKt(javaTool)

  /** Adapts a whole collection of ADK Java tools (e.g. an `LlmAgent`'s `tools`). */
  @JvmStatic
  fun asKtTools(javaTools: List<JavaBaseTool>): List<KtBaseTool> = javaTools.map { asKtTool(it) }

  /**
   * Adapts an ADK Java toolset. Its `ReadonlyContext` session is a per-call snapshot; see
   * [JavaAdkToKt].
   */
  @JvmStatic fun asKtToolset(javaToolset: JavaBaseToolset): KtToolset = JavaToolsetToKt(javaToolset)

  /** Adapts a whole collection of ADK Java toolsets. */
  @JvmStatic
  fun asKtToolsets(javaToolsets: List<JavaBaseToolset>): List<KtToolset> = javaToolsets.map {
    asKtToolset(it)
  }

  /**
   * Adapts an ADK Java plugin. Its callback contexts expose a per-call session snapshot; see
   * [JavaAdkToKt].
   */
  @JvmStatic fun asKtPlugin(javaPlugin: JavaPlugin): KtPlugin = JavaPluginToKt(javaPlugin)

  /** Adapts a whole collection of ADK Java plugins (e.g. a `Runner`'s `plugins`). */
  @JvmStatic
  fun asKtPlugins(javaPlugins: List<JavaPlugin>): List<KtPlugin> = javaPlugins.map {
    asKtPlugin(it)
  }

  @JvmStatic fun asKtModel(javaLlm: JavaBaseLlm): KtModel = JavaModelToKt(javaLlm)

  @JvmStatic
  fun asKtSessionService(service: JavaSessionService): KtSessionService =
    javaSessionServiceAsKt(service)

  @JvmStatic
  fun asKtArtifactService(service: JavaArtifactService): KtArtifactService =
    javaArtifactServiceAsKt(service)

  @JvmStatic
  fun asKtMemoryService(service: JavaMemoryService): KtMemoryService =
    javaMemoryServiceAsKt(service)
}
