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

import com.google.adk.kt.agents.ReadonlyContext
import com.google.adk.kt.models.LlmRequest as KtLlmRequest
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.tools.Toolset
import com.google.adk.tokt.context.KtReadonlyContextToJavaView
import com.google.adk.tokt.context.ktToolContextToJava
import com.google.adk.tools.BaseToolset as JavaBaseToolset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withContext

/**
 * Java -> Kotlin adapter: exposes a Java [JavaBaseToolset] as a Kotlin [Toolset] so the ADK Kotlin
 * can pull tools from a user-authored Java toolset at request time, with the live [ReadonlyContext]
 * ([KtReadonlyContextToJavaView]). Each Java tool the toolset returns is wrapped in a
 * [JavaToolToKt].
 *
 * Both `getTools` (tool provisioning) and `processLlmRequest` are bridged; the latter lets the Java
 * toolset mutate the request's contents/config, which are re-applied to the Kotlin request while
 * preserving Kotlin-only fields (model, toolsDict).
 */
internal class JavaToolsetToKt(internal val javaToolset: JavaBaseToolset) : Toolset {

  override suspend fun getTools(readonlyContext: ReadonlyContext?): List<BaseTool> {
    // ADK Java's BaseToolset.getTools requires a non-null ReadonlyContext (and the engine always
    // supplies one); fail fast with a clear message rather than passing null into user code.
    val javaContext =
      KtReadonlyContextToJavaView(
        requireNotNull(readonlyContext) {
          "A ReadonlyContext is required to get tools from a Java toolset"
        }
      )
    // Off the engine dispatcher: toolset discovery (getTools) often does blocking I/O.
    // RxJava Flowable<BaseTool> -> Single<List> -> Flowable -> awaitSingle().
    val javaTools =
      withContext(Dispatchers.IO) {
        javaToolset.getTools(javaContext).toList().toFlowable().awaitSingle()
      }
    return javaTools.map { JavaToolToKt(it) }
  }

  override suspend fun processLlmRequest(
    toolContext: ToolContext,
    llmRequest: KtLlmRequest,
  ): KtLlmRequest {
    val javaToolContext = ktToolContextToJava(toolContext)
    return bridgeProcessLlmRequest(llmRequest) { builder ->
      withContext(Dispatchers.IO) {
        javaToolset.processLlmRequest(builder, javaToolContext).toFlowable<Any>().awaitFirstOrNull()
      }
    }
  }

  override fun close() {
    javaToolset.close()
  }
}
