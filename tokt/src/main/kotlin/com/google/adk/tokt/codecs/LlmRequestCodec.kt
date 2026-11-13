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
import com.google.adk.kt.types.LiveConnectConfig as KtLiveConnectConfig
import com.google.adk.kt.types.Modality as KtModality
import com.google.adk.kt.types.Role
import com.google.adk.models.LlmRequest as JavaLlmRequest
import kotlin.jvm.optionals.getOrNull

/**
 * Converts a Kotlin `kt.models.LlmRequest` to the ADK Java [JavaLlmRequest] expected by a Java
 * `BaseLlm`, so the Kotlin agent loop can call a user's Java model
 * ([JavaModelToKt][com.google.adk.tokt.adapters.JavaModelToKt]).
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
   * Returns the Java [JavaLlmRequest] a Java model opens a live connection with. The agent's
   * instruction, tools, thinking and safety settings are folded into its live config, because that
   * is all a Java model reads when it connects. An unset response modality is sent as audio, which
   * is what the Kotlin live config means by it; a Java connection without one opens as text.
   */
  fun toJavaLive(request: KtLlmRequest): JavaLlmRequest {
    val liveConfig = request.liveConnectConfig.carryingAgentConfig(request.config)
    val modalities = liveConfig.responseModalities.orEmpty().ifEmpty { listOf(KtModality.AUDIO) }
    return toJava(request)
      .toBuilder()
      .liveConnectConfig(
        LiveConnectConfigCodec.toJava(liveConfig.copy(responseModalities = modalities))
      )
      .build()
  }

  /**
   * Returns this live config with the agent's instruction and tools, its thinking when set, and its
   * safety settings when the live config has none.
   */
  private fun KtLiveConnectConfig.carryingAgentConfig(config: KtConfig): KtLiveConnectConfig =
    copy(
      // Instruction/tools replace the live config's; absent one omitted, not sent empty (1007).
      systemInstruction = config.systemInstruction?.copy(role = Role.SYSTEM),
      tools = config.tools,
      // The agent's thinking wins; the live config's own safety wins over the agent's.
      thinkingConfig = config.thinkingConfig ?: thinkingConfig,
      safetySettings = safetySettings ?: config.safetySettings,
    )

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
