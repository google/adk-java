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
import com.google.common.primitives.Ints;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.PartialArg;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Aggregates the chunks of one streamed model turn into partial responses and one final response
 * holding the whole turn.
 *
 * <p>Pass each chunk to {@link #processResponse}, then call {@link #aggregate} once the stream
 * ends. An instance handles a single stream and is not thread-safe.
 */
final class StreamingResponseAggregator {
  /** The largest list index a streamed argument path may set, as in ADK Python. */
  private static final int MAX_ARRAY_INDEX = 10_000;

  /**
   * A JSONPath segment, as in ADK Python: a list index such as {@code [0]}, a quoted key such as
   * {@code ['a.b']} or {@code ["a.b"]}, or a key with an optional dot. Keys may hold escapes. The
   * key loops are possessive: a greedy one recurses per character and overflows the stack.
   */
  private static final Pattern JSON_PATH_SEGMENT =
      Pattern.compile(
          "\\[\\s*(\\d+)\\s*]"
              + "|\\['((?:[^'\\\\]|\\\\.)*+)'\\]"
              + "|\\[\"((?:[^\"\\\\]|\\\\.)*+)\"\\]"
              + "|\\.?((?:[^.\\[\\]\\\\]|\\\\.)++)",
          // Only \n ends a line, so "." matches what Python's "." does.
          Pattern.UNIX_LINES);

  /** A backslash escape in a JSONPath key: {@code \\uXXXX} or one escaped character. */
  private static final Pattern JSON_PATH_ESCAPE =
      Pattern.compile("\\\\u([0-9a-fA-F]{4})|\\\\(.)", Pattern.UNIX_LINES);

  /** A whole number whose magnitude is at most 2^53 - 1 has an exact double; others may not. */
  private static final double MAX_EXACT_DOUBLE_INTEGER = 9007199254740991d;

  private final List<Part> accumulatedSequence = new ArrayList<>();
  private final StringBuilder currentTextBuffer = new StringBuilder();
  private boolean currentTextIsThought;
  // Kept apart from the streamed call's signature so interleaved chunks cannot swap them.
  private byte @Nullable [] currentTextThoughtSignature = null;

  private @Nullable LlmResponse lastResponse = null;

  // Latest value from any chunk: providers may send these before the last chunk.
  private @Nullable GenerateContentResponseUsageMetadata usageMetadata = null;
  private @Nullable FinishReason finishReason = null;
  private @Nullable GroundingMetadata groundingMetadata = null;
  private @Nullable Content terminatorContent = null;

  // The streamed function call being assembled from partialArgs chunks.
  private @Nullable String currentFcName = null;
  private Map<String, Object> currentFcArgs = new LinkedHashMap<>();
  private @Nullable String currentFcId = null;
  private byte @Nullable [] currentThoughtSignature = null;

  /**
   * Accumulates {@code response} and returns it as a partial response in which every function call
   * carries an ID: its own model-provided ID, or else the ID of the call it belongs to. A call
   * without a name also gets an ID, though it is left out of the final response. Returns empty if
   * the chunk carries no content, such as a metadata-only chunk.
   */
  Optional<LlmResponse> processResponse(LlmResponse response) {
    lastResponse = response;
    response.usageMetadata().ifPresent(usage -> usageMetadata = usage);
    response.finishReason().ifPresent(reason -> finishReason = reason);
    response.groundingMetadata().ifPresent(grounding -> groundingMetadata = grounding);
    List<Part> parts = response.content().flatMap(Content::parts).orElse(ImmutableList.of());
    // Only terminators: a dropped part, such as a nameless function call, must not reach the final.
    if (parts.stream().allMatch(StreamingResponseAggregator::isStreamTerminator)) {
      response.content().ifPresent(content -> terminatorContent = content);
      return Optional.empty();
    }
    List<Part> partsWithIds = accumulateParts(parts);
    // Rebuilt from partsWithIds so the partial's function call IDs match the final response.
    Content.Builder rebuilt = Content.builder().parts(partsWithIds);
    response.content().flatMap(Content::role).ifPresent(rebuilt::role);
    return Optional.of(response.toBuilder().content(rebuilt.build()).partial(true).build());
  }

  /**
   * Returns the final, non-partial response: every accumulated part, or else the latest content
   * made only of stream terminators, with the latest usage, finish reason and grounding metadata;
   * other fields come from the last chunk. Once a finish reason is seen, the error code is that
   * reason unless it is STOP, and the error message is cleared. Returns empty only if no chunk was
   * processed.
   */
  Optional<LlmResponse> aggregate() {
    if (lastResponse == null) {
      return Optional.empty();
    }

    flushTextBufferToSequence();
    // Flush any in-progress streamed function call whose stream ended before completing.
    flushFunctionCallToSequence();

    LlmResponse.Builder finalResponseBuilder =
        lastResponse.toBuilder()
            .partial(null)
            .usageMetadata(usageMetadata)
            .finishReason(finishReason)
            .groundingMetadata(groundingMetadata);
    if (finishReason != null) {
      // Overrides the placeholder error a trailing chunk without candidates carries.
      finalResponseBuilder.errorCode(isStop(finishReason) ? null : finishReason).errorMessage(null);
    }

    return Optional.of(
        finalResponseBuilder
            .content(
                accumulatedSequence.isEmpty()
                    // Without content or an error code, BaseLlmFlow drops the final and its usage.
                    ? terminatorContent
                    : Content.builder()
                        .role("model")
                        .parts(new ArrayList<>(accumulatedSequence))
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
   * Accumulates content from incoming parts: text, function calls, and any other content part
   * (inline image/audio data, file data, code execution, server-side tool calls/responses,
   * standalone thought signatures, and future part types), which are appended verbatim as ADK
   * Python does. The empty-text part that ends a Gemini 3 stream is the one thing dropped.
   *
   * @return a mutable list of the parts for the partial response, each function call carrying an ID
   */
  private List<Part> accumulateParts(List<Part> parts) {
    List<Part> partsWithIds = new ArrayList<>(parts.size());
    for (Part part : parts) {
      String text = part.text().orElse("");
      if (!text.isEmpty()) {
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
        partsWithIds.add(processFunctionCallPart(part));
        continue;
      } else if (isStreamTerminator(part)) {
        // Gemini 3 ends a stream with a bare empty text part; it carries nothing to keep.
      } else {
        // Kept as sent: a signature moved to another part would come back on an unsigned part.
        flushTextBufferToSequence();
        accumulatedSequence.add(part);
      }
      partsWithIds.add(part);
    }
    return partsWithIds;
  }

  /**
   * Processes a function-call part, mirroring ADK Python's {@code _process_function_call_part}. A
   * function call whose arguments are streamed across chunks is accumulated and flushed as a single
   * complete part once it finishes; a complete (non-streaming) function call closes any open call
   * and, if it has a name, is appended directly.
   *
   * @return the part for the partial response, its function call carrying an ID
   */
  private Part processFunctionCallPart(Part part) {
    FunctionCall fc = part.functionCall().get();
    String name = fc.name().filter(n -> !n.isEmpty()).orElse(null);
    String modelId = fc.id().filter(id -> !id.isEmpty()).orElse(null);
    // A part without args belongs to the open call; Gemini ends a call with an empty one.
    boolean streamedPart =
        fc.partialArgs().map(args -> !args.isEmpty()).orElse(false)
            || fc.willContinue().orElse(false)
            || (currentFcId != null && fc.args().map(Map::isEmpty).orElse(true));
    if (!streamedPart) {
      // Even a nameless call gets an ID: BaseLlmFlow throws on an ID-less call without a role.
      Part withId =
          modelId != null
              ? part
              : withFunctionCallId(part, fc, FunctionCallIds.generateClientFunctionCallId());
      // A complete call ends the open one; a stray nameless marker leaves the text intact.
      if (name != null || currentFcId != null) {
        flushTextBufferToSequence();
        flushFunctionCallToSequence();
      }
      if (name != null) {
        accumulatedSequence.add(withId);
      }
      return withId;
    }

    // A new name means a different tool, so the open call ends here.
    if (name != null && currentFcName != null && !name.equals(currentFcName)) {
      flushTextBufferToSequence();
      flushFunctionCallToSequence();
    }
    if (currentFcId == null) {
      currentFcId = modelId != null ? modelId : FunctionCallIds.generateClientFunctionCallId();
    }
    if (name != null) {
      currentFcName = name;
    }
    // Capture the thought signature from the first chunk that carries one.
    if (currentThoughtSignature == null
        && part.thoughtSignature().map(sig -> sig.length > 0).orElse(false)) {
      currentThoughtSignature = part.thoughtSignature().get();
    }
    // A continuation without its own ID carries the call's ID, so partials and the final match.
    Part withId = modelId != null ? part : withFunctionCallId(part, fc, currentFcId);
    processStreamingFunctionCall(fc);
    return withId;
  }

  private static Part withFunctionCallId(Part part, FunctionCall fc, String id) {
    return part.toBuilder().functionCall(fc.toBuilder().id(id).build()).build();
  }

  /**
   * Accumulates one chunk of a streamed function call, mirroring ADK Python's {@code
   * _process_streaming_function_call}: merges each {@code partialArg} (by JSONPath) into {@link
   * #currentFcArgs}, then flushes the completed call once {@code willContinue} is no longer true.
   */
  private void processStreamingFunctionCall(FunctionCall fc) {
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
      setValueByJsonPath(jsonPath, wholeNumberAsInteger(partialArg.numberValue().get()));
    } else if (partialArg.boolValue().isPresent()) {
      setValueByJsonPath(jsonPath, partialArg.boolValue().get());
    } else if (partialArg.nullValue().isPresent()) {
      setValueByJsonPath(jsonPath, null);
    }
  }

  /**
   * Returns a whole number whose magnitude is at most 2^53 - 1 as the Integer or Long a
   * non-streamed call carries, because schema validation rejects a Double for an INTEGER field. Any
   * other value stays a Double.
   */
  private static Number wholeNumberAsInteger(double value) {
    if (value != Math.rint(value) || Math.abs(value) > MAX_EXACT_DOUBLE_INTEGER) {
      return value;
    }
    long whole = (long) value;
    if (whole == (int) whole) {
      return (int) whole;
    }
    return whole;
  }

  /** Returns the value currently stored at {@code jsonPath} in {@link #currentFcArgs}, or null. */
  private @Nullable Object getValueByJsonPath(String jsonPath) {
    Object current = currentFcArgs;
    for (Object segment : parseJsonPath(jsonPath)) {
      if (segment instanceof String key
          && current instanceof Map<?, ?> map
          && map.containsKey(key)) {
        current = map.get(key);
      } else if (segment instanceof Integer index
          && current instanceof List<?> list
          && index < list.size()) {
        current = list.get(index);
      } else {
        return null;
      }
    }
    return current;
  }

  /**
   * Sets {@code value} at {@code jsonPath} in {@link #currentFcArgs}, creating maps and lists as
   * needed and padding lists with nulls, as ADK Python's {@code _set_value_by_json_path} does. It
   * stops at a segment that hits a value of the wrong type or an index above 10,000; containers
   * created before that segment stay.
   */
  @SuppressWarnings("unchecked")
  private void setValueByJsonPath(String jsonPath, @Nullable Object value) {
    List<Object> segments = parseJsonPath(jsonPath);
    Object current = currentFcArgs;
    for (int i = 0; i < segments.size(); i++) {
      boolean last = i == segments.size() - 1;
      Object child =
          last
              ? value
              : segments.get(i + 1) instanceof Integer ? new ArrayList<>() : new LinkedHashMap<>();
      if (segments.get(i) instanceof String key && current instanceof Map<?, ?>) {
        Map<String, Object> map = (Map<String, Object>) current;
        if (last || !map.containsKey(key)) {
          map.put(key, child);
        }
        current = map.get(key);
      } else if (segments.get(i) instanceof Integer index
          && current instanceof List<?>
          && index <= MAX_ARRAY_INDEX) {
        List<Object> list = (List<Object>) current;
        while (list.size() <= index) {
          list.add(null);
        }
        if (last || list.get(index) == null) {
          list.set(index, child);
        }
        current = list.get(index);
      } else {
        return;
      }
    }
  }

  /** Splits a JSONPath such as {@code "$.items[0].name"} into map keys and list indices. */
  private static List<Object> parseJsonPath(String jsonPath) {
    String path =
        jsonPath.startsWith("$.")
            ? jsonPath.substring(2)
            : jsonPath.startsWith("$") ? jsonPath.substring(1) : jsonPath;
    Matcher matcher = JSON_PATH_SEGMENT.matcher(path);
    List<Object> segments = new ArrayList<>();
    while (matcher.find()) {
      if (matcher.group(1) != null) {
        Integer index = Ints.tryParse(matcher.group(1));
        // An index too large for an int is above MAX_ARRAY_INDEX anyway.
        segments.add(index == null ? Integer.MAX_VALUE : index);
      } else {
        String key =
            matcher.group(2) != null
                ? matcher.group(2)
                : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
        segments.add(unescapeJsonPathKey(key));
      }
    }
    return segments;
  }

  /** Resolves backslash escapes in a JSONPath key the way ADK Python does. */
  private static String unescapeJsonPathKey(String key) {
    return JSON_PATH_ESCAPE
        .matcher(key)
        .replaceAll(match ->
            Matcher.quoteReplacement(
                match.group(1) != null
                    ? String.valueOf((char) Integer.parseInt(match.group(1), 16))
                    : switch (match.group(2)) {
                      case "n" -> "\n";
                      case "r" -> "\r";
                      case "t" -> "\t";
                      case "b" -> "\b";
                      case "f" -> "\f";
                      default -> match.group(2);
                    }));
  }

  /**
   * Flushes the accumulated streamed function call (if any) to {@link #accumulatedSequence} as a
   * single complete part, mirroring ADK Python's {@code _flush_function_call_to_sequence}.
   */
  private void flushFunctionCallToSequence() {
    if (currentFcName != null) {
      FunctionCall fc =
          FunctionCall.builder()
              .name(currentFcName)
              .args(new LinkedHashMap<>(currentFcArgs))
              .id(Objects.requireNonNull(currentFcId))
              .build();
      Part.Builder partBuilder = Part.builder().functionCall(fc);
      if (currentThoughtSignature != null) {
        partBuilder.thoughtSignature(currentThoughtSignature);
      }
      accumulatedSequence.add(partBuilder.build());
    }
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

  private static boolean isStop(FinishReason reason) {
    return reason.knownEnum() == FinishReason.Known.STOP;
  }
}
