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

package com.google.adk.models;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.MediaModality;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One generation, carried across the requests that resume it after the model pauses it. */
final class GeminiContinuation {
  @VisibleForTesting static final int MAX_RESUMES = 256;

  private static final Logger logger = LoggerFactory.getLogger(GeminiContinuation.class);

  private final ImmutableList<Content> contents;
  private final @Nullable GenerateContentConfig config;
  private final List<Part> parts = new ArrayList<>();
  private @Nullable GenerateContentResponseUsageMetadata usage = null;
  private byte @Nullable [] token = null;
  private int resumes = 0;

  GeminiContinuation(List<Content> contents, @Nullable GenerateContentConfig config) {
    this.contents = ImmutableList.copyOf(contents);
    this.config = config;
  }

  /**
   * Returns the token that resumes {@code response}, or null if it did not pause or has no token.
   */
  static byte @Nullable [] resumeToken(GenerateContentResponse response) {
    Optional<Candidate> paused =
        response
            .candidates()
            .flatMap(candidates -> candidates.stream().findFirst())
            .filter(
                candidate ->
                    candidate
                        .finishReason()
                        .map(reason -> reason.knownEnum() == FinishReason.Known.CONTINUATION)
                        .orElse(false));
    if (paused.isEmpty()) {
      return null;
    }
    byte[] token = paused.get().continuationToken().filter(bytes -> bytes.length > 0).orElse(null);
    if (token == null) {
      logger.warn(
          "The model paused the generation for continuation, but the response carries no"
              + " continuation token, so the partial output is returned.");
    }
    return token;
  }

  /**
   * Records one request's output and returns the next request to resume generation. Returns empty
   * if generation finished, paused without a new token, or already resumed {@link #MAX_RESUMES}
   * times.
   */
  Optional<ResumeRequest> advance(
      byte @Nullable [] nextToken,
      List<Part> newParts,
      @Nullable GenerateContentResponseUsageMetadata newUsage) {
    usage = addUsage(usage, newUsage);
    if (nextToken == null && !resumed()) {
      return Optional.empty();
    }
    appendParts(newParts);
    if (nextToken == null) {
      return Optional.empty();
    }
    if (!willResume(nextToken)) {
      if (Arrays.equals(nextToken, token)) {
        // A token that did not change means the model made no progress.
        logger.warn(
            "The model returned the same continuation token twice; returning the output"
                + " generated so far.");
      } else {
        logger.warn(
            "The model paused the generation {} times; returning the output generated so far.",
            MAX_RESUMES);
      }
      return Optional.empty();
    }
    token = nextToken;
    resumes++;
    logger.info("The model paused the generation; resuming it.");
    return Optional.of(nextRequest(nextToken));
  }

  /** Returns {@code response} with the content and usage of all requests, if it was resumed. */
  LlmResponse complete(LlmResponse response) {
    if (!resumed()) {
      return response;
    }
    return response.toBuilder()
        .content(parts.isEmpty() ? response.content().orElse(null) : content())
        .usageMetadata(usage)
        .build();
  }

  /**
   * Returns the final streamed {@code response}, with usage summed across all requests if resumed.
   */
  LlmResponse withSummedUsage(LlmResponse response) {
    return resumed() ? response.toBuilder().usageMetadata(usage).build() : response;
  }

  /** Returns whether the generation took more than one request. */
  private boolean resumed() {
    return token != null;
  }

  /** Returns whether a request that ended with {@code nextToken} is resumed. */
  private boolean willResume(byte[] nextToken) {
    return !Arrays.equals(nextToken, token) && resumes < MAX_RESUMES;
  }

  private Content content() {
    return Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build();
  }

  private ResumeRequest nextRequest(byte[] nextToken) {
    ImmutableList.Builder<Content> nextContents = ImmutableList.<Content>builder().addAll(contents);
    if (!parts.isEmpty()) {
      nextContents.add(content());
    }
    GenerateContentConfig baseConfig =
        config != null ? config : GenerateContentConfig.builder().build();
    return new ResumeRequest(
        nextContents.build(), baseConfig.toBuilder().continuationToken(nextToken).build());
  }

  /** Appends {@code newParts}, joining text as the streaming aggregator does. */
  private void appendParts(List<Part> newParts) {
    for (Part part : newParts) {
      int last = parts.size() - 1;
      Part previous = last >= 0 ? parts.get(last) : null;
      if (previous != null && canJoin(previous, part)) {
        Part.Builder joined =
            Part.builder().text(previous.text().orElseThrow() + part.text().orElseThrow());
        previous.thought().ifPresent(joined::thought);
        previous
            .thoughtSignature()
            .filter(signature -> signature.length > 0)
            .or(part::thoughtSignature)
            .ifPresent(joined::thoughtSignature);
        parts.set(last, joined.build());
      } else {
        parts.add(part);
      }
    }
  }

  /** Returns whether two parts are non-empty text of the same kind. */
  private static boolean canJoin(Part first, Part second) {
    return isText(first)
        && isText(second)
        && first.thought().orElse(false).equals(second.thought().orElse(false));
  }

  /** Returns whether the part is non-empty text with at most a thought flag and a signature. */
  private static boolean isText(Part part) {
    if (part.text().filter(text -> !text.isEmpty()).isEmpty()) {
      return false;
    }
    Part.Builder text = Part.builder().text(part.text().orElseThrow());
    part.thought().ifPresent(text::thought);
    part.thoughtSignature().ifPresent(text::thoughtSignature);
    return text.build().equals(part);
  }

  /** Returns the combined token usage of two requests. */
  private static @Nullable GenerateContentResponseUsageMetadata addUsage(
      @Nullable GenerateContentResponseUsageMetadata total,
      @Nullable GenerateContentResponseUsageMetadata next) {
    if (total == null || next == null) {
      return total == null ? next : total;
    }
    GenerateContentResponseUsageMetadata.Builder combined = next.toBuilder();
    addCounts(total.promptTokenCount(), next.promptTokenCount())
        .ifPresent(combined::promptTokenCount);
    addCounts(total.candidatesTokenCount(), next.candidatesTokenCount())
        .ifPresent(combined::candidatesTokenCount);
    addCounts(total.totalTokenCount(), next.totalTokenCount()).ifPresent(combined::totalTokenCount);
    addCounts(total.cachedContentTokenCount(), next.cachedContentTokenCount())
        .ifPresent(combined::cachedContentTokenCount);
    addCounts(total.thoughtsTokenCount(), next.thoughtsTokenCount())
        .ifPresent(combined::thoughtsTokenCount);
    addCounts(total.toolUsePromptTokenCount(), next.toolUsePromptTokenCount())
        .ifPresent(combined::toolUsePromptTokenCount);
    addModalityCounts(total.promptTokensDetails(), next.promptTokensDetails())
        .ifPresent(combined::promptTokensDetails);
    addModalityCounts(total.candidatesTokensDetails(), next.candidatesTokensDetails())
        .ifPresent(combined::candidatesTokensDetails);
    addModalityCounts(total.cacheTokensDetails(), next.cacheTokensDetails())
        .ifPresent(combined::cacheTokensDetails);
    addModalityCounts(total.toolUsePromptTokensDetails(), next.toolUsePromptTokensDetails())
        .ifPresent(combined::toolUsePromptTokensDetails);
    return combined.build();
  }

  private static Optional<Integer> addCounts(Optional<Integer> first, Optional<Integer> second) {
    if (first.isEmpty() && second.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(first.orElse(0) + second.orElse(0));
  }

  /** Sums token counts that share a modality, keeping first-seen order. */
  private static Optional<ImmutableList<ModalityTokenCount>> addModalityCounts(
      Optional<List<ModalityTokenCount>> first, Optional<List<ModalityTokenCount>> second) {
    if (first.isEmpty() && second.isEmpty()) {
      return Optional.empty();
    }
    List<ModalityTokenCount> counts = new ArrayList<>(first.orElse(ImmutableList.of()));
    counts.addAll(second.orElse(ImmutableList.of()));
    Map<@Nullable MediaModality, Integer> totals = new LinkedHashMap<>();
    for (ModalityTokenCount count : counts) {
      totals.merge(count.modality().orElse(null), count.tokenCount().orElse(0), Integer::sum);
    }
    ImmutableList.Builder<ModalityTokenCount> summed = ImmutableList.builder();
    totals.forEach(
        (modality, tokenCount) -> {
          ModalityTokenCount.Builder count = ModalityTokenCount.builder().tokenCount(tokenCount);
          if (modality != null) {
            count.modality(modality);
          }
          summed.add(count.build());
        });
    return Optional.of(summed.build());
  }

  /** The contents and config of a request that resumes a paused generation. */
  record ResumeRequest(ImmutableList<Content> contents, GenerateContentConfig config) {}

  /** What one streamed request generated, recorded to resume the generation if it pauses. */
  static final class StreamedOutput {
    private final GeminiContinuation continuation;
    private final List<Part> parts = new ArrayList<>();
    private byte @Nullable [] token = null;
    private @Nullable GenerateContentResponseUsageMetadata usage = null;

    StreamedOutput(GeminiContinuation continuation) {
      this.continuation = continuation;
    }

    /** Returns the parts recorded so far, excluding stream terminators. */
    ImmutableList<Part> parts() {
      return ImmutableList.copyOf(parts);
    }

    /** Returns the token that resumes the generation if this request paused, or null. */
    byte @Nullable [] token() {
      return token;
    }

    /** Returns the request's usage metadata, taken from the last chunk that carried it. */
    @Nullable GenerateContentResponseUsageMetadata usage() {
      return usage;
    }

    /**
     * Records a chunk and returns what to aggregate: the chunk, with the finish reason of a resumed
     * pause cleared since that pause does not end the generation, or nothing for a resumed pause
     * without output.
     */
    Optional<GenerateContentResponse> record(GenerateContentResponse chunk) {
      chunk.usageMetadata().ifPresent(chunkUsage -> usage = chunkUsage);
      List<Candidate> candidates = chunk.candidates().orElse(ImmutableList.of());
      if (candidates.isEmpty()) {
        return Optional.of(chunk);
      }
      Candidate candidate = candidates.get(0);
      // A stream terminator carries nothing, and resending it would add an empty text part.
      ImmutableList<Part> chunkParts =
          candidate.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
              .filter(part -> !Gemini.StreamingResponseAggregator.isStreamTerminator(part))
              .collect(ImmutableList.toImmutableList());
      parts.addAll(chunkParts);
      byte[] chunkToken = resumeToken(chunk);
      if (chunkToken == null) {
        return Optional.of(chunk);
      }
      token = chunkToken;
      if (!continuation.willResume(chunkToken)) {
        // A pause that is not resumed ends the generation, so it keeps its finish reason.
        return Optional.of(chunk);
      }
      if (chunkParts.isEmpty()) {
        // Drop an empty pause chunk: as the first chunk, it would emit a complete, empty response.
        return Optional.empty();
      }
      List<Candidate> resumable = new ArrayList<>(candidates);
      resumable.set(0, candidate.toBuilder().clearFinishReason().build());
      return Optional.of(chunk.toBuilder().candidates(resumable).build());
    }
  }
}
