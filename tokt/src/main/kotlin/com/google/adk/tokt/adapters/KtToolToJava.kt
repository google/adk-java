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

import com.google.adk.kt.tools.BaseTool as KtBaseTool
import com.google.adk.tokt.codecs.FunctionDeclarationCodec
import com.google.adk.tools.BaseTool as JavaBaseTool
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.genai.types.FunctionDeclaration as GenaiFunctionDeclaration
import io.reactivex.rxjava3.core.Single
import java.util.Optional

/**
 * An inspection-only Java view of a native Kotlin [KtBaseTool], so ADK Java plugin tool callbacks
 * (`beforeToolCallback`, `afterToolCallback`, `onToolErrorCallback`) can read a native Kotlin tool
 * as an ADK Java [JavaBaseTool]: name, description, long-running flag, declaration, and custom
 * metadata. It is not meant to be executed -- the Kotlin runner is the sole driver of tool
 * execution -- so [runAsync] fails loud with a descriptive error rather than doing anything.
 * Mirrors [KtAgentToJava].
 */
internal class KtToolToJava(internal val ktTool: KtBaseTool) :
  JavaBaseTool(ktTool.name, ktTool.description, ktTool.isLongRunning) {

  init {
    for ((key, value) in ktTool.customMetadata) {
      setCustomMetadata(key, value)
    }
  }

  override fun declaration(): Optional<GenaiFunctionDeclaration> =
    Optional.ofNullable(ktTool.declaration()?.let { FunctionDeclarationCodec.toJava(it) })

  @JvmSuppressWildcards
  override fun runAsync(
    args: Map<String, Any>,
    toolContext: JavaToolContext,
  ): Single<Map<String, Any>> =
    Single.error(
      UnsupportedOperationException(
        "This is an inspection-only view of a Kotlin engine tool, handed to Java plugin callbacks " +
          "via ktToolAsJava; the Kotlin runner is the sole driver of tool execution, so it cannot " +
          "be run from Java."
      )
    )
}

/**
 * Presents a Kotlin [tool] to a Java plugin callback as an ADK Java [JavaBaseTool]: a round-tripped
 * adapted Java tool ([JavaToolToKt]) unwraps to its original instance (so it fires natively), and a
 * native Kotlin tool gets an inspection-only [KtToolToJava] view.
 */
internal fun ktToolAsJava(tool: KtBaseTool): JavaBaseTool =
  (tool as? JavaToolToKt)?.javaTool ?: KtToolToJava(tool)
