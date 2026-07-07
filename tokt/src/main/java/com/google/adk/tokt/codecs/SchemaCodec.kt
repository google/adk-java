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

import com.google.adk.kt.types.Schema as KtSchema
import com.google.adk.kt.types.Type as KtType
import com.google.genai.types.Schema as GenaiSchema
import kotlin.jvm.optionals.getOrNull

/**
 * Converts an OpenAPI [Schema][KtSchema] between the genai type the ADK Java facade exposes and the
 * Kotlin's `kt.types.Schema`, in both directions.
 *
 * The (recursive) schema is carried with its type, properties, items, required, description, and
 * enum; the OpenAPI `Type` is mapped by name (the genai and Kotlin enums share the same names).
 * Schema facets outside that subset (format, nullable, ...) are not carried yet.
 */
internal object SchemaCodec {

  /** Returns the Kotlin [KtSchema] view of the genai [schema]. */
  fun fromJava(schema: GenaiSchema): KtSchema =
    KtSchema(
      type = enumByNameOrNull<KtType> { schema.type().getOrNull()?.knownEnum()?.name },
      properties = schema.properties().getOrNull()?.mapValues { fromJava(it.value) },
      items = schema.items().getOrNull()?.let { fromJava(it) },
      required = schema.required().getOrNull(),
      description = schema.description().getOrNull(),
      enum = schema.enum_().getOrNull(),
    )

  /** Returns the genai [GenaiSchema] view of the Kotlin [schema]. */
  fun toJava(schema: KtSchema): GenaiSchema {
    val builder = GenaiSchema.builder()
    schema.type?.let { builder.type(it.name) }
    schema.properties?.let { props -> builder.properties(props.mapValues { toJava(it.value) }) }
    schema.items?.let { builder.items(toJava(it)) }
    schema.required?.let { builder.required(it) }
    schema.description?.let { builder.description(it) }
    schema.enum?.let { builder.enum_(it) }
    return builder.build()
  }
}
