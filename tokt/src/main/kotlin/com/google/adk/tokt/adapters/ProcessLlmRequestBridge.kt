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
import com.google.adk.models.LlmRequest as JavaLlmRequest
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.GenerateContentConfigCodec
import com.google.adk.tokt.codecs.LlmRequestCodec
import kotlin.jvm.optionals.getOrNull

/**
 * Re-applies the (possibly mutated) contents/config of a Java [javaResult] onto the original Kotlin
 * [request], preserving Kotlin-only fields the Java view cannot carry (model, toolsDict,
 * cacheConfig, cacheMetadata, cacheableContentsTokenCount). Use this instead of
 * [LlmRequestCodec.fromJava], which builds a fresh request and drops those fields.
 */
internal fun reapplyJavaRequest(request: KtLlmRequest, javaResult: JavaLlmRequest): KtLlmRequest =
  request.copy(
    contents = javaResult.contents().map { ContentCodec.fromJava(it) },
    config =
      javaResult.config().map { GenerateContentConfigCodec.fromJava(it) }.getOrNull()
        ?: request.config,
  )

/**
 * Shared bridge for a Java tool's / toolset's `processLlmRequest`: converts the Kotlin request to a
 * Java builder, runs [applyJavaMutation] (the Java `processLlmRequest`, which the caller runs off
 * the engine dispatcher), then re-applies the mutated contents/config back onto the Kotlin request
 * while preserving Kotlin-only fields.
 */
internal suspend fun bridgeProcessLlmRequest(
  llmRequest: KtLlmRequest,
  applyJavaMutation: suspend (JavaLlmRequest.Builder) -> Unit,
): KtLlmRequest {
  val builder = LlmRequestCodec.toJava(llmRequest).toBuilder()
  applyJavaMutation(builder)
  return reapplyJavaRequest(llmRequest, builder.build())
}
