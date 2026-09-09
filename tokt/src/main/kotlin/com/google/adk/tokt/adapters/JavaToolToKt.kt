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

import com.google.adk.kt.models.LlmRequest as KtLlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.FunctionDeclarationCodec
import com.google.adk.tokt.context.ktToolContextToJava
import com.google.adk.tools.BaseTool as JavaBaseTool
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext

/**
 * Exposes a Java [JavaBaseTool] as a Kotlin [BaseTool] so the Kotlin engine can invoke it. [run]
 * awaits the tool's RxJava result off the engine dispatcher, with actions delegating live to the
 * Kotlin context. [processLlmRequest] re-applies a tool's request edits while preserving
 * Kotlin-only fields (model, toolsDict).
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

  override suspend fun run(context: ToolContext, args: Map<String, Any?>): Any {
    val javaContext = ktToolContextToJava(context)
    // Run off the engine dispatcher so synchronous RxJava or blocking tool I/O cannot stall the
    // agent loop; the live actions view is backed by concurrent maps, so another thread is safe.
    val result =
      withContext(InteropDispatcher) {
        javaTool.runAsync(args, javaContext).toFlowable().awaitSingle()
      }
    // Carry back what the live view cannot (a setActions replacement, confirmations, skip-summary).
    reconcileActionsToKt(javaContext.actions(), context.actions)
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
}
