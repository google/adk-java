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
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates streaming responses from Spring AI models.
 *
 * <p>This class helps manage the accumulation of partial responses in streaming mode, ensuring that
 * text content is properly concatenated and tool calls are correctly handled.
 */
public class StreamingResponseAggregator {

  private final StringBuilder textAccumulator = new StringBuilder();
  private final List<Part> toolCallParts = new ArrayList<>();
  private boolean isFirstResponse = true;

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

    // Process each part in the response
    for (Part part : content.parts().get()) {
      if (part.text().isPresent()) {
        textAccumulator.append(part.text().get());
      } else if (part.functionCall().isPresent()) {
        // Tool calls are typically complete in each response
        toolCallParts.add(part);
      }
    }

    // Create aggregated content
    List<Part> aggregatedParts = new ArrayList<>();
    if (textAccumulator.length() > 0) {
      aggregatedParts.add(Part.fromText(textAccumulator.toString()));
    }
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
  }

  /**
   * Returns the final aggregated response and resets the aggregator.
   *
   * @return The final complete response
   */
  public LlmResponse getFinalResponse() {
    List<Part> finalParts = new ArrayList<>();
    if (textAccumulator.length() > 0) {
      finalParts.add(Part.fromText(textAccumulator.toString()));
    }
    finalParts.addAll(toolCallParts);

    Content finalContent = Content.builder().role("model").parts(finalParts).build();

    LlmResponse finalResponse =
        LlmResponse.builder().content(finalContent).partial(false).turnComplete(true).build();

    // Reset for next use
    reset();
    return finalResponse;
  }

  /** Resets the aggregator for reuse. */
  public void reset() {
    textAccumulator.setLength(0);
    toolCallParts.clear();
    isFirstResponse = true;
  }

  /** Returns true if no content has been processed yet. */
  public boolean isEmpty() {
    return textAccumulator.length() == 0 && toolCallParts.isEmpty();
  }

  /** Returns the current accumulated text length. */
  public int getAccumulatedTextLength() {
    return textAccumulator.length();
  }
}
