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

import com.google.adk.events.EventCompaction as JavaEventCompaction
import com.google.adk.kt.events.EventCompaction as KtEventCompaction

/**
 * Converts the context-compaction summary an event carries on its `actions.compaction` between the
 * Kotlin [KtEventCompaction] and the Java [JavaEventCompaction], so a compaction summary survives
 * the session round-trip (the summary text lives here, not in the event content).
 */
internal object EventCompactionCodec {

  fun toJava(compaction: KtEventCompaction): JavaEventCompaction =
    JavaEventCompaction.builder()
      .startTimestamp(compaction.startTimestamp)
      .endTimestamp(compaction.endTimestamp)
      .compactedContent(ContentCodec.toJava(compaction.compactedContent))
      .build()

  fun fromJava(compaction: JavaEventCompaction): KtEventCompaction =
    KtEventCompaction(
      startTimestamp = compaction.startTimestamp(),
      endTimestamp = compaction.endTimestamp(),
      compactedContent = ContentCodec.fromJava(compaction.compactedContent()),
    )
}
