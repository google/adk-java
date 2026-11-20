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
   * Accumulates {@code response} and returns it as a partial response, with an ID on every function
   * call. Returns empty if the chunk carries no content, such as a metadata-only chunk.
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
    }
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
    // Gemini can end a streamed call with a separate nameless willContinue=false part.
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

  private static boolean isStop(FinishReason reason) {
    return reason.knownEnum() == FinishReason.Known.STOP;
  }
}
