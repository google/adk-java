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

import com.google.genai.types.CustomMetadata as GenaiCustomMetadata
import com.google.genai.types.StringList as GenaiStringList
import kotlin.jvm.optionals.getOrNull

/**
 * Bridges the Kotlin's `customMetadata` (a `Map<String, Any?>` on the Kotlin `LlmResponse` and
 * `Event`) and the ADK Java facade's `List<CustomMetadata>` (each a key plus one typed value).
 * Shared by [LlmResponseCodec] and [EventCodec].
 *
 * A genai [CustomMetadata][GenaiCustomMetadata] can only hold a string, a float, or a list of
 * strings, so a Kotlin value outside those (e.g. a boolean or nested object) is stored via its
 * `toString()` and a non-`Float` number is narrowed to `Float`; entries without a key are dropped.
 */
internal object CustomMetadataCodec {

  /** Returns the Java `List<CustomMetadata>` view of the Kotlin [metadata] map. */
  fun toJava(metadata: Map<String, Any?>): List<GenaiCustomMetadata> =
    metadata.map { (key, value) ->
      val builder = GenaiCustomMetadata.builder().key(key)
      when (value) {
        is String -> builder.stringValue(value)
        is Number -> builder.numericValue(value.toFloat())
        is Collection<*> ->
          builder.stringListValue(
            GenaiStringList.builder().values(value.map { it.toString() }).build()
          )
        null -> {}
        else -> builder.stringValue(value.toString())
      }
      builder.build()
    }

  /** Returns the Kotlin map view of the Java [list], keyed by each entry's key. */
  fun toKotlin(list: List<GenaiCustomMetadata>): Map<String, Any> = buildMap {
    for (entry in list) {
      val key = entry.key().getOrNull() ?: continue
      val value: Any? =
        entry.stringValue().getOrNull()
          ?: entry.numericValue().getOrNull()
          ?: entry.stringListValue().getOrNull()?.values()?.getOrNull()
      if (value != null) put(key, value)
    }
  }
}
