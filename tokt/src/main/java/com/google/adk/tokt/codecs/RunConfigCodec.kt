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
 * Only the three settings both frameworks model cross: streaming mode, the LLM call budget, and
 * custom metadata. Each direction keeps the other side's defaults for its own extra settings, as
 * neither engine has anything to source them from.
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
  fun fromJava(config: JavaRunConfig): KtRunConfig =
    KtRunConfig(
      // Java's BIDI has no Kotlin constant, so it falls back to NONE.
      streamingMode =
        enumByNameOrNull<KtStreamingMode>(config.streamingMode().name) ?: KtStreamingMode.NONE,
      maxLlmCalls = config.maxLlmCalls(),
      customMetadata = config.customMetadata().takeIf { it.isNotEmpty() },
    )
}
