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
 * Kotlin's `kt.types.Schema`, in both directions. The (recursive) schema carries every facet the
 * Kotlin type models - type, properties, items, required, description, enum, format, nullable,
 * default, anyOf, title, pattern, and the numeric/length/item/property bounds - so structured
 * output and tool parameter constraints survive the interop; the OpenAPI `Type` is mapped by name
 * (the genai and Kotlin enums share the same names).
 */
internal object SchemaCodec {

  /** Returns the Kotlin [KtSchema] view of the genai [schema]. */
  fun fromJava(schema: GenaiSchema): KtSchema =
    KtSchema(
      type = enumByNameOrNull<KtType>(schema.type().getOrNull()?.knownEnum()?.name),
      properties = schema.properties().getOrNull()?.mapValues { fromJava(it.value) },
      items = schema.items().getOrNull()?.let { fromJava(it) },
      required = schema.required().getOrNull(),
      description = schema.description().getOrNull(),
      enum = schema.enum_().getOrNull(),
      format = schema.format().getOrNull(),
      nullable = schema.nullable().getOrNull(),
      default = schema.default_().getOrNull(),
      anyOf = schema.anyOf().getOrNull()?.map { fromJava(it) },
      title = schema.title().getOrNull(),
      pattern = schema.pattern().getOrNull(),
      minimum = schema.minimum().getOrNull(),
      maximum = schema.maximum().getOrNull(),
      minLength = schema.minLength().getOrNull(),
      maxLength = schema.maxLength().getOrNull(),
      minItems = schema.minItems().getOrNull(),
      maxItems = schema.maxItems().getOrNull(),
      minProperties = schema.minProperties().getOrNull(),
      maxProperties = schema.maxProperties().getOrNull(),
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
    schema.format?.let { builder.format(it) }
    schema.nullable?.let { builder.nullable(it) }
    schema.default?.let { builder.default_(it) }
    schema.anyOf?.let { subschemas -> builder.anyOf(subschemas.map { toJava(it) }) }
    schema.title?.let { builder.title(it) }
    schema.pattern?.let { builder.pattern(it) }
    schema.minimum?.let { builder.minimum(it) }
    schema.maximum?.let { builder.maximum(it) }
    schema.minLength?.let { builder.minLength(it) }
    schema.maxLength?.let { builder.maxLength(it) }
    schema.minItems?.let { builder.minItems(it) }
    schema.maxItems?.let { builder.maxItems(it) }
    schema.minProperties?.let { builder.minProperties(it) }
    schema.maxProperties?.let { builder.maxProperties(it) }
    return builder.build()
  }
}
