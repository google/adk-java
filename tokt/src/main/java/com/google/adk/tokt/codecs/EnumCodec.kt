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

import com.google.adk.kt.types.FinishReason as KtFinishReason
import com.google.genai.types.FinishReason as GenaiFinishReason

/**
 * Maps a genai enum to the Kotlin enum of the same name. The genai and Kotlin enums share constant
 * names, so this bridges them by name. [name] is evaluated inside the guard, so an absent value, a
 * genai `knownEnum()` that throws on an unrecognized value, or a name with no Kotlin constant all
 * resolve to null.
 */
internal inline fun <reified K : Enum<K>> enumByNameOrNull(name: () -> String?): K? =
  runCatching { name()?.let { enumValueOf<K>(it) } }.getOrNull()

/** The Kotlin finish reason as a genai finish reason (matched by name). */
internal fun KtFinishReason.toGenai(): GenaiFinishReason = GenaiFinishReason(name)
