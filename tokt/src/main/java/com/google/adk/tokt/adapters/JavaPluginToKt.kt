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
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.LlmRequestCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import com.google.adk.tokt.codecs.reconcileRemovedSentinels
import com.google.adk.tokt.context.ktCallbackContextToJava
import com.google.adk.tokt.context.ktInvocationContextToJava
import com.google.adk.tokt.context.ktToolContextToJava
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext

/**
 * Java -> Kotlin adapter: exposes an ADK Java [JavaPlugin] as a Kotlin [KtPlugin] so a Java app's
 * plugins run on the Kotlin runner. Each callback converts the Kotlin context to its Java facade
 * ([ktInvocationContextToJava] / [ktCallbackContextToJava] / [ktToolContextToJava]), invokes the
 * Java plugin, and awaits its RxJava result as a coroutine.
 *
 * The Java agent each callback sees is the running engine agent wrapped via [ktAgentAsJava], so no
 * root agent has to be supplied. Tool-level callbacks apply only to Kt-backed Java tools
 * ([JavaToolToKt]); native Kotlin tools pass through unchanged.
 */
internal class JavaPluginToKt(private val plugin: JavaPlugin) : KtPlugin {

  override val name: String
    get() = plugin.name

  /**
   * Runs a Java plugin callback off the engine dispatcher. Plugin callbacks may do blocking I/O
   * (logging, metrics, network); RxJava is synchronous by default, so running them on the coroutine
   * that drives the agent loop could stall it.
   */
  private suspend fun <T : Any> onIo(source: () -> Flowable<T>): T? =
    withContext(Dispatchers.IO) { source().awaitFirstOrNull() }

  /**
   * Maps any Java removal sentinel a plugin wrote through the live callback state (which is backed
   * by the Kotlin delta map) to the Kotlin sentinel, so the engine recognizes the deletion.
   */
  private fun reconcileState(context: KtCallbackContext) =
    reconcileRemovedSentinels(context.eventActions.stateDelta)

  /**
   * As [reconcileState], for the live tool-context actions a tool-callback plugin writes through.
   */
  private fun reconcileToolState(context: KtToolContext) =
    reconcileRemovedSentinels(context.actions.stateDelta)

  // Run-level callbacks.

  override suspend fun onUserMessage(
    invocationContext: KtInvocationContext,
    userMessage: KtContent,
  ): KtContent {
    val javaContext =
      ktInvocationContextToJava(invocationContext, ktAgentAsJava(invocationContext.agent))
    val replacement = onIo {
      plugin.onUserMessageCallback(javaContext, ContentCodec.toJava(userMessage)).toFlowable()
    }
    return replacement?.let { ContentCodec.fromJava(it) } ?: userMessage
  }

  override suspend fun beforeRun(
    invocationContext: KtInvocationContext
  ): CallbackChoice<Unit, KtContent> {
    val javaContext =
      ktInvocationContextToJava(invocationContext, ktAgentAsJava(invocationContext.agent))
    val halt = onIo { plugin.beforeRunCallback(javaContext).toFlowable() }
    return if (halt != null) CallbackChoice.Break(ContentCodec.fromJava(halt))
    else CallbackChoice.Continue(Unit)
  }

  override suspend fun onEvent(invocationContext: KtInvocationContext, event: KtEvent): KtEvent {
    val javaContext =
      ktInvocationContextToJava(invocationContext, ktAgentAsJava(invocationContext.agent))
    val replacement = onIo {
      plugin.onEventCallback(javaContext, EventCodec.toJava(event)).toFlowable()
    }
    return replacement?.let { EventCodec.fromJava(it) } ?: event
  }

  override suspend fun afterRun(invocationContext: KtInvocationContext) {
    val javaContext =
      ktInvocationContextToJava(invocationContext, ktAgentAsJava(invocationContext.agent))
    onIo { plugin.afterRunCallback(javaContext).toFlowable<Any>() }
  }

  // Agent-level callbacks.

  override suspend fun beforeAgent(
    context: KtCallbackContext
  ): CallbackChoice<KtEventActions, KtContent> {
    val javaAgent = ktAgentAsJava(context.agent)
    val override = onIo {
      plugin
        .beforeAgentCallback(javaAgent, ktCallbackContextToJava(context, javaAgent))
        .toFlowable()
    }
    reconcileState(context)
    return if (override != null) CallbackChoice.Break(ContentCodec.fromJava(override))
    else CallbackChoice.Continue(KtEventActions())
  }

  override suspend fun afterAgent(context: KtCallbackContext): CallbackChoice<Unit, KtContent> {
    val javaAgent = ktAgentAsJava(context.agent)
    val override = onIo {
      plugin.afterAgentCallback(javaAgent, ktCallbackContextToJava(context, javaAgent)).toFlowable()
    }
    reconcileState(context)
    return if (override != null) CallbackChoice.Break(ContentCodec.fromJava(override))
    else CallbackChoice.Continue(Unit)
  }

  // Model-level callbacks.

  override suspend fun beforeModel(
    context: KtCallbackContext,
    request: KtLlmRequest,
  ): CallbackChoice<KtLlmRequest, KtLlmResponse> {
    val builder = LlmRequestCodec.toJava(request).toBuilder()
    val override = onIo {
      plugin
        .beforeModelCallback(
          ktCallbackContextToJava(context, ktAgentAsJava(context.agent)),
          builder,
        )
        .toFlowable()
    }
    reconcileState(context)
    return if (override != null) CallbackChoice.Break(LlmResponseCodec.fromJava(override))
    // Re-apply onto the original request to preserve Kotlin-only fields (toolsDict, cache config);
    // LlmRequestCodec.fromJava would build a fresh request and drop them, breaking agent transfer.
    else CallbackChoice.Continue(reapplyJavaRequest(request, builder.build()))
  }

  override suspend fun afterModel(
    context: KtCallbackContext,
    response: KtLlmResponse,
  ): KtLlmResponse {
    val override = onIo {
      plugin
        .afterModelCallback(
          ktCallbackContextToJava(context, ktAgentAsJava(context.agent)),
          LlmResponseCodec.toJava(response),
        )
        .toFlowable()
    }
    reconcileState(context)
    return if (override != null) LlmResponseCodec.fromJava(override) else response
  }

  override suspend fun onModelError(
    context: KtCallbackContext,
    request: KtLlmRequest,
    error: Throwable,
  ): CallbackChoice<Unit, KtLlmResponse> {
    val builder = LlmRequestCodec.toJava(request).toBuilder()
    val fallback = onIo {
      plugin
        .onModelErrorCallback(
          ktCallbackContextToJava(context, ktAgentAsJava(context.agent)),
          builder,
          error,
        )
        .toFlowable()
    }
    reconcileState(context)
    return if (fallback != null) CallbackChoice.Break(LlmResponseCodec.fromJava(fallback))
    else CallbackChoice.Continue(Unit)
  }

  // Tool-level callbacks (only Kt-backed Java tools).

  override suspend fun beforeTool(
    context: KtToolContext,
    tool: KtBaseTool,
    args: Map<String, Any>,
  ): CallbackChoice<Map<String, Any>, Map<String, Any>> {
    val javaTool = (tool as? JavaToolToKt)?.javaTool ?: return CallbackChoice.Continue(args)
    val mutableArgs = args.toMutableMap()
    val override = onIo {
      plugin.beforeToolCallback(javaTool, mutableArgs, ktToolContextToJava(context)).toFlowable()
    }
    reconcileToolState(context)
    return if (override != null) CallbackChoice.Break(override)
    else CallbackChoice.Continue(mutableArgs)
  }

  override suspend fun afterTool(
    context: KtToolContext,
    tool: KtBaseTool,
    args: Map<String, Any>,
    result: Map<String, Any>,
  ): Map<String, Any> {
    val javaTool = (tool as? JavaToolToKt)?.javaTool ?: return result
    val override = onIo {
      plugin.afterToolCallback(javaTool, args, ktToolContextToJava(context), result).toFlowable()
    }
    reconcileToolState(context)
    return override ?: result
  }

  override suspend fun onToolError(
    context: KtToolContext,
    tool: KtBaseTool,
    args: Map<String, Any>,
    error: Throwable,
  ): CallbackChoice<Unit, Map<String, Any>> {
    val javaTool = (tool as? JavaToolToKt)?.javaTool ?: return CallbackChoice.Continue(Unit)
    val fallback = onIo {
      plugin.onToolErrorCallback(javaTool, args, ktToolContextToJava(context), error).toFlowable()
    }
    reconcileToolState(context)
    return if (fallback != null) CallbackChoice.Break(fallback) else CallbackChoice.Continue(Unit)
  }

  override suspend fun close() {
    onIo { plugin.close().toFlowable<Any>() }
  }
}
