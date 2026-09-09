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

import com.google.adk.agents.RunConfig as JavaRunConfig
import com.google.adk.kt.agents.RunConfig as KtRunConfig
import com.google.adk.kt.agents.StreamingMode as KtStreamingMode

/**
 * Converts between the Kotlin `kt.agents.RunConfig` and the ADK Java [JavaRunConfig].
 *
 * Only three settings cross both ways: streaming mode, the LLM call budget, and custom metadata.
 * [toJava] leaves the Java-only settings at their defaults; [fromJava] instead rejects a Java
 * setting the engine cannot honor rather than dropping it silently.
 */
internal object RunConfigCodec {

  /** Returns the Java [JavaRunConfig] view of the Kotlin [config]. */
  fun toJava(config: KtRunConfig): JavaRunConfig =
    JavaRunConfig.builder()
      // Kotlin has no BIDI, so a by-name match always resolves; NONE is the shared default.
      .streamingMode(
        enumByNameOrNull<JavaRunConfig.StreamingMode>(config.streamingMode.name)
          ?: JavaRunConfig.StreamingMode.NONE
      )
      .maxLlmCalls(config.maxLlmCalls)
      .customMetadata(config.customMetadata.orEmpty())
      .build()

  /** Returns the Kotlin [KtRunConfig] view of the Java [config]. */
  @Suppress(
    "deprecation"
  ) // Reads the deprecated groupFunctionResponsesInHistoryOverride to reject it.
  fun fromJava(config: JavaRunConfig): KtRunConfig {
    val unsupported = buildList {
      if (config.streamingMode() == JavaRunConfig.StreamingMode.BIDI) add("streamingMode=BIDI")
      if (config.saveInputBlobsAsArtifacts()) add("saveInputBlobsAsArtifacts")
      if (config.toolExecutionMode() != JavaRunConfig.ToolExecutionMode.NONE)
        add("toolExecutionMode")
      if (config.responseModalities().isNotEmpty()) add("responseModalities")
      if (config.speechConfig() != null) add("speechConfig")
      if (config.avatarConfig() != null) add("avatarConfig")
      if (config.outputAudioTranscription() != null) add("outputAudioTranscription")
      if (config.inputAudioTranscription() != null) add("inputAudioTranscription")
      if (config.groupFunctionResponsesInHistoryOverride().isPresent)
        add("groupFunctionResponsesInHistoryOverride")
    }
    require(unsupported.isEmpty()) {
      "RunConfig settings not supported by the ADK Kotlin engine: $unsupported"
    }
    return KtRunConfig(
      // Only NONE and SSE reach here; BIDI is rejected above.
      streamingMode =
        enumByNameOrNull<KtStreamingMode>(config.streamingMode().name) ?: KtStreamingMode.NONE,
      maxLlmCalls = config.maxLlmCalls(),
      customMetadata = config.customMetadata().takeIf { it.isNotEmpty() },
    )
  }
}
