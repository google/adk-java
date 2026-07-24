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

import com.google.adk.events.EventActions as JavaEventActions
import com.google.adk.events.EventCompaction as JavaEventCompaction
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.kt.sessions.Session as KtSession
import com.google.adk.kt.sessions.State as KtState
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.sessions.State as JavaState
import java.util.Optional
import kotlin.time.toJavaInstant

/**
 * Kt -> Java value conversions (session, instants, and the live event-actions view). These are
 * plain value/view conversions shared by both the codecs and the direction-specific adapters, so
 * they live in `codecs` rather than `context`.
 */

/**
 * A real Java [JavaEventActions] that delegates live to a Kotlin [KtEventActions]: the delta maps
 * and most control-flow signals read and write straight through, so a Java tool mutating
 * `actions()` (e.g. `actions().setEscalate(true)`) takes effect on the Kotlin immediately. Escalate
 * returns `Optional.of(false)` when unset (the Kotlin field defaults to false). Skip-summarization
 * is left to the base fields (its Java setter has both a boxed and a primitive overload that can't
 * both be overridden cleanly) and is reconciled onto the Kotlin by [JavaToolToKt] after the tool
 * runs. A tool that instead *replaces* its actions (`setActions(...)`) is likewise reconciled by
 * [JavaToolToKt]. For a read-only snapshot, use [eventActionsToJava] instead.
 */
internal class KtEventActionsToJavaView(private val actions: KtEventActions) : JavaEventActions() {
  override fun stateDelta(): MutableMap<String, Any> = actions.stateDelta

  override fun artifactDelta(): MutableMap<String, Int> = actions.artifactDelta

  override fun transferToAgent(): Optional<String> = Optional.ofNullable(actions.transferToAgent)

  override fun setTransferToAgent(transferToAgent: String?) {
    actions.transferToAgent = transferToAgent
  }

  override fun escalate(): Optional<Boolean> = Optional.of(actions.escalate)

  override fun setEscalate(escalate: Boolean?) {
    actions.escalate = escalate ?: false
  }

  override fun endOfAgent(): Boolean = actions.endOfAgent

  override fun setEndOfAgent(endOfAgent: Boolean) {
    actions.endOfAgent = endOfAgent
  }

  override fun compaction(): Optional<JavaEventCompaction> =
    Optional.ofNullable(actions.compaction?.let { EventCompactionCodec.toJava(it) })
}

/**
 * Builds a read-only Java [JavaEventActions] snapshot from a Kotlin [KtEventActions], carrying the
 * deltas, the control-flow signals (skip-summarization, transfer, escalate, end-of-agent), and the
 * requested tool confirmations. Used when exposing an existing Kotlin event to a Java runner
 * ([EventCodec.toJava]); unlike [KtEventActionsToJavaView] (a live write sink) nothing writes back
 * through it, so every field is populated.
 */
internal fun eventActionsToJava(actions: KtEventActions): JavaEventActions =
  JavaEventActions.builder()
    .skipSummarization(actions.skipSummarization)
    .stateDelta(stateDeltaToJava(actions.stateDelta))
    .artifactDelta(actions.artifactDelta)
    .transferToAgent(actions.transferToAgent)
    .escalate(actions.escalate)
    .requestedToolConfirmations(
      actions.requestedToolConfirmations.mapValues { ToolConfirmationCodec.toJava(it.value) }
    )
    .endOfAgent(actions.endOfAgent)
    .compaction(actions.compaction?.let { EventCompactionCodec.toJava(it) })
    .build()

/** Copies a Kotlin state delta to Java, mapping the Kotlin [KtState.REMOVED] deletion sentinel. */
private fun stateDeltaToJava(delta: Map<String, Any>): Map<String, Any> =
  delta.mapValues { (_, value) ->
    if (value === KtState.REMOVED) JavaState.REMOVED else value
  }

/**
 * Converts a Kotlin [KtSession] to a Java [JavaSession] snapshot. The state and events are copied,
 * not shared: `JavaState` copies the Kotlin state map (it is not a `ConcurrentMap`) and events are
 * converted via [EventCodec]. Because the view is re-projected on every `context.session()` call it
 * still reflects the current Kotlin session on each read; state *writes* from Java propagate via
 * the live `eventActions.stateDelta` (see [KtEventActionsToJavaView]), not through this copied map.
 */
internal fun ktSessionToJava(session: KtSession): JavaSession =
  JavaSession.builder(session.key.id ?: "")
    .appName(session.key.appName)
    .userId(session.key.userId)
    .state(JavaState(session.state))
    .events(session.events.map { EventCodec.toJava(it) })
    .lastUpdateTime(session.lastUpdateTime.toJavaInstant())
    .build()
