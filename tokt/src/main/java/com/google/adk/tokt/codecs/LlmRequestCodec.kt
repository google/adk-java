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

package com.google.adk.tokt.codecs

import com.google.adk.kt.models.LlmRequest as KtLlmRequest
import com.google.adk.kt.types.GenerateContentConfig as KtConfig
import com.google.adk.models.LlmRequest as JavaLlmRequest
import kotlin.jvm.optionals.getOrNull

/**
 * Converts a Kotlin `kt.models.LlmRequest` to the ADK Java [JavaLlmRequest] expected by a Java
 * `BaseLlm`, so the Kotlin agent loop can call a user's Java model ([JavaModelToKt]).
 *
 * Carries the model name, the request contents, and the [config][GenerateContentConfigCodec]
 * (system instruction, tools / function declarations, generation parameters).
 */
internal object LlmRequestCodec {

  /** Returns the Java [JavaLlmRequest] view of the Kotlin [request]. */
  fun toJava(request: KtLlmRequest): JavaLlmRequest {
    val builder =
      JavaLlmRequest.builder().contents(request.contents.map { ContentCodec.toJava(it) })
    request.model?.let { builder.model(it.name) }
    builder.config(GenerateContentConfigCodec.toJava(request.config))
    return builder.build()
  }

  /**
   * Returns the Kotlin [KtLlmRequest] view of the Java [request]. The model name is left unset; the
   * Kotlin model (e.g. [com.google.adk.kt.models.Gemini]) resolves it from its own configuration.
   */
  fun fromJava(request: JavaLlmRequest): KtLlmRequest =
    KtLlmRequest(
      contents = request.contents().map { ContentCodec.fromJava(it) },
      config =
        request.config().map { GenerateContentConfigCodec.fromJava(it) }.getOrNull() ?: KtConfig(),
    )
}
