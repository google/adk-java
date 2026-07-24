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

import com.google.adk.kt.types.Content as KtContent
import com.google.genai.types.Content as GenaiContent
import kotlin.jvm.optionals.getOrNull

/**
 * Converts [Content][KtContent] between the ADK Kotlin and the genai `Content` that the ADK Java
 * facade exposes on events and requests. The role is carried here; each part is converted via
 * [PartCodec].
 */
internal object ContentCodec {

  /** Returns the genai [GenaiContent] view of the Kotlin [content]. */
  fun toJava(content: KtContent): GenaiContent {
    val parts = content.parts.mapNotNull { PartCodec.toJava(it) }
    val builder = GenaiContent.builder()
    content.role?.let { builder.role(it) }
    builder.parts(parts)
    return builder.build()
  }

  /** Returns the Kotlin [KtContent] view of the genai [content]. */
  fun fromJava(content: GenaiContent): KtContent {
    val parts = content.parts().getOrNull().orEmpty().mapNotNull { PartCodec.toKotlin(it) }
    return KtContent(role = content.role().getOrNull(), parts = parts)
  }
}
