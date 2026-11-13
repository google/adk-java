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

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Aggregates the chunks of one streamed model turn into partial responses and one final response
 * holding the whole turn.
 *
 * <p>Pass each chunk to {@link #processResponse}, then call {@link #aggregate} once the stream
 * ends. An instance handles a single stream and is not thread-safe.
 */
final class StreamingResponseAggregator {

  private final List<Part> accumulatedSequence = new ArrayList<>();
  private final StringBuilder currentTextBuffer = new StringBuilder();
  private boolean currentTextIsThought;

  // Separate from the call's signature so interleaving cannot swap the two.
  private byte @Nullable [] currentTextThoughtSignature = null;
  private byte @Nullable [] currentThoughtSignature = null;
  private @Nullable LlmResponse lastResponse = null;

  // The streamed function call being assembled from partialArgs chunks.
  private @Nullable String currentFcName = null;
  private Map<String, Object> currentFcArgs = new LinkedHashMap<>();
  private @Nullable String currentFcId = null;

  /**
   * Accumulates {@code response} and returns it as a partial response, with an ID on every function
   * call. Returns empty if the chunk carries no content, such as a metadata-only chunk.
   */
  Optional<LlmResponse> processResponse(LlmResponse response) {
    lastResponse = response;
    List<Part> parts = response.content().flatMap(Content::parts).orElse(ImmutableList.of());

    // Same ID in the partial and the final response, so consumers can correlate them.
    List<Part> partsWithIds = ensureFunctionCallIds(parts);

    if (!accumulateParts(partsWithIds)) {
      return Optional.empty();
    }
    // Rebuilt from partsWithIds so the partial's function call IDs match the final response.
    Content.Builder rebuilt = Content.builder().parts(partsWithIds);
    response.content().flatMap(Content::role).ifPresent(rebuilt::role);
    return Optional.of(response.toBuilder().content(rebuilt.build()).partial(true).build());
  }

  /**
   * Returns whether any text or part has been accumulated so far. A streamed function call that is
   * still in progress does not count until it completes.
   */
  boolean hasAccumulatedContent() {
    return !accumulatedSequence.isEmpty() || !currentTextBuffer.isEmpty();
  }

  /**
   * Returns the final, non-partial response with every accumulated part, built on the last chunk's
   * metadata, with a non-STOP finish reason set as the error code. Returns empty if no chunk was
   * processed, or if nothing was accumulated and the last chunk has no finish reason.
   */
  Optional<LlmResponse> aggregate() {
    if (lastResponse == null) {
      return Optional.empty();
    }

    flushTextBufferToSequence();
    // Flush any in-progress streamed function call whose stream ended before completing.
    flushFunctionCallToSequence();

    // Nothing accumulated and no finish reason: any empty/metadata chunk already streamed, skip.
    boolean hasFinishReason = lastResponse.finishReason().isPresent();
    if (accumulatedSequence.isEmpty() && !hasFinishReason) {
      return Optional.empty();
    }

    LlmResponse.Builder finalResponseBuilder = lastResponse.toBuilder().partial(null);
    if (hasFinishReason && !isStop(lastResponse)) {
      finalResponseBuilder.errorCode(lastResponse.finishReason().get());
    }

    if (accumulatedSequence.isEmpty()) {
      return Optional.of(finalResponseBuilder.build());
    }

    return Optional.of(
        finalResponseBuilder
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.copyOf(accumulatedSequence))
                    .build())
            .build());
  }

  /**
   * Returns whether the part is the empty-text terminator Gemini 3 ends a stream with: empty text
   * and nothing else worth keeping. Compared by rebuilding rather than against a single literal, so
   * a terminator that also carries an explicit {@code thought=false} is still recognized.
   */
  private static boolean isStreamTerminator(Part part) {
    if (!part.text().map(String::isEmpty).orElse(false)) {
      return false;
    }
    Part.Builder terminator = Part.builder().text("");
    part.thought().ifPresent(terminator::thought);
    return terminator.build().equals(part);
  }

  /**
   * Returns a list of parts where every function-call part has a non-empty ID. If a part's function
   * call already has an ID, the original part is preserved; otherwise a new part with a
   * client-generated ID is substituted. Non-FC parts are passed through unchanged.
   */
  private static List<Part> ensureFunctionCallIds(List<Part> parts) {
    List<Part> result = new ArrayList<>(parts.size());
    for (Part part : parts) {
      if (part.functionCall().isPresent()) {
        FunctionCall fc = part.functionCall().get();
        if (fc.id().map(String::isEmpty).orElse(true)) {
          FunctionCall withId =
              fc.toBuilder().id(FunctionCallIds.generateClientFunctionCallId()).build();
          result.add(part.toBuilder().functionCall(withId).build());
          continue;
        }
      }
      result.add(part);
    }
    return result;
  }

  /**
   * Accumulates content from incoming parts: text, function calls, and any other content part
   * (inline image/audio data, file data, code execution, server-side tool calls/responses,
   * standalone thought signatures, and future part types), which are appended verbatim as ADK
   * Python does. The empty-text part that ends a Gemini 3 stream is the one thing dropped.
   * Function-call parts passed to this method are expected to already have IDs (see {@link
   * #ensureFunctionCallIds}).
   *
   * @return true if any content part was present, false otherwise.
   */
  private boolean accumulateParts(List<Part> parts) {
    boolean hasContent = false;
    for (Part part : parts) {
      String text = part.text().orElse("");
      if (!text.isEmpty()) {
        hasContent = true;
        boolean isThought = part.thought().orElse(false);
        // Flush first, or the new run's signature would land on the flushed run.
        if (!currentTextBuffer.isEmpty() && isThought != currentTextIsThought) {
          flushTextBufferToSequence();
        }
        if (currentTextBuffer.isEmpty()) {
          currentTextIsThought = isThought;
        }
        // Keep the run's first signature, as ADK Python does.
        if (currentTextThoughtSignature == null
            && part.thoughtSignature().map(sig -> sig.length > 0).orElse(false)) {
          currentTextThoughtSignature = part.thoughtSignature().get();
        }
        currentTextBuffer.append(text);
      } else if (part.functionCall().isPresent()) {
        hasContent = true;
        processFunctionCallPart(part);
      } else if (isStreamTerminator(part)) {
        // Gemini 3 ends a stream with a bare empty text part; it carries nothing to keep.
      } else {
        // Kept as sent: a signature moved to another part would come back on an unsigned part.
        hasContent = true;
        flushTextBufferToSequence();
        accumulatedSequence.add(part);
      }
    }
    return hasContent;
  }

  /**
   * Processes a function-call part, mirroring ADK Python's {@code _process_function_call_part}. A
   * function call whose arguments are streamed across chunks (it carries {@code partialArgs} or
   * {@code willContinue=true}) is accumulated and flushed as a single complete part once it
   * finishes; a complete (non-streaming) function call is appended directly.
   */
  private void processFunctionCallPart(Part part) {
    FunctionCall fc = part.functionCall().get();
    boolean hasName = fc.name().filter(name -> !name.isEmpty()).isPresent();
    // Streamed: has partialArgs or willContinue, or is the nameless end marker of an open call.
    boolean streamedPart =
        fc.partialArgs().map(args -> !args.isEmpty()).orElse(false)
            || fc.willContinue().orElse(false)
            || (currentFcName != null && !hasName);
    if (streamedPart) {
      // Capture the thought signature from the first chunk that carries one.
      if (currentThoughtSignature == null
          && part.thoughtSignature().map(sig -> sig.length > 0).orElse(false)) {
        currentThoughtSignature = part.thoughtSignature().get();
      }
      processStreamingFunctionCall(fc);
    } else if (hasName) {
      // Complete call: flush any open streamed call first so it is neither dropped nor merged.
      flushTextBufferToSequence();
      flushFunctionCallToSequence();
      accumulatedSequence.add(part);
    }
  }

  /**
   * Accumulates one chunk of a streamed function call, mirroring ADK Python's {@code
   * _process_streaming_function_call}: merges the function name/ID and each {@code partialArg} (by
   * JSONPath) into {@link #currentFcArgs}, then flushes the completed call once {@code
   * willContinue} is no longer set.
   */
  private void processStreamingFunctionCall(FunctionCall fc) {
    fc.name().filter(name -> !name.isEmpty()).ifPresent(name -> currentFcName = name);
    // One ID for the whole call, so its partial and final responses correlate.
    if (currentFcId == null) {
      currentFcId =
          fc.id()
              .filter(id -> !id.isEmpty())
              .orElseGet(FunctionCallIds::generateClientFunctionCallId);
    }
    for (PartialArg partialArg : fc.partialArgs().orElse(ImmutableList.of())) {
      String jsonPath = partialArg.jsonPath().orElse("");
      if (jsonPath.isEmpty()) {
        continue;
      }
      applyPartialArg(partialArg, jsonPath);
    }
    if (!fc.willContinue().orElse(false)) {
      flushTextBufferToSequence();
      flushFunctionCallToSequence();
    }
  }

  /**
   * Applies a single {@link PartialArg} to {@link #currentFcArgs} at {@code jsonPath}, mirroring
   * ADK Python's {@code _get_value_from_partial_arg}: string chunks are appended to any existing
   * string at the path, while number/bool/null values overwrite.
   */
  private void applyPartialArg(PartialArg partialArg, String jsonPath) {
    if (partialArg.stringValue().isPresent()) {
      Object existing = getValueByJsonPath(jsonPath);
      String chunk = partialArg.stringValue().get();
      setValueByJsonPath(jsonPath, existing instanceof String s ? s + chunk : chunk);
    } else if (partialArg.numberValue().isPresent()) {
      setValueByJsonPath(jsonPath, partialArg.numberValue().get());
    } else if (partialArg.boolValue().isPresent()) {
      setValueByJsonPath(jsonPath, partialArg.boolValue().get());
    } else if (partialArg.nullValue().isPresent()) {
      setValueByJsonPath(jsonPath, null);
    }
  }

  /** Returns the value currently stored at {@code jsonPath} in {@link #currentFcArgs}, or null. */
  private @Nullable Object getValueByJsonPath(String jsonPath) {
    Object current = currentFcArgs;
    for (String key : splitJsonPath(jsonPath)) {
      if (current instanceof Map<?, ?> map && map.containsKey(key)) {
        current = map.get(key);
      } else {
        return null;
      }
    }
    return current;
  }

  /** Sets {@code value} at {@code jsonPath} in {@link #currentFcArgs}, creating maps as needed. */
  @SuppressWarnings("unchecked")
  private void setValueByJsonPath(String jsonPath, @Nullable Object value) {
    String[] keys = splitJsonPath(jsonPath);
    Map<String, Object> current = currentFcArgs;
    for (int i = 0; i < keys.length - 1; i++) {
      Object next = current.get(keys[i]);
      if (!(next instanceof Map)) {
        next = new LinkedHashMap<>();
        current.put(keys[i], next);
      }
      current = (Map<String, Object>) next;
    }
    current.put(keys[keys.length - 1], value);
  }

  /** Splits a JSONPath such as {@code "$.location.city"} into its component keys. */
  private static String[] splitJsonPath(String jsonPath) {
    String path = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
    return path.split("\\.");
  }

  /**
   * Flushes the accumulated streamed function call (if any) to {@link #accumulatedSequence} as a
   * single complete part, mirroring ADK Python's {@code _flush_function_call_to_sequence}.
   */
  private void flushFunctionCallToSequence() {
    if (currentFcName == null) {
      return;
    }
    FunctionCall.Builder fcBuilder =
        FunctionCall.builder().name(currentFcName).args(new LinkedHashMap<>(currentFcArgs));
    if (currentFcId != null) {
      fcBuilder.id(currentFcId);
    }
    Part.Builder partBuilder = Part.builder().functionCall(fcBuilder.build());
    if (currentThoughtSignature != null) {
      partBuilder.thoughtSignature(currentThoughtSignature);
    }
    accumulatedSequence.add(partBuilder.build());
    currentFcName = null;
    currentFcArgs = new LinkedHashMap<>();
    currentFcId = null;
    currentThoughtSignature = null;
  }

  /** Flushes any accumulated text or thought content in the buffer as a new {@link Part}. */
  private void flushTextBufferToSequence() {
    if (!currentTextBuffer.isEmpty()) {
      Part.Builder partBuilder =
          Part.builder().text(currentTextBuffer.toString()).thought(currentTextIsThought);
      if (currentTextThoughtSignature != null) {
        partBuilder.thoughtSignature(currentTextThoughtSignature);
        currentTextThoughtSignature = null;
      }
      accumulatedSequence.add(partBuilder.build());
      currentTextBuffer.setLength(0);
      currentTextIsThought = false;
    }
  }

  /** Checks whether the response finish reason indicates the stream has finished with STOP. */
  static boolean isStop(LlmResponse response) {
    return response
        .finishReason()
        .map(reason -> reason.knownEnum() == FinishReason.Known.STOP)
        .orElse(false);
  }
}
