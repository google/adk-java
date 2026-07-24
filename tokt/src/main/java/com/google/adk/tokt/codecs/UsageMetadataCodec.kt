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

import com.google.adk.kt.types.MediaModality as KtMediaModality
import com.google.adk.kt.types.ModalityTokenCount as KtModalityTokenCount
import com.google.adk.kt.types.UsageMetadata as KtUsageMetadata
import com.google.genai.types.GenerateContentResponseUsageMetadata as GenaiUsageMetadata
import com.google.genai.types.MediaModality as GenaiMediaModality
import com.google.genai.types.ModalityTokenCount as GenaiModalityTokenCount
import kotlin.jvm.optionals.getOrNull

/**
 * Converts token-usage metadata between the genai
 * [GenerateContentResponseUsageMetadata][GenaiUsageMetadata] the ADK Java facade exposes and the
 * Kotlin's `kt.types.UsageMetadata`. Carries the prompt/candidates/total/thoughts/tool-use/cached
 * token counts and the per-modality token breakdowns. Shared by [LlmResponseCodec] and
 * [EventCodec].
 */
internal object UsageMetadataCodec {

  /** Returns the Kotlin [KtUsageMetadata] view of the genai [usage]. */
  fun fromJava(usage: GenaiUsageMetadata): KtUsageMetadata =
    KtUsageMetadata(
      promptTokenCount = usage.promptTokenCount().getOrNull(),
      candidatesTokenCount = usage.candidatesTokenCount().getOrNull(),
      totalTokenCount = usage.totalTokenCount().getOrNull(),
      thoughtsTokenCount = usage.thoughtsTokenCount().getOrNull(),
      toolUsePromptTokenCount = usage.toolUsePromptTokenCount().getOrNull(),
      cachedContentTokenCount = usage.cachedContentTokenCount().getOrNull(),
      promptTokensDetails = usage.promptTokensDetails().getOrNull()?.map { modalityFromJava(it) },
      candidatesTokensDetails =
        usage.candidatesTokensDetails().getOrNull()?.map { modalityFromJava(it) },
    )

  /** Returns the genai [GenaiUsageMetadata] view of the Kotlin [usage]. */
  fun toJava(usage: KtUsageMetadata): GenaiUsageMetadata {
    val builder = GenaiUsageMetadata.builder()
    usage.promptTokenCount?.let { builder.promptTokenCount(it) }
    usage.candidatesTokenCount?.let { builder.candidatesTokenCount(it) }
    usage.totalTokenCount?.let { builder.totalTokenCount(it) }
    usage.thoughtsTokenCount?.let { builder.thoughtsTokenCount(it) }
    usage.toolUsePromptTokenCount?.let { builder.toolUsePromptTokenCount(it) }
    usage.cachedContentTokenCount?.let { builder.cachedContentTokenCount(it) }
    usage.promptTokensDetails?.let {
      builder.promptTokensDetails(it.map { m -> modalityToJava(m) })
    }
    usage.candidatesTokensDetails?.let {
      builder.candidatesTokensDetails(it.map { m -> modalityToJava(m) })
    }
    return builder.build()
  }

  private fun modalityFromJava(count: GenaiModalityTokenCount): KtModalityTokenCount =
    KtModalityTokenCount(
      modality =
        enumByNameOrNull<KtMediaModality> { count.modality().getOrNull()?.knownEnum()?.name },
      tokenCount = count.tokenCount().getOrNull(),
    )

  private fun modalityToJava(count: KtModalityTokenCount): GenaiModalityTokenCount {
    val builder = GenaiModalityTokenCount.builder()
    count.modality?.let { builder.modality(GenaiMediaModality(it.name)) }
    count.tokenCount?.let { builder.tokenCount(it) }
    return builder.build()
  }
}
