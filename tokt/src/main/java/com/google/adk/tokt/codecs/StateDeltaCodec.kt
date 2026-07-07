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

import com.google.adk.kt.sessions.State as KtState
import com.google.adk.sessions.State as JavaState

/**
 * Translates the removed-entry sentinel between the ADK Java and Kotlin `State`. The two frameworks
 * use distinct singleton sentinels ([JavaState.REMOVED] / [KtState.REMOVED]) and the Kotlin engine
 * matches deletions by identity, so a Java sentinel left in a Kotlin delta would be persisted as a
 * value instead of removing the key.
 */

/**
 * Maps a Java state value to Kotlin, converting the removal sentinel; other values pass through.
 */
internal fun stateValueFromJava(value: Any): Any =
  if (value === JavaState.REMOVED) KtState.REMOVED else value

/** Copies a Java state delta to a new Kotlin delta, translating the removal sentinel. */
internal fun stateDeltaFromJava(delta: Map<String, Any>?): MutableMap<String, Any> {
  val result = mutableMapOf<String, Any>()
  delta?.forEach { (key, value) -> result[key] = stateValueFromJava(value) }
  return result
}

/**
 * Translates any Java removal sentinel to the Kotlin one in a live Kotlin [delta], in place. A Java
 * tool / plugin removing a key through the live actions view writes the Java sentinel straight into
 * the Kotlin delta map (both are backed by the same concurrent map); this reconciles it so the
 * engine recognizes the deletion.
 */
internal fun reconcileRemovedSentinels(delta: MutableMap<String, Any>) {
  // Snapshot the keys to update so we don't mutate the map while iterating it.
  for (key in delta.filterValues { it === JavaState.REMOVED }.keys) {
    delta[key] = KtState.REMOVED
  }
}
