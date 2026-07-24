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

import com.google.adk.kt.models.LlmResponse as KtLlmResponse
import com.google.adk.kt.types.FinishReason as KtFinishReason
import com.google.adk.models.LlmResponse as JavaLlmResponse
import com.google.genai.types.FinishReason as GenaiFinishReason
import kotlin.jvm.optionals.getOrNull

/**
 * Converts an LLM response between the ADK Java [JavaLlmResponse] and the Kotlin's
 * `kt.models.LlmResponse` (both directions): the Kotlin agent loop consumes a Java model's response
 * ([JavaModelToKt]), and an Kt-backed Java model returns the Kotlin's response (the model adapter).
 *
 * Carries content, error message, streaming flags, model version, finish reason, usage metadata,
 * grounding metadata, average log-probs, error code, and custom metadata. Citation metadata and
 * logprobs results have no ADK Java facade field, so they are not mapped.
 */
internal object LlmResponseCodec {

  /** Returns the Kotlin [KtLlmResponse] view of the Java [response]. */
  fun fromJava(response: JavaLlmResponse): KtLlmResponse =
    KtLlmResponse(
      content = response.content().getOrNull()?.let { ContentCodec.fromJava(it) },
      errorMessage = response.errorMessage().getOrNull(),
      partial = response.partial().getOrNull() ?: false,
      interrupted = response.interrupted().getOrNull() ?: false,
      modelVersion = response.modelVersion().getOrNull(),
      finishReason =
        enumByNameOrNull<KtFinishReason> { response.finishReason().getOrNull()?.knownEnum()?.name },
      usageMetadata = response.usageMetadata().getOrNull()?.let { UsageMetadataCodec.fromJava(it) },
      groundingMetadata =
        response.groundingMetadata().getOrNull()?.let { GroundingMetadataCodec.fromJava(it) },
      avgLogprobs = response.avgLogprobs().getOrNull(),
      // toString() preserves the raw value; knownEnum().name would collapse an unrecognized code to
      // FINISH_REASON_UNSPECIFIED (errorCode is a free-form String on the Kotlin side).
      errorCode = response.errorCode().getOrNull()?.toString(),
      customMetadata =
        response.customMetadata().getOrNull()?.let { CustomMetadataCodec.toKotlin(it) },
    )

  /** Returns the Java [JavaLlmResponse] view of the Kotlin [response]. */
  fun toJava(response: KtLlmResponse): JavaLlmResponse {
    val builder =
      JavaLlmResponse.builder().partial(response.partial).interrupted(response.interrupted)
    response.content?.let { builder.content(ContentCodec.toJava(it)) }
    response.errorMessage?.let { builder.errorMessage(it) }
    response.modelVersion?.let { builder.modelVersion(it) }
    response.finishReason?.let { builder.finishReason(it.toGenai()) }
    response.usageMetadata?.let { builder.usageMetadata(UsageMetadataCodec.toJava(it)) }
    response.groundingMetadata?.let { builder.groundingMetadata(GroundingMetadataCodec.toJava(it)) }
    response.avgLogprobs?.let { builder.avgLogprobs(it) }
    response.errorCode?.let { builder.errorCode(GenaiFinishReason(it)) }
    response.customMetadata?.let { builder.customMetadata(CustomMetadataCodec.toJava(it)) }
    return builder.build()
  }
}
