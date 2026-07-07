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

/**
 * Converts the Kotlin `kt.agents.RunConfig` to the ADK Java [JavaRunConfig] a bridged Java
 * component reads through its invocation context.
 *
 * Only the three settings both frameworks model cross: streaming mode, the LLM call budget, and
 * custom metadata. The Java-only settings (response modalities, speech and avatar config, audio
 * transcription, tool execution mode, save-input-blobs, auto-create-session, and the
 * group-function-responses override) keep their Java defaults, as the Kotlin engine has nothing to
 * source them from.
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
}
