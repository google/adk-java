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

import com.google.adk.kt.models.CacheMetadata as KtCacheMetadata
import com.google.adk.models.CacheMetadata as JavaCacheMetadata
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

/**
 * Converts context cache metadata between ADK Kotlin and ADK Java. Cache times are epoch
 * milliseconds on the Kotlin side and [Instant]s on the Java side. Shared by [EventCodec] and
 * [LlmResponseCodec].
 */
internal object CacheMetadataCodec {

  /** Returns the Java [JavaCacheMetadata] view of the Kotlin [metadata]. */
  fun toJava(metadata: KtCacheMetadata): JavaCacheMetadata =
    JavaCacheMetadata.builder()
      .fingerprint(metadata.fingerprint)
      .contentsCount(metadata.contentsCount)
      .cacheName(metadata.cacheName)
      .expireTime(metadata.expireTime?.let { Instant.ofEpochMilli(it) })
      .invocationsUsed(metadata.invocationsUsed)
      .createdAt(metadata.createdAt?.let { Instant.ofEpochMilli(it) })
      .build()

  /** Returns the Kotlin [KtCacheMetadata] view of the Java [metadata]. */
  fun fromJava(metadata: JavaCacheMetadata): KtCacheMetadata =
    KtCacheMetadata(
      fingerprint = metadata.fingerprint(),
      contentsCount = metadata.contentsCount(),
      cacheName = metadata.cacheName().getOrNull(),
      expireTime = metadata.expireTime().getOrNull()?.toEpochMilli(),
      invocationsUsed = metadata.invocationsUsed().getOrNull(),
      createdAt = metadata.createdAt().getOrNull()?.toEpochMilli(),
    )
}
