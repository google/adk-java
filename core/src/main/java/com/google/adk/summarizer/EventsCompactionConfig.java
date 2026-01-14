/*
 * Copyright 2025 Google LLC
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

package com.google.adk.summarizer;

/**
 * Configuration for event compaction.
 *
 * @param compactionInterval The number of <b>new</b> user-initiated invocations that, once fully
 *     represented in the session's events, will trigger a compaction.
 * @param overlapSize The number of preceding invocations to include from the end of the last
 *     compacted range. This creates an overlap between consecutive compacted summaries, maintaining
 *     context.
 * @param summarizer An event summarizer to use for compaction.
 */
public record EventsCompactionConfig(
    int compactionInterval, int overlapSize, BaseEventSummarizer summarizer) {}
