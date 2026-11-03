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

import com.google.adk.kt.types.AudioTranscriptionConfig as KtAudioTranscriptionConfig
import com.google.adk.kt.types.AudioTranscriptionConfigMode as KtAudioTranscriptionConfigMode
import com.google.genai.types.AudioTranscriptionConfig as GenaiAudioTranscriptionConfig
import com.google.genai.types.AudioTranscriptionConfigMode as GenaiAudioTranscriptionConfigMode
import kotlin.jvm.optionals.getOrNull

/**
 * Returns the genai [GenaiAudioTranscriptionConfig] view of this ADK Kotlin transcription setting.
 */
internal fun KtAudioTranscriptionConfig.toGenai(): GenaiAudioTranscriptionConfig {
  val builder = GenaiAudioTranscriptionConfig.builder()
  languageCodes?.let { builder.languageCodes(it) }
  customVocabulary?.let { builder.customVocabulary(it) }
  diarization?.let { builder.diarization(it) }
  wordTimestamp?.let { builder.wordTimestamp(it) }
  mode?.let { builder.mode(GenaiAudioTranscriptionConfigMode(it.name)) }
  return builder.build()
}

/** Returns the ADK Kotlin view of this genai transcription setting, without [unsupportedFields]. */
internal fun GenaiAudioTranscriptionConfig.toKt(): KtAudioTranscriptionConfig =
  KtAudioTranscriptionConfig(
    languageCodes = languageCodes().getOrNull(),
    customVocabulary = customVocabulary().getOrNull(),
    diarization = diarization().getOrNull(),
    wordTimestamp = wordTimestamp().getOrNull(),
    mode = mode().getOrNull()?.toKt(),
  )

/**
 * The fields set on this genai transcription setting that [GenaiAudioTranscriptionConfig.toKt]
 * cannot carry: the genai-only fields, and a `mode` ADK Kotlin has no constant for.
 */
internal val GenaiAudioTranscriptionConfig.unsupportedFields: List<String>
  get() = buildList {
    if (languageAuto().isPresent) add("languageAuto")
    if (languageHints().isPresent) add("languageHints")
    if (adaptationPhrases().isPresent) add("adaptationPhrases")
    val javaMode = mode().getOrNull()
    if (javaMode != null && javaMode.toKt() == null) add("mode")
  }

private fun GenaiAudioTranscriptionConfigMode.toKt(): KtAudioTranscriptionConfigMode? =
  enumByNameOrNull<KtAudioTranscriptionConfigMode>(knownEnum().name)
