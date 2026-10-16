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

import com.google.adk.kt.types.Transcription as KtTranscription
import com.google.adk.kt.types.WordInfo as KtWordInfo
import com.google.genai.types.Transcription as GenaiTranscription
import com.google.genai.types.WordInfo as GenaiWordInfo
import kotlin.jvm.optionals.getOrNull

/**
 * Converts an audio transcription, with its per-word timings, between the ADK Kotlin
 * [KtTranscription] and the genai [GenaiTranscription] that ADK Java events and LLM responses
 * carry. The genai `startOffset` and `endOffset` have no ADK Kotlin field, so they are dropped.
 */
internal object TranscriptionCodec {

  /** Returns the genai [GenaiTranscription] view of the Kotlin [transcription]. */
  fun toJava(transcription: KtTranscription): GenaiTranscription {
    val builder = GenaiTranscription.builder()
    transcription.text?.let { builder.text(it) }
    transcription.finished?.let { builder.finished(it) }
    transcription.languageCode?.let { builder.languageCode(it) }
    transcription.speakerLabel?.let { builder.speakerLabel(it) }
    transcription.words?.let { words -> builder.words(words.map(::wordToJava)) }
    return builder.build()
  }

  /** Returns the Kotlin [KtTranscription] view of the genai [transcription]. */
  fun fromJava(transcription: GenaiTranscription): KtTranscription =
    KtTranscription(
      text = transcription.text().getOrNull(),
      finished = transcription.finished().getOrNull(),
      languageCode = transcription.languageCode().getOrNull(),
      speakerLabel = transcription.speakerLabel().getOrNull(),
      words = transcription.words().getOrNull()?.map(::wordFromJava),
    )

  private fun wordToJava(word: KtWordInfo): GenaiWordInfo {
    val builder = GenaiWordInfo.builder()
    word.word?.let { builder.word(it) }
    word.startOffset?.let { builder.startOffset(it) }
    word.endOffset?.let { builder.endOffset(it) }
    return builder.build()
  }

  private fun wordFromJava(word: GenaiWordInfo): KtWordInfo =
    KtWordInfo(
      word = word.word().getOrNull(),
      startOffset = word.startOffset().getOrNull(),
      endOffset = word.endOffset().getOrNull(),
    )
}
