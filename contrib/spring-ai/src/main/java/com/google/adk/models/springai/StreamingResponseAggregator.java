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
package com.google.adk.models.springai;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Aggregates streaming responses from Spring AI models.
 *
 * <p>This class helps manage the accumulation of partial responses in streaming mode, ensuring that
 * text content is properly concatenated and tool calls are correctly handled.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. All public methods are synchronized to ensure
 * safe concurrent access. The internal state is protected using a combination of thread-safe data
 * structures and synchronization locks.
 */
public class StreamingResponseAggregator {

  private final List<Part> textParts = new CopyOnWriteArrayList<>();

  /** Tracks the thought flag of the part currently being accumulated (Kotlin parity). */
  private Boolean currentThoughtFlag = null;

  /**
   * Latest non-empty usage metadata seen on the stream, carried on the final response. Visibility
   * is guaranteed by {@code lock}: written in {@code processStreamingResponse} and read in {@code
   * getFinalResponse()}, both under the write lock.
   */
  private GenerateContentResponseUsageMetadata usageMetadata;

  private final List<Part> toolCallParts = new CopyOnWriteArrayList<>();

  /** Non-text, non-function-call parts (inlineData/fileData) passed through as-is. */
  private final List<Part> otherParts = new CopyOnWriteArrayList<>();

  private final ReadWriteLock lock = new ReentrantReadWriteLock();
  private volatile boolean isFirstResponse = true;

  /** Last finish reason seen on the stream; carried on the final, non-STOP becomes an error. */
  private FinishReason finishReason;

  private String errorMessage;
  private String modelVersion;

  /**
   * Processes a streaming LlmResponse and returns the current aggregated state.
   *
   * @param response The streaming response to process
   * @return The current aggregated LlmResponse
   */
  public LlmResponse processStreamingResponse(LlmResponse response) {
    if (response.content().isEmpty()) {
      return response;
    }

    Content content = response.content().get();
    if (content.parts().isEmpty()) {
      return response;
    }

    lock.writeLock().lock();
    try {
      response.usageMetadata().ifPresent(usage -> this.usageMetadata = usage);
      response.finishReason().ifPresent(reason -> this.finishReason = reason);
      response.errorMessage().ifPresent(message -> this.errorMessage = message);
      response.modelVersion().ifPresent(version -> this.modelVersion = version);

      // Process each part in the response. Text is grouped into parts by its thought flag
      // (Kotlin parity): reasoning and answer must not merge into one part, or reasoning is
      // persisted as answer text and replayed to the model on the next turn.
      for (Part part : content.parts().get()) {
        if (part.functionCall().isPresent()) {
          // Tool calls are typically complete in each response
          toolCallParts.add(part);
        } else if (part.text().isPresent()) {
          boolean thought = part.thought().orElse(false);
          if (currentThoughtFlag != null && currentThoughtFlag == thought && !textParts.isEmpty()) {
            Part last = textParts.get(textParts.size() - 1);
            textParts.set(
                textParts.size() - 1, withText(last, last.text().orElse("") + part.text().get()));
          } else {
            textParts.add(withThought(Part.fromText(part.text().get()), thought));
            currentThoughtFlag = thought;
          }
        } else {
          // inlineData/fileData and other non-text parts pass through as-is
          otherParts.add(part);
        }
      }

      // Create aggregated content
      List<Part> aggregatedParts = new ArrayList<>(textParts);
      aggregatedParts.addAll(toolCallParts);

      Content aggregatedContent = Content.builder().role("model").parts(aggregatedParts).build();

      // Determine if this is still partial
      boolean isPartial = response.partial().orElse(false);
      boolean isTurnComplete = response.turnComplete().orElse(true);

      LlmResponse aggregatedResponse =
          LlmResponse.builder()
              .content(aggregatedContent)
              .partial(isPartial)
              .turnComplete(isTurnComplete)
              .build();

      isFirstResponse = false;
      return aggregatedResponse;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Returns the final aggregated response and resets the aggregator.
   *
   * @return The final complete response
   */
  public LlmResponse getFinalResponse() {
    lock.writeLock().lock();
    try {
      List<Part> finalParts = new ArrayList<>(textParts);
      finalParts.addAll(toolCallParts);
      finalParts.addAll(otherParts);

      Content finalContent = Content.builder().role("model").parts(finalParts).build();

      LlmResponse.Builder finalBuilder =
          LlmResponse.builder().content(finalContent).partial(false).turnComplete(true);
      // Carry the last finish reason: a non-STOP stream is an error turn, not a clean one
      // (Kotlin aggregator / Python LiteLLM parity).
      if (finishReason != null) {
        finalBuilder.finishReason(finishReason);
        if (finishReason.knownEnum() != FinishReason.Known.STOP) {
          finalBuilder.errorCode(finishReason);
          if (errorMessage == null && finishReason.knownEnum() == FinishReason.Known.MAX_TOKENS) {
            errorMessage = "The generation ran out of tokens before completing.";
          }
        }
      }
      if (errorMessage != null) {
        finalBuilder.errorMessage(errorMessage);
      }
      if (modelVersion != null) {
        finalBuilder.modelVersion(modelVersion);
      }
      LlmResponse finalResponse = finalBuilder.usageMetadata(usageMetadata).build();

      // Reset internal state without calling reset() to avoid nested locking
      textParts.clear();
      currentThoughtFlag = null;
      usageMetadata = null;
      toolCallParts.clear();
      otherParts.clear();
      finishReason = null;
      errorMessage = null;
      modelVersion = null;
      isFirstResponse = true;

      return finalResponse;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Resets the aggregator for reuse. */
  public void reset() {
    lock.writeLock().lock();
    try {
      textParts.clear();
      currentThoughtFlag = null;
      usageMetadata = null;
      toolCallParts.clear();
      otherParts.clear();
      finishReason = null;
      errorMessage = null;
      modelVersion = null;
      isFirstResponse = true;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Returns true if no content has been processed yet. */
  public boolean isEmpty() {
    lock.readLock().lock();
    try {
      return textParts.isEmpty() && toolCallParts.isEmpty() && otherParts.isEmpty();
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Returns the current accumulated text length. */
  public int getAccumulatedTextLength() {
    lock.readLock().lock();
    try {
      return textParts.stream().mapToInt(part -> part.text().map(String::length).orElse(0)).sum();
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Replaces a part's text, preserving its thought flag and signature. */
  private static Part withText(Part part, String text) {
    Part.Builder builder = Part.builder().text(text);
    if (part.thought().isPresent()) {
      builder.thought(part.thought().get());
    }
    if (part.thoughtSignature().isPresent()) {
      builder.thoughtSignature(part.thoughtSignature().get());
    }
    return builder.build();
  }

  /** Returns the part with the thought flag set (Kotlin parity: reasoning segments). */
  private static Part withThought(Part part, boolean thought) {
    if (!thought) {
      return part;
    }
    Part.Builder builder = Part.builder().text(part.text().orElse(""));
    builder.thought(true);
    if (part.thoughtSignature().isPresent()) {
      builder.thoughtSignature(part.thoughtSignature().get());
    }
    return builder.build();
  }
}
