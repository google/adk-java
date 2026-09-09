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

import com.google.adk.kt.agents.TypedData

/**
 * Translates a Kotlin [TypedData] agent-state tree to and from the ADK Java facade's plain
 * `Map<String, Any?>` (`EventActions.agentState`), so a resumable run's state survives a round trip
 * through a Java session service. Each leaf maps to its plain Java counterpart to keep the
 * int/long/double distinction; a type the engine's [TypedData] cannot represent throws rather than
 * being coerced.
 */

/** Converts a Kotlin [TypedData] tree to its plain Java value. */
internal fun typedDataToJava(data: TypedData): Any? =
  when (data) {
    is TypedData.NullValue -> null
    is TypedData.IntValue -> data.value
    is TypedData.LongValue -> data.value
    is TypedData.DoubleValue -> data.value
    is TypedData.BooleanValue -> data.value
    is TypedData.StringValue -> data.value
    is TypedData.ListValue -> data.elements.map { typedDataToJava(it) }
    is TypedData.MapValue -> data.fields.mapValues { typedDataToJava(it.value) }
  }

/**
 * Converts a plain Java agent-state value back to a Kotlin [TypedData], preserving the
 * int/long/double distinction. Throws on a type [TypedData] cannot represent rather than coercing
 * or dropping it.
 */
internal fun typedDataFromJava(value: Any?): TypedData =
  when (value) {
    null -> TypedData.NullValue
    is Int -> TypedData.IntValue(value)
    is Long -> TypedData.LongValue(value)
    is Double -> TypedData.DoubleValue(value)
    is Boolean -> TypedData.BooleanValue(value)
    is String -> TypedData.StringValue(value)
    is List<*> -> TypedData.ListValue(value.map { typedDataFromJava(it) })
    is Map<*, *> ->
      TypedData.MapValue(value.entries.associate { (k, v) -> k.toString() to typedDataFromJava(v) })
    else ->
      throw IllegalArgumentException(
        "agentState value of type ${value.javaClass.name} cannot be represented as TypedData"
      )
  }

/**
 * The Java `EventActions.agentState` map view of the Kotlin [state]. The engine's agent state is a
 * [TypedData.MapValue]; a non-map top level has no Java `Map` representation and throws.
 */
internal fun agentStateToJava(state: TypedData?): Map<String, Any?>? =
  when (state) {
    null -> null
    is TypedData.MapValue -> state.fields.mapValues { typedDataToJava(it.value) }
    else ->
      throw IllegalArgumentException(
        "EventActions.agentState must be a TypedData.MapValue to cross to ADK Java, but was " +
          state::class.simpleName
      )
  }

/** The Kotlin [TypedData] view of the Java `EventActions.agentState` [state] map. */
internal fun agentStateFromJava(state: Map<String, Any?>?): TypedData? = state?.let { map ->
  TypedData.MapValue(map.mapValues { typedDataFromJava(it.value) })
}
