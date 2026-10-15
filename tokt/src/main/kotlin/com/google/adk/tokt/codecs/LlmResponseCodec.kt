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
import com.google.adk.models.LlmResponse as JavaLlmResponse
import com.google.genai.types.FinishReason as GenaiFinishReason
import kotlin.jvm.optionals.getOrNull

/**
 * Converts an LLM response between the ADK Java [JavaLlmResponse] and the Kotlin
 * `kt.models.LlmResponse` (both directions): the Kotlin agent loop consumes a Java model's response
 * ([JavaModelToKt][com.google.adk.tokt.adapters.JavaModelToKt]); Kotlin to Java hands a response to
 * a bridged Java plugin's `afterModel`.
 *
 * Carries content, error message, streaming flags, `turnComplete`, the live transcriptions, model
 * version, finish reason, usage metadata, grounding metadata, average log-probs, error code, and
 * custom metadata. Every other field, such as citation metadata and logprobs results, is dropped;
 * [reapplyJavaResponse] keeps them when a Java plugin returns a response.
 */
internal object LlmResponseCodec {

  /** Returns the Kotlin [KtLlmResponse] view of the Java [response]. */
  fun fromJava(response: JavaLlmResponse): KtLlmResponse =
    KtLlmResponse(
      content = response.content().getOrNull()?.let { ContentCodec.fromJava(it) },
      errorMessage = response.errorMessage().getOrNull(),
      partial = response.partial().getOrNull() ?: false,
      interrupted = response.interrupted().getOrNull() ?: false,
      turnComplete = response.turnComplete().getOrNull(),
      inputTranscription =
        response.inputTranscription().getOrNull()?.let { TranscriptionCodec.fromJava(it) },
      outputTranscription =
        response.outputTranscription().getOrNull()?.let { TranscriptionCodec.fromJava(it) },
      modelVersion = response.modelVersion().getOrNull(),
      finishReason = response.finishReason().getOrNull()?.toKt(),
      usageMetadata = response.usageMetadata().getOrNull()?.let { UsageMetadataCodec.fromJava(it) },
      groundingMetadata =
        response.groundingMetadata().getOrNull()?.let { GroundingMetadataCodec.fromJava(it) },
      avgLogprobs = response.avgLogprobs().getOrNull(),
      // toString() preserves the raw value; knownEnum().name would collapse an unrecognized code to
      // FINISH_REASON_UNSPECIFIED (errorCode is a free-form String on the Kotlin side).
      errorCode = response.errorCode().getOrNull()?.toString(),
      customMetadata =
        response.customMetadata().getOrNull()?.let { CustomMetadataCodec.fromJava(it) },
    )

  /** Returns the Java [JavaLlmResponse] view of the Kotlin [response]. */
  fun toJava(response: KtLlmResponse): JavaLlmResponse {
    val builder =
      JavaLlmResponse.builder().partial(response.partial).interrupted(response.interrupted)
    response.content?.let { builder.content(ContentCodec.toJava(it)) }
    response.turnComplete?.let { builder.turnComplete(it) }
    response.inputTranscription?.let { builder.inputTranscription(TranscriptionCodec.toJava(it)) }
    response.outputTranscription?.let { builder.outputTranscription(TranscriptionCodec.toJava(it)) }
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

/**
 * Re-applies the response a Java plugin returned onto the original Kotlin [response]: fields
 * [LlmResponseCodec] converts come from the result, and every other field keeps [response]'s value.
 * `partial`, `turnComplete` and `usageMetadata` keep [response]'s values when the result leaves
 * them unset, as ADK Python does. `interactionStatus` stays only while the turn is complete.
 */
internal fun reapplyJavaResponse(
  response: KtLlmResponse,
  javaResult: JavaLlmResponse,
): KtLlmResponse {
  val result = LlmResponseCodec.fromJava(javaResult)
  val turnComplete = result.turnComplete ?: response.turnComplete
  return result.copy(
    // fromJava reads an unset partial as false, so check the Java value.
    partial = javaResult.partial().getOrNull() ?: response.partial,
    turnComplete = turnComplete,
    usageMetadata = result.usageMetadata ?: response.usageMetadata,
    citationMetadata = response.citationMetadata,
    logprobsResult = response.logprobsResult,
    cacheMetadata = response.cacheMetadata,
    turnCompleteReason = response.turnCompleteReason,
    interactionStatus = response.interactionStatus.takeIf { turnComplete == true },
    liveSessionId = response.liveSessionId,
    liveSessionResumptionUpdate = response.liveSessionResumptionUpdate,
    goAway = response.goAway,
    voiceActivity = response.voiceActivity,
  )
}
