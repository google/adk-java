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

import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.kt.agents.CallbackContext as KtCallbackContext
import com.google.adk.kt.agents.InvocationContext as KtInvocationContext
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.Event as KtEvent
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.kt.models.LlmRequest as KtLlmRequest
import com.google.adk.kt.models.LlmResponse as KtLlmResponse
import com.google.adk.kt.plugins.Plugin as KtPlugin
import com.google.adk.kt.tools.BaseTool as KtBaseTool
import com.google.adk.kt.tools.ToolContext as KtToolContext
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.plugins.Plugin as JavaPlugin
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.LlmRequestCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import com.google.adk.tokt.context.KtInvocationContextToJavaView
import com.google.adk.tokt.context.javaAgentView
import com.google.adk.tokt.context.ktCallbackContextToJava
import com.google.adk.tokt.context.ktToolContextToJava
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import java.util.logging.Logger
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingleOrNull
import kotlinx.coroutines.withContext

/**
 * Exposes an ADK Java [JavaPlugin] as a Kotlin [KtPlugin] so a Java app's plugins run on the Kotlin
 * runner. Each callback converts the Kotlin context to its Java view, invokes the plugin off the
 * engine dispatcher, and reconciles the actions it wrote back onto the Kotlin side. Tool-level
 * callbacks fire for every tool: an adapted Java tool ([JavaToolToKt]) unwraps to its original
 * instance, and a native Kotlin tool is presented through an inspection-only [KtToolToJava] view
 * (see [ktToolAsJava]) -- so the plugin can read the tool and write actions, but must not run it.
 */
internal class JavaPluginToKt(internal val plugin: JavaPlugin) : KtPlugin {

  private companion object {
    val logger: Logger = Logger.getLogger(JavaPluginToKt::class.java.name)
  }

  init {
    // The engine surfaces run-level errors through the event stream, not to plugins, so an
    // onRunErrorCallback override cannot fire here; warn and skip it rather than failing
    // adaptation.
    val onRunError =
      plugin.javaClass.getMethod(
        "onRunErrorCallback",
        JavaInvocationContext::class.java,
        Throwable::class.java,
      )
    // declaringClass is Plugin only when neither the class nor an intermediate interface overrode
    // it.
    if (onRunError.declaringClass != JavaPlugin::class.java) {
      logger.warning(
        "Bridged Java plugin '${plugin.name}' overrides onRunErrorCallback, which the ADK Kotlin " +
          "engine does not invoke; the override is skipped (run errors surface via the event stream)."
      )
    }
  }

  override val name: String
    get() = plugin.name

  /**
   * Runs a Java plugin callback off the engine dispatcher. Plugin callbacks may do blocking I/O
   * (logging, metrics, network); RxJava is synchronous by default, so running them on the coroutine
   * that drives the agent loop could stall it.
   */
  private suspend fun <T : Any> onIo(source: () -> Maybe<T>): T? =
    withContext(InteropDispatcher) { source().awaitSingleOrNull() }

  /** As [onIo], for the two callbacks that report completion rather than a value. */
  private suspend fun completeOnIo(source: () -> Completable) {
    withContext(InteropDispatcher) { source().await() }
  }

  // Run-level callbacks.

  override suspend fun onUserMessage(
    invocationContext: KtInvocationContext,
    userMessage: KtContent,
  ): KtContent {
    val javaContext = KtInvocationContextToJavaView(invocationContext)
    val replacement = onIo {
      plugin.onUserMessageCallback(javaContext, ContentCodec.toJava(userMessage))
    }
    return replacement?.let { ContentCodec.fromJava(it) } ?: userMessage
  }

  override suspend fun beforeRun(
    invocationContext: KtInvocationContext
  ): CallbackChoice<Unit, KtContent> {
    val javaContext = KtInvocationContextToJavaView(invocationContext)
    val halt = onIo { plugin.beforeRunCallback(javaContext) }
    return if (halt != null) CallbackChoice.Break(ContentCodec.fromJava(halt))
    else CallbackChoice.Continue(Unit)
  }

  override suspend fun onEvent(invocationContext: KtInvocationContext, event: KtEvent): KtEvent {
    val javaContext = KtInvocationContextToJavaView(invocationContext)
    val replacement = onIo { plugin.onEventCallback(javaContext, EventCodec.toJava(event)) }
    return replacement?.let { EventCodec.fromJava(it) } ?: event
  }

  override suspend fun afterRun(invocationContext: KtInvocationContext) {
    val javaContext = KtInvocationContextToJavaView(invocationContext)
    completeOnIo { plugin.afterRunCallback(javaContext) }
  }

  // Agent-level callbacks.

  override suspend fun beforeAgent(
    context: KtCallbackContext
  ): CallbackChoice<KtEventActions, KtContent> {
    val javaAgent = javaAgentView(context)
    val javaContext = ktCallbackContextToJava(context, javaAgent)
    val override = onIo { plugin.beforeAgentCallback(javaAgent, javaContext) }
    reconcileActionsToKt(javaContext.eventActions(), context.eventActions)
    return if (override != null) CallbackChoice.Break(ContentCodec.fromJava(override))
    else CallbackChoice.Continue(KtEventActions())
  }

  override suspend fun afterAgent(context: KtCallbackContext): CallbackChoice<Unit, KtContent> {
    val javaAgent = javaAgentView(context)
    val javaContext = ktCallbackContextToJava(context, javaAgent)
    val override = onIo { plugin.afterAgentCallback(javaAgent, javaContext) }
    reconcileActionsToKt(javaContext.eventActions(), context.eventActions)
    return if (override != null) CallbackChoice.Break(ContentCodec.fromJava(override))
    else CallbackChoice.Continue(Unit)
  }

  // Model-level callbacks.

  override suspend fun beforeModel(
    context: KtCallbackContext,
    request: KtLlmRequest,
  ): CallbackChoice<KtLlmRequest, KtLlmResponse> {
    val javaContext = ktCallbackContextToJava(context, javaAgentView(context))
    val builder = LlmRequestCodec.toJava(request).toBuilder()
    val override = onIo { plugin.beforeModelCallback(javaContext, builder) }
    reconcileActionsToKt(javaContext.eventActions(), context.eventActions)
    return if (override != null) CallbackChoice.Break(LlmResponseCodec.fromJava(override))
    // Re-apply onto the original request to preserve Kotlin-only fields (toolsDict, cache config);
    // LlmRequestCodec.fromJava would build a fresh request and drop them, breaking agent transfer.
    else CallbackChoice.Continue(reapplyJavaRequest(request, builder.build()))
  }

  override suspend fun afterModel(
    context: KtCallbackContext,
    response: KtLlmResponse,
  ): KtLlmResponse {
    val javaContext = ktCallbackContextToJava(context, javaAgentView(context))
    val override = onIo {
      plugin.afterModelCallback(javaContext, LlmResponseCodec.toJava(response))
    }
    reconcileActionsToKt(javaContext.eventActions(), context.eventActions)
    return if (override != null) LlmResponseCodec.fromJava(override) else response
  }

  override suspend fun onModelError(
    context: KtCallbackContext,
    request: KtLlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, KtLlmResponse> {
    val javaContext = ktCallbackContextToJava(context, javaAgentView(context))
    val builder = LlmRequestCodec.toJava(request).toBuilder()
    val fallback = onIo { plugin.onModelErrorCallback(javaContext, builder, error) }
    reconcileActionsToKt(javaContext.eventActions(), context.eventActions)
    return if (fallback != null) CallbackChoice.Break(LlmResponseCodec.fromJava(fallback))
    else CallbackChoice.Continue(Unit)
  }

  // Tool-level callbacks. An adapted Java tool fires natively; a native Kotlin tool is passed
  // through an inspection-only Java view ([KtToolToJava]).

  override suspend fun beforeTool(
    context: KtToolContext,
    tool: KtBaseTool,
    args: Map<String, Any?>,
  ): CallbackChoice<Map<String, Any?>, Map<String, Any?>> {
    val javaTool = ktToolAsJava(tool)
    val javaContext = ktToolContextToJava(context)
    val mutableArgs = args.toMutableMap()
    val override = onIo { plugin.beforeToolCallback(javaTool, mutableArgs, javaContext) }
    reconcileActionsToKt(javaContext.actions(), context.actions)
    return if (override != null) CallbackChoice.Break(override)
    else CallbackChoice.Continue(mutableArgs)
  }

  override suspend fun afterTool(
    context: KtToolContext,
    tool: KtBaseTool,
    args: Map<String, Any?>,
    result: Map<String, Any?>,
  ): Map<String, Any?> {
    val javaTool = ktToolAsJava(tool)
    val javaContext = ktToolContextToJava(context)
    val override = onIo { plugin.afterToolCallback(javaTool, args, javaContext, result) }
    reconcileActionsToKt(javaContext.actions(), context.actions)
    return override ?: result
  }

  override suspend fun onToolError(
    context: KtToolContext,
    tool: KtBaseTool,
    args: Map<String, Any?>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any?>> {
    val javaTool = ktToolAsJava(tool)
    val javaContext = ktToolContextToJava(context)
    val fallback = onIo { plugin.onToolErrorCallback(javaTool, args, javaContext, error) }
    reconcileActionsToKt(javaContext.actions(), context.actions)
    return if (fallback != null) CallbackChoice.Break(fallback) else CallbackChoice.Continue(Unit)
  }

  override fun close() {
    // Plugin.close() is non-suspend (runner shutdown); block on the Java Completable.
    plugin.close().blockingAwait()
  }
}
