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

import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.events.Event as KtEvent
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.kt.ids.Uuid
import com.google.adk.kt.types.FinishReason as KtFinishReason
import com.google.genai.types.FinishReason as GenaiFinishReason
import kotlin.jvm.optionals.getOrNull

/**
 * Converts an event between the ADK Kotlin [KtEvent] and the ADK Java facade [JavaEvent] that a
 * Java `Runner` and the session service exchange.
 *
 * Carries the identity (`id`, `invocationId`, `author`, `branch`), the [content][ContentCodec], the
 * [actions][KtEventActions] (state/artifact deltas, the control-flow flags - transfer, escalate,
 * skipSummarization, endOfAgent - the requested tool confirmations, and the context-compaction
 * summary), the streaming/error signals (`partial`, `turnComplete`, `interrupted`, `errorMessage`,
 * `errorCode`, `finishReason`), the token `usageMetadata` ([UsageMetadataCodec]) and
 * `groundingMetadata` ([GroundingMetadataCodec]), long-running tool ids, and the timestamp.
 */
internal object EventCodec {

  /** Returns the Java [JavaEvent] view of the Kotlin [event]. */
  fun toJava(event: KtEvent): JavaEvent {
    val builder =
      JavaEvent.builder()
        .id(event.id)
        .author(event.author)
        .actions(eventActionsToJava(event.actions))
    event.invocationId?.let { builder.invocationId(it) }
    event.branch?.let { builder.branch(it) }
    event.content?.let { builder.content(ContentCodec.toJava(it)) }
    if (event.longRunningToolIds.isNotEmpty()) builder.longRunningToolIds(event.longRunningToolIds)
    // Streaming/error signals are set only when meaningful, so ordinary events keep the Java
    // Optional.empty() defaults.
    if (event.partial) builder.partial(true)
    if (event.turnComplete) builder.turnComplete(true)
    if (event.interrupted) builder.interrupted(true)
    event.errorMessage?.let { builder.errorMessage(it) }
    event.errorCode?.let { builder.errorCode(GenaiFinishReason(it)) }
    event.finishReason?.let { builder.finishReason(it.toGenai()) }
    event.usageMetadata?.let { builder.usageMetadata(UsageMetadataCodec.toJava(it)) }
    event.avgLogProbs?.let { builder.avgLogprobs(it) }
    event.groundingMetadata?.let { builder.groundingMetadata(GroundingMetadataCodec.toJava(it)) }
    event.modelVersion?.let { builder.modelVersion(it) }
    event.customMetadata?.let { builder.customMetadata(CustomMetadataCodec.toJava(it)) }
    builder.timestamp(event.timestamp)
    return builder.build()
  }

  /** Returns the Kotlin [KtEvent] view of the Java [event]. */
  fun fromJava(event: JavaEvent): KtEvent {
    val javaActions = event.actions()
    return KtEvent(
      id = event.id() ?: Uuid.random(),
      invocationId = event.invocationId(),
      author = event.author() ?: "",
      content = event.content().getOrNull()?.let { ContentCodec.fromJava(it) },
      longRunningToolIds = event.longRunningToolIds().getOrNull() ?: emptySet(),
      actions =
        KtEventActions(
          stateDelta = stateDeltaFromJava(javaActions?.stateDelta()),
          artifactDelta = (javaActions?.artifactDelta() ?: emptyMap()).toMutableMap(),
          transferToAgent = javaActions?.transferToAgent()?.getOrNull(),
          escalate = javaActions?.escalate()?.orElse(false) ?: false,
          skipSummarization = javaActions?.skipSummarization()?.orElse(false) ?: false,
          endOfAgent = javaActions?.endOfAgent() ?: false,
          requestedToolConfirmations =
            (javaActions?.requestedToolConfirmations() ?: emptyMap())
              .mapValues { ToolConfirmationCodec.toKotlin(it.value) }
              .toMutableMap(),
          compaction =
            javaActions?.compaction()?.getOrNull()?.let { EventCompactionCodec.fromJava(it) },
        ),
      branch = event.branch().getOrNull(),
      partial = event.partial().getOrNull() ?: false,
      turnComplete = event.turnComplete().getOrNull() ?: false,
      interrupted = event.interrupted().getOrNull() ?: false,
      errorMessage = event.errorMessage().getOrNull(),
      // toString() preserves the raw value; knownEnum().name would collapse an unrecognized code to
      // FINISH_REASON_UNSPECIFIED (errorCode is a free-form String on the Kotlin side).
      errorCode = event.errorCode().getOrNull()?.toString(),
      finishReason =
        enumByNameOrNull<KtFinishReason> { event.finishReason().getOrNull()?.knownEnum()?.name },
      usageMetadata = event.usageMetadata().getOrNull()?.let { UsageMetadataCodec.fromJava(it) },
      avgLogProbs = event.avgLogprobs().getOrNull(),
      groundingMetadata =
        event.groundingMetadata().getOrNull()?.let { GroundingMetadataCodec.fromJava(it) },
      modelVersion = event.modelVersion().getOrNull(),
      customMetadata = event.customMetadata().getOrNull()?.let { CustomMetadataCodec.toKotlin(it) },
      timestamp = event.timestamp(),
    )
  }
}
