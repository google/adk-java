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

import com.google.adk.kt.plugins.Plugin as KtPlugin
import com.google.adk.kt.plugins.PluginManager as KtPluginManager
import com.google.adk.plugins.Plugin as JavaPlugin
import com.google.adk.plugins.PluginManager as JavaPluginManager

/**
 * Exposes a Kotlin [KtPluginManager] (a runner's or an invocation's) through the ADK Java
 * [JavaPluginManager], so a Java caller - e.g. an `AgentTool` with `includePlugins` - sees the
 * engine's plugins. The view is read-only: the Kotlin engine's plugins are fixed at construction,
 * so [JavaPluginManager.registerPlugin] throws rather than silently dropping a late registration.
 */
internal fun ktPluginManagerAsJava(manager: KtPluginManager): JavaPluginManager =
  ReadOnlyPluginManager(manager.plugins.map(::ktPluginAsJava))

/**
 * Maps a Kotlin plugin to Java by unwrapping a round-tripped adapted Java plugin
 * ([JavaPluginToKt]), so it fires natively on the Java side. A native Kotlin plugin has no Java
 * form that could fire there - that would need a Java-to-Kotlin context bridge the one-way interop
 * does not provide - so it is rejected rather than silently skipped.
 */
internal fun ktPluginAsJava(plugin: KtPlugin): JavaPlugin =
  (plugin as? JavaPluginToKt)?.plugin
    ?: throw UnsupportedOperationException(
      "a native Kotlin plugin cannot be exposed through the ADK Java plugin manager"
    )

/** A [JavaPluginManager] populated once from the Kotlin side, after which registration throws. */
private class ReadOnlyPluginManager(plugins: List<JavaPlugin>) : JavaPluginManager() {
  init {
    // Populate through the superclass method; the override below rejects any later mutation.
    seed(plugins)
  }

  private fun seed(plugins: List<JavaPlugin>) = plugins.forEach { super.registerPlugin(it) }

  override fun registerPlugin(plugin: JavaPlugin): Unit =
    throw UnsupportedOperationException(
      "The Kotlin engine's plugins are fixed at construction; this Java view cannot register more"
    )
}
