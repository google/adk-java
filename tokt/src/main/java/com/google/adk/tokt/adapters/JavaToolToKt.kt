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

import com.google.adk.events.EventActions as JavaEventActions
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.kt.models.LlmRequest as KtLlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.FunctionDeclarationCodec
import com.google.adk.tokt.codecs.ToolConfirmationCodec
import com.google.adk.tokt.codecs.reconcileRemovedSentinels
import com.google.adk.tokt.context.ktToolContextToJava
import com.google.adk.tools.BaseTool as JavaBaseTool
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext

/**
 * Java -> Kotlin adapter: exposes a Java [JavaBaseTool] as a Kotlin [BaseTool] so the ADK Kotlin
 * can invoke a user-authored Java tool.
 *
 * [run] converts the Kotlin [ToolContext] to a Java one ([ktToolContextToJava]), awaits the tool's
 * RxJava `Single` as a coroutine, and returns the result map. The converted context's actions
 * delegate live to the Kotlin context, so the tool's state and artifact deltas, and its
 * control-flow signals, are visible to the engine. [declaration] exposes the tool's schema
 * ([FunctionDeclarationCodec]) so a model can be prompted to call it.
 *
 * [processLlmRequest] is bridged too, so a Java tool that mutates the outgoing request (e.g.
 * [com.google.adk.tools.ExampleTool] injecting few-shot examples, or a built-in tool attaching
 * grounding config) takes effect. The tool's resulting contents/config are re-applied to the Kotlin
 * request while Kotlin-only fields (model, toolsDict) are preserved.
 */
internal class JavaToolToKt(internal val javaTool: JavaBaseTool) :
  BaseTool(
    javaTool.name(),
    javaTool.description(),
    javaTool.longRunning(),
    javaTool.customMetadata(),
  ) {

  override fun declaration(): FunctionDeclaration? =
    javaTool.declaration().map { FunctionDeclarationCodec.fromJava(it) }.getOrNull()

  override suspend fun run(context: ToolContext, args: Map<String, Any>): Any {
    val javaContext = ktToolContextToJava(context)
    // Run the user's Java tool off the engine dispatcher: RxJava is synchronous by default, so a
    // blocking tool must not stall the coroutine driving the agent loop. The live actions view it
    // writes is backed by thread-safe (concurrent) Kotlin maps, so the IO thread is safe.
    // RxJava Single -> Flowable (reactive-streams Publisher) -> awaitSingle().
    val result =
      withContext(InteropDispatcher) {
        javaTool.runAsync(args, javaContext).toFlowable().awaitSingle()
      }
    // No-op when the tool mutated the live actions() view; needed when a tool replaces its actions
    // wholesale (setActions(...)) or requests confirmations - copy those back onto the Kotlin
    // actions.
    copyActionsToKt(javaContext.actions(), context.actions)
    return result
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: KtLlmRequest,
  ): KtLlmRequest {
    val javaToolContext = ktToolContextToJava(toolContext)
    return bridgeProcessLlmRequest(llmRequest) { builder ->
      withContext(InteropDispatcher) {
        javaTool.processLlmRequest(builder, javaToolContext).toFlowable<Any>().awaitFirstOrNull()
      }
    }
  }

  private fun copyActionsToKt(java: JavaEventActions, kt: KtEventActions) {
    java.escalate().ifPresent { kt.escalate = it }
    java.transferToAgent().ifPresent { kt.transferToAgent = it }
    java.skipSummarization().ifPresent { kt.skipSummarization = it }
    kt.endOfAgent = kt.endOfAgent || java.endOfAgent()
    // Merge deltas only when the tool replaced the actions (otherwise the maps are the same live
    // Kotlin maps and are already in sync).
    if (java.stateDelta() !== kt.stateDelta) kt.stateDelta.putAll(java.stateDelta())
    if (java.artifactDelta() !== kt.artifactDelta) kt.artifactDelta.putAll(java.artifactDelta())
    // A Java state removal writes the Java sentinel into the (live) delta; map it to the Kotlin one
    // so the engine recognizes the deletion. Artifact deltas need no such translation: they are
    // plain filename -> version numbers on both sides, with no removal sentinel.
    reconcileRemovedSentinels(kt.stateDelta)
    // Tool confirmations the tool requested (toolContext.requestConfirmation(...)) live on the Java
    // actions only; carry them to the Kotlin actions so the confirmation flow emits the request
    // event.
    for ((id, confirmation) in java.requestedToolConfirmations()) {
      kt.requestedToolConfirmations[id] = ToolConfirmationCodec.fromJava(confirmation)
    }
  }
}
