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
import com.google.adk.events.EventActions as JavaEventActions
import com.google.adk.events.EventCompaction as JavaEventCompaction
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.kt.sessions.Session as KtSession
import com.google.adk.kt.sessions.SessionKey as KtSessionKey
import com.google.adk.kt.sessions.State as KtState
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.sessions.State as JavaState
import java.util.AbstractMap
import java.util.Optional
import java.util.concurrent.ConcurrentMap
import kotlin.time.toJavaInstant

/**
 * Kt -> Java value conversions (session, instants, and the live event-actions view). These are
 * plain value/view conversions shared by both the codecs and the direction-specific adapters, so
 * they live in `codecs` rather than `context`.
 */

/**
 * A real Java [JavaEventActions] that delegates live to a Kotlin [KtEventActions]: the delta maps
 * and most control-flow signals read and write straight through, so a Java tool mutating
 * `actions()` (e.g. `actions().setEscalate(true)`) takes effect on the Kotlin side immediately.
 * Escalate returns `Optional.of(false)` when unset (the Kotlin field defaults to false).
 * Skip-summarization is left to the base fields (its Java setter has both a boxed and a primitive
 * overload that can't both be overridden cleanly) and is reconciled onto the Kotlin side by
 * [JavaToolToKt][com.google.adk.tokt.adapters.JavaToolToKt] after the tool runs. A tool that
 * instead *replaces* its actions (`setActions(...)`) is likewise reconciled by
 * [JavaToolToKt][com.google.adk.tokt.adapters.JavaToolToKt]. For a read-only snapshot, use
 * [eventActionsToJava] instead.
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

  override fun setCompaction(compaction: JavaEventCompaction?) {
    actions.compaction = compaction?.let { EventCompactionCodec.fromJava(it) }
  }

  // The three inherited mutators below write private fields on the base class, which the overridden
  // getters do not read, so without these the write is silently lost.

  override fun removeStateByKey(key: String) {
    // The Kotlin sentinel directly: this writes into the Kotlin delta, which the engine reads.
    actions.stateDelta[key] = KtState.REMOVED
  }

  override fun setArtifactDelta(artifactDelta: Map<String, Int>) {
    // Refill rather than reassign: the Kotlin map is the live one the engine holds.
    actions.artifactDelta.keys.retainAll(artifactDelta.keys)
    actions.artifactDelta.putAll(artifactDelta)
  }
}

/**
 * Builds a read-only Java [JavaEventActions] snapshot from a Kotlin [KtEventActions], carrying the
 * deltas, the control-flow signals (skip-summarization, transfer, escalate, end-of-agent), and the
 * requested tool confirmations. Used when exposing an existing Kotlin event to a Java runner
 * ([EventCodec.toJava]); unlike [KtEventActionsToJavaView] (a live write sink) nothing writes back
 * through it.
 *
 * Does not carry `agentState` or `rewindBeforeInvocationId`: Java `EventActions` has no equivalent,
 * so neither survives a round trip through a Java session service. Both are engine state rather
 * than decoration - the first is how a resumable workflow rebuilds its position from history, the
 * second drives rewind - so see [com.google.adk.tokt.JavaAdkToKt] for what that costs.
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
 * The Java session id for [key]. A null id is rejected rather than substituted: an empty id would
 * silently address a session keyed on "" - matching the session and artifact services, which
 * already require it.
 */
internal fun sessionId(key: KtSessionKey): String =
  requireNotNull(key.id) { "SessionKey.id must not be null when crossing to ADK Java" }

/**
 * Converts a Kotlin [KtSession] to a Java [JavaSession] **snapshot**: state and events are copied,
 * not shared, so later Kotlin-side changes are invisible and writes do not propagate back. Used at
 * service boundaries (handing a session to a Java session/memory service), where a point-in-time
 * value is what the callee expects. Contexts handed to a running Java tool / plugin / agent use
 * [ktSessionToJavaLive] instead.
 */
internal fun ktSessionToJava(session: KtSession): JavaSession =
  JavaSession.builder(sessionId(session.key))
    .appName(session.key.appName)
    .userId(session.key.userId)
    .state(JavaState(session.state))
    .events(session.events.map { EventCodec.toJava(it) })
    .lastUpdateTime(session.lastUpdateTime.toJavaInstant())
    .build()

/**
 * A Java [ConcurrentMap] that reads and writes through live to a Kotlin [KtState]. A Java
 * [JavaState] keeps a `ConcurrentMap` backing by reference (it does not copy one), so wrapping this
 * in `JavaState(...)` gives a Java session a live state view over the Kotlin session - an adapted
 * Java flow then sees state a tool set earlier in the same turn.
 *
 * Departures from the `ConcurrentMap` contract, none of which ADK relies on: the collection views
 * are per-call snapshots of detached entries (`entry.setValue` does not write through);
 * [putIfAbsent] / [remove] `(key, value)` / [replace] are read-then-write rather than atomic, as
 * [KtState] exposes no compare-and-set (the default `compute`/`merge` inherit this); and [clear]
 * also drops the pending state delta.
 */
private class KtStateAsJavaConcurrentMap(private val state: KtState) : ConcurrentMap<String, Any> {
  override val size: Int
    get() = state.size

  override fun isEmpty(): Boolean = state.isEmpty()

  override fun containsKey(key: String): Boolean = state.containsKey(key)

  override fun containsValue(value: Any): Boolean = state.containsValue(value)

  override fun get(key: String): Any? = state[key]

  override val keys: MutableSet<String>
    get() = state.keys.toMutableSet()

  override val values: MutableCollection<Any>
    get() = state.values.toMutableList()

  override val entries: MutableSet<MutableMap.MutableEntry<String, Any>>
    get() = state.entries.mapTo(mutableSetOf()) { AbstractMap.SimpleEntry(it.key, it.value) }

  override fun put(key: String, value: Any): Any? = state.set(key, value)

  override fun remove(key: String): Any? = state.remove(key)

  override fun putAll(from: Map<out String, Any>) {
    state.putAll(from)
  }

  override fun clear() {
    state.clear()
  }

  override fun putIfAbsent(key: String, value: Any): Any? {
    val existing = state[key]
    if (existing == null) {
      state[key] = value
    }
    return existing
  }

  override fun remove(key: String, value: Any): Boolean {
    if (state[key] == value) {
      state.remove(key)
      return true
    }
    return false
  }

  override fun replace(key: String, oldValue: Any, newValue: Any): Boolean {
    if (state[key] == oldValue) {
      state[key] = newValue
      return true
    }
    return false
  }

  override fun replace(key: String, value: Any): Any? =
    if (state.containsKey(key)) state.set(key, value) else null
}

/**
 * Like [ktSessionToJava] but backs the returned session with LIVE views: `events()` and `state()`
 * convert on access rather than snapshotting. Used for the context/agent bridges, so an adapted
 * Java flow (or a Java tool/plugin) always reads a current session - including what the Kotlin
 * runner or an earlier step changed this turn - with no re-projection.
 *
 * `events()` is read-only, the Kotlin runner owning the list, so a Java append throws rather than
 * writing to a discarded copy. Each element re-converts on access, deliberately: a Kotlin event's
 * `actions` are mutable, so caching would reintroduce the staleness this view removes. Copy the
 * list once rather than indexing it in a loop. `lastUpdateTime` is captured here, not live.
 */
// eventsView is deprecated to warn application code off it; an interop adapter is exactly the
// caller it exists for.
@Suppress("DEPRECATION")
internal fun ktSessionToJavaLive(session: KtSession): JavaSession =
  JavaSession.builder(sessionId(session.key))
    .appName(session.key.appName)
    .userId(session.key.userId)
    .state(JavaState(KtStateAsJavaConcurrentMap(session.state)))
    .eventsView(KtBackedEventsView(session))
    .lastUpdateTime(session.lastUpdateTime.toJavaInstant())
    .build()

/**
 * The read-only, converting `events()` of [ktSessionToJavaLive]. Named rather than anonymous so a
 * caller holding a Java [JavaSession] can tell a live view from a snapshot: this one is already
 * backed by the Kotlin session, so it must not be mirrored into (and would throw if tried).
 */
internal class KtBackedEventsView(private val session: KtSession) : AbstractList<JavaEvent>() {
  override val size: Int
    get() = session.events.size

  override fun get(index: Int): JavaEvent = EventCodec.toJava(session.events[index])
}
