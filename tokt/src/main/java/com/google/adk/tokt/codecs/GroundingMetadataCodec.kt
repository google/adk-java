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

import com.google.adk.kt.types.GroundingChunk as KtGroundingChunk
import com.google.adk.kt.types.GroundingChunkRetrievedContext as KtGroundingChunkRetrievedContext
import com.google.adk.kt.types.GroundingChunkWeb as KtGroundingChunkWeb
import com.google.adk.kt.types.GroundingMetadata as KtGroundingMetadata
import com.google.adk.kt.types.GroundingSupport as KtGroundingSupport
import com.google.adk.kt.types.RetrievalMetadata as KtRetrievalMetadata
import com.google.adk.kt.types.SearchEntryPoint as KtSearchEntryPoint
import com.google.adk.kt.types.Segment as KtSegment
import com.google.genai.types.GroundingChunk as GenaiGroundingChunk
import com.google.genai.types.GroundingChunkRetrievedContext as GenaiGroundingChunkRetrievedContext
import com.google.genai.types.GroundingChunkWeb as GenaiGroundingChunkWeb
import com.google.genai.types.GroundingMetadata as GenaiGroundingMetadata
import com.google.genai.types.GroundingSupport as GenaiGroundingSupport
import com.google.genai.types.RetrievalMetadata as GenaiRetrievalMetadata
import com.google.genai.types.SearchEntryPoint as GenaiSearchEntryPoint
import com.google.genai.types.Segment as GenaiSegment
import kotlin.jvm.optionals.getOrNull

/**
 * Converts grounding metadata between the genai [GroundingMetadata][GenaiGroundingMetadata] the ADK
 * Java facade exposes and the Kotlin's `kt.types.GroundingMetadata`, in both directions: image/web
 * search queries, grounding chunks (web / retrieved context), grounding supports (segment, chunk
 * indices, confidence scores), the search entry point, and retrieval metadata. genai-only fields
 * the Kotlin type does not model are dropped. Shared by [EventCodec] and [LlmResponseCodec].
 */
internal object GroundingMetadataCodec {

  /** Returns the Kotlin [KtGroundingMetadata] view of the genai [metadata]. */
  fun fromJava(metadata: GenaiGroundingMetadata): KtGroundingMetadata =
    KtGroundingMetadata(
      imageSearchQueries = metadata.imageSearchQueries().getOrNull().orEmpty(),
      groundingChunks = metadata.groundingChunks().getOrNull()?.map { chunkFromJava(it) },
      groundingSupports = metadata.groundingSupports().getOrNull()?.map { supportFromJava(it) },
      webSearchQueries = metadata.webSearchQueries().getOrNull(),
      searchEntryPoint =
        metadata.searchEntryPoint().getOrNull()?.let { searchEntryPointFromJava(it) },
      retrievalMetadata =
        metadata.retrievalMetadata().getOrNull()?.let { retrievalMetadataFromJava(it) },
    )

  /** Returns the genai [GenaiGroundingMetadata] view of the Kotlin [metadata]. */
  fun toJava(metadata: KtGroundingMetadata): GenaiGroundingMetadata {
    val builder = GenaiGroundingMetadata.builder().imageSearchQueries(metadata.imageSearchQueries)
    metadata.groundingChunks?.let { builder.groundingChunks(it.map { c -> chunkToJava(c) }) }
    metadata.groundingSupports?.let { builder.groundingSupports(it.map { s -> supportToJava(s) }) }
    metadata.webSearchQueries?.let { builder.webSearchQueries(it) }
    metadata.searchEntryPoint?.let { builder.searchEntryPoint(searchEntryPointToJava(it)) }
    metadata.retrievalMetadata?.let { builder.retrievalMetadata(retrievalMetadataToJava(it)) }
    return builder.build()
  }

  private fun chunkFromJava(chunk: GenaiGroundingChunk): KtGroundingChunk =
    KtGroundingChunk(
      web = chunk.web().getOrNull()?.let { webFromJava(it) },
      retrievedContext = chunk.retrievedContext().getOrNull()?.let { retrievedContextFromJava(it) },
    )

  private fun chunkToJava(chunk: KtGroundingChunk): GenaiGroundingChunk {
    val builder = GenaiGroundingChunk.builder()
    chunk.web?.let { builder.web(webToJava(it)) }
    chunk.retrievedContext?.let { builder.retrievedContext(retrievedContextToJava(it)) }
    return builder.build()
  }

  private fun webFromJava(web: GenaiGroundingChunkWeb): KtGroundingChunkWeb =
    KtGroundingChunkWeb(
      uri = web.uri().getOrNull(),
      title = web.title().getOrNull(),
      domain = web.domain().getOrNull(),
    )

  private fun webToJava(web: KtGroundingChunkWeb): GenaiGroundingChunkWeb {
    val builder = GenaiGroundingChunkWeb.builder()
    web.uri?.let { builder.uri(it) }
    web.title?.let { builder.title(it) }
    web.domain?.let { builder.domain(it) }
    return builder.build()
  }

  private fun retrievedContextFromJava(
    context: GenaiGroundingChunkRetrievedContext
  ): KtGroundingChunkRetrievedContext =
    KtGroundingChunkRetrievedContext(
      uri = context.uri().getOrNull(),
      title = context.title().getOrNull(),
      text = context.text().getOrNull(),
    )

  private fun retrievedContextToJava(
    context: KtGroundingChunkRetrievedContext
  ): GenaiGroundingChunkRetrievedContext {
    val builder = GenaiGroundingChunkRetrievedContext.builder()
    context.uri?.let { builder.uri(it) }
    context.title?.let { builder.title(it) }
    context.text?.let { builder.text(it) }
    return builder.build()
  }

  private fun supportFromJava(support: GenaiGroundingSupport): KtGroundingSupport =
    KtGroundingSupport(
      segment = support.segment().getOrNull()?.let { segmentFromJava(it) },
      groundingChunkIndices = support.groundingChunkIndices().getOrNull(),
      confidenceScores = support.confidenceScores().getOrNull(),
    )

  private fun supportToJava(support: KtGroundingSupport): GenaiGroundingSupport {
    val builder = GenaiGroundingSupport.builder()
    support.segment?.let { builder.segment(segmentToJava(it)) }
    support.groundingChunkIndices?.let { builder.groundingChunkIndices(it) }
    support.confidenceScores?.let { builder.confidenceScores(it) }
    return builder.build()
  }

  private fun segmentFromJava(segment: GenaiSegment): KtSegment =
    KtSegment(
      startIndex = segment.startIndex().getOrNull(),
      endIndex = segment.endIndex().getOrNull(),
      partIndex = segment.partIndex().getOrNull(),
      text = segment.text().getOrNull(),
    )

  private fun segmentToJava(segment: KtSegment): GenaiSegment {
    val builder = GenaiSegment.builder()
    segment.startIndex?.let { builder.startIndex(it) }
    segment.endIndex?.let { builder.endIndex(it) }
    segment.partIndex?.let { builder.partIndex(it) }
    segment.text?.let { builder.text(it) }
    return builder.build()
  }

  private fun searchEntryPointFromJava(point: GenaiSearchEntryPoint): KtSearchEntryPoint =
    KtSearchEntryPoint(renderedContent = point.renderedContent().getOrNull())

  private fun searchEntryPointToJava(point: KtSearchEntryPoint): GenaiSearchEntryPoint {
    val builder = GenaiSearchEntryPoint.builder()
    point.renderedContent?.let { builder.renderedContent(it) }
    return builder.build()
  }

  private fun retrievalMetadataFromJava(metadata: GenaiRetrievalMetadata): KtRetrievalMetadata =
    KtRetrievalMetadata(
      googleSearchDynamicRetrievalScore = metadata.googleSearchDynamicRetrievalScore().getOrNull()
    )

  private fun retrievalMetadataToJava(metadata: KtRetrievalMetadata): GenaiRetrievalMetadata {
    val builder = GenaiRetrievalMetadata.builder()
    metadata.googleSearchDynamicRetrievalScore?.let {
      builder.googleSearchDynamicRetrievalScore(it)
    }
    return builder.build()
  }
}
