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

import com.google.adk.kt.memory.MemoryEntry as KtMemoryEntry
import com.google.adk.kt.memory.SearchMemoryResponse as KtSearchMemoryResponse
import com.google.adk.memory.MemoryEntry as JavaMemoryEntry
import com.google.adk.memory.SearchMemoryResponse as JavaSearchMemoryResponse

/**
 * Converts the ADK Kotlin's memory search results ([KtMemoryEntry], [KtSearchMemoryResponse]) to
 * the ADK Java facade types.
 */
internal object MemoryEntryCodec {

  /** Returns the Java [JavaMemoryEntry] view of the Kotlin [entry]. */
  fun toJava(entry: KtMemoryEntry): JavaMemoryEntry =
    JavaMemoryEntry.builder()
      .content(ContentCodec.toJava(entry.content))
      .author(entry.author)
      .timestamp(entry.timestamp)
      .build()

  /** Returns the Java [JavaSearchMemoryResponse] view of the Kotlin [response]. */
  fun toJava(response: KtSearchMemoryResponse): JavaSearchMemoryResponse =
    JavaSearchMemoryResponse.builder().memories(response.memories.map { toJava(it) }).build()

  /** Returns the Kotlin [KtMemoryEntry] view of the Java [entry]. */
  fun toKotlin(entry: JavaMemoryEntry): KtMemoryEntry =
    KtMemoryEntry(
      content = ContentCodec.fromJava(entry.content()),
      author = entry.author(),
      timestamp = entry.timestamp(),
    )

  /** Returns the Kotlin [KtSearchMemoryResponse] view of the Java [response]. */
  fun toKotlin(response: JavaSearchMemoryResponse): KtSearchMemoryResponse =
    KtSearchMemoryResponse(memories = response.memories().map { toKotlin(it) })
}
