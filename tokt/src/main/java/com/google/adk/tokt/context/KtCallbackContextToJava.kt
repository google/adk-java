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

package com.google.adk.tokt.context

import com.google.adk.agents.BaseAgent as JavaBaseAgent
import com.google.adk.agents.CallbackContext as JavaCallbackContext
import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.kt.agents.CallbackContext as KtCallbackContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.plugins.Plugin as JavaPlugin
import com.google.adk.sessions.BaseSessionService as JavaBaseSessionService
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.KtEventActionsToJavaView
import com.google.adk.tokt.codecs.RunConfigCodec
import com.google.adk.tokt.codecs.ktSessionToJavaLive
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import java.util.Optional

/**
 * A Java [JavaInvocationContext] backed by a Kotlin [KtCallbackContext]'s readonly view. The
 * session (set on the builder) is a live view of the current Kotlin session; the artifact / memory
 * services and user content the callback context exposes are wired through so a Java plugin
 * callback can load / save artifacts and add to memory.
 */
private class KtCallbackContextToJavaView(
  private val context: KtCallbackContext,
  private val agent: JavaBaseAgent,
) : JavaInvocationContext(callbackContextJavaBuilder(context)) {

  override fun invocationId(): String = context.invocationId

  override fun branch(): Optional<String> = Optional.ofNullable(context.branch)

  override fun agent(): JavaBaseAgent = agent

  override fun sessionService(): JavaBaseSessionService =
    throw UnsupportedOperationException(
      "A Kotlin callback context does not expose its session service, so it is unavailable to a " +
        "bridged Java plugin callback"
    )

  // Plugins run on the Kotlin engine, so the plugin manager is unavailable here too.
  override fun pluginManager(): JavaPlugin =
    throw UnsupportedOperationException(
      "Plugins run on the Kotlin engine and are not exposed through this bridged Java context"
    )

  @OptIn(FrameworkInternalApi::class)
  override fun callbackContextData(): MutableMap<String, Any> = context.callbackContextData
}

/**
 * Builds the base builder for [KtCallbackContextToJavaView], wiring the artifact / memory services
 * (unwrapped where possible) and user content the readonly [KtCallbackContext] exposes. A Kotlin
 * callback context does not expose its session service, so the view throws for it rather than
 * leaving the field null: this builder bypasses `build()`, so `validate()` never runs and the unset
 * field would otherwise surface as an NPE at the plugin's own call site.
 */
private fun callbackContextJavaBuilder(context: KtCallbackContext): JavaInvocationContext.Builder {
  val builder =
    JavaInvocationContext.builder()
      .invocationId(context.invocationId)
      // Live view: events + state read through to the current Kotlin session.
      .session(ktSessionToJavaLive(context.session))
  // Without this, a plugin reading callbackContext.invocationContext().runConfig() in a
  // before/afterModel or before/afterAgent callback would see a default RunConfig, not the run's.
  context.runConfig?.let { builder.runConfig(RunConfigCodec.toJava(it)) }
  context.artifactService?.let { builder.artifactService(ktArtifactServiceAsJava(it)) }
  context.memoryService?.let { builder.memoryService(ktMemoryServiceAsJava(it)) }
  context.userContent?.let { builder.userContent(ContentCodec.toJava(it)) }
  return builder
}

/**
 * Presents the Kotlin [context] as a Java [JavaCallbackContext] (reusing [KtEventActionsToJavaView]
 * so a callback's state / artifact deltas write straight through to the Kotlin context). [agent] is
 * the Java agent the callback belongs to, so a callback sees the correct `context.agent()`.
 */
internal fun ktCallbackContextToJava(
  context: KtCallbackContext,
  agent: JavaBaseAgent,
): JavaCallbackContext =
  JavaCallbackContext(
    KtCallbackContextToJavaView(context, agent),
    KtEventActionsToJavaView(context.eventActions),
  )
