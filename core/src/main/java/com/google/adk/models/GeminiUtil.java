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
package com.google.adk.models;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.UsageMetadata;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Request / Response utilities for {@link Gemini}. */
public final class GeminiUtil {

  public static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  private GeminiUtil() {}

  /**
   * Prepares an {@link LlmRequest} for the GenerateContent API.
   *
   * <p>This method can optionally sanitize the request and ensures that the last content part is
   * from the user to prompt a model response.
   *
   * @param llmRequest The original {@link LlmRequest}.
   * @param sanitize Whether to sanitize the request to be compatible with the Gemini API backend.
   * @return The prepared {@link LlmRequest}.
   */
  public static LlmRequest prepareGenenerateContentRequest(
      LlmRequest llmRequest, boolean sanitize) {
    return prepareGenenerateContentRequest(llmRequest, sanitize, /* stripThoughts= */ true);
  }

  /**
   * Prepares an {@link LlmRequest} for the GenerateContent API.
   *
   * <p>This method can optionally sanitize the request and ensures that the last content part is
   * from the user to prompt a model response. It also strips out any parts marked as "thoughts" and
   * removes client-side function call IDs as some LLM APIs reject requests with client-side
   * function call IDs.
   *
   * @param llmRequest The original {@link LlmRequest}.
   * @param sanitize Whether to sanitize the request to be compatible with the Gemini API backend.
   * @return The prepared {@link LlmRequest}.
   */
  public static LlmRequest prepareGenenerateContentRequest(
      LlmRequest llmRequest, boolean sanitize, boolean stripThoughts) {
    if (sanitize) {
      llmRequest = sanitizeRequestForGeminiApi(llmRequest);
    }
    llmRequest = removeClientFunctionCallId(llmRequest);
    List<Content> contents = ensureModelResponse(llmRequest.contents());
    if (stripThoughts) {
      contents = stripThoughts(contents);
    }
    return llmRequest.toBuilder().contents(contents).build();
  }

  /**
   * Sanitizes the request to ensure it is compatible with the Gemini API backend. Required as there
   * are some parameters that if included in the request will raise a runtime error if sent to the
   * wrong backend (e.g. image names only work on Vertex AI).
   *
   * @param llmRequest The request to sanitize.
   * @return The sanitized request.
   */
  public static LlmRequest sanitizeRequestForGeminiApi(LlmRequest llmRequest) {
    LlmRequest.Builder requestBuilder = llmRequest.toBuilder();
    llmRequest
        .config()
        .filter(config -> config.labels().isPresent())
        .ifPresent(
            config -> requestBuilder.config(config.toBuilder().labels(ImmutableMap.of()).build()));

    if (llmRequest.contents().isEmpty()) {
      return requestBuilder.build();
    }

    // This backend does not support the display_name parameter for file uploads,
    // so it must be removed to prevent request failures.
    ImmutableList<Content> updatedContents =
        llmRequest.contents().stream()
            .map(
                content -> {
                  if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
                    return content;
                  }

                  ImmutableList<Part> updatedParts =
                      content.parts().get().stream()
                          .map(
                              part -> {
                                Part.Builder partBuilder = part.toBuilder();
                                if (part.inlineData().flatMap(Blob::displayName).isPresent()) {
                                  Blob blob = part.inlineData().get();
                                  Blob.Builder newBlobBuilder = Blob.builder();
                                  blob.data().ifPresent(newBlobBuilder::data);
                                  blob.mimeType().ifPresent(newBlobBuilder::mimeType);
                                  partBuilder.inlineData(newBlobBuilder.build());
                                }
                                if (part.fileData().flatMap(FileData::displayName).isPresent()) {
                                  FileData fileData = part.fileData().get();
                                  FileData.Builder newFileDataBuilder = FileData.builder();
                                  fileData.fileUri().ifPresent(newFileDataBuilder::fileUri);
                                  fileData.mimeType().ifPresent(newFileDataBuilder::mimeType);
                                  partBuilder.fileData(newFileDataBuilder.build());
                                }
                                return partBuilder.build();
                              })
                          .collect(toImmutableList());

                  return content.toBuilder().parts(updatedParts).build();
                })
            .collect(toImmutableList());
    return requestBuilder.contents(updatedContents).build();
  }

  /**
   * Removes client-side function call IDs from the request.
   *
   * <p>Client-side function call IDs are internal to the ADK and should not be sent to the model.
   * This method iterates through the contents and parts, removing the ID from any {@link
   * com.google.genai.types.FunctionCall} or {@link com.google.genai.types.FunctionResponse} parts.
   *
   * @param llmRequest The request to process.
   * @return A new {@link LlmRequest} with function call IDs removed.
   */
  public static LlmRequest removeClientFunctionCallId(LlmRequest llmRequest) {
    if (llmRequest.contents().isEmpty()) {
      return llmRequest;
    }

    ImmutableList<Content> updatedContents =
        llmRequest.contents().stream()
            .map(
                content ->
                    content.toBuilder()
                        .parts(
                            content.parts().orElse(ImmutableList.of()).stream()
                                .map(GeminiUtil::removeClientFunctionCallIdFromPart)
                                .collect(toImmutableList()))
                        .build())
            .collect(toImmutableList());

    return llmRequest.toBuilder().contents(updatedContents).build();
  }

  private static Part removeClientFunctionCallIdFromPart(Part part) {
    if (part.functionCall().isPresent() && part.functionCall().get().id().isPresent()) {
      return part.toBuilder()
          .functionCall(part.functionCall().get().toBuilder().clearId().build())
          .build();
    }
    if (part.functionResponse().isPresent() && part.functionResponse().get().id().isPresent()) {
      return part.toBuilder()
          .functionResponse(part.functionResponse().get().toBuilder().clearId().build())
          .build();
    }
    return part;
  }

  /**
   * Ensures that the content is conducive to prompting a model response by ensuring the last
   * content part is from the user.
   *
   * <p>If the list is empty or the last message is not from the user, a new "user" content part
   * with a {@link #CONTINUE_OUTPUT_MESSAGE} is appended to the list. This is necessary to prompt
   * the model to generate a response.
   *
   * @param contents The original list of {@link Content}.
   * @return A list of {@link Content} where the last element is guaranteed to be from the "user".
   */
  static List<Content> ensureModelResponse(List<Content> contents) {
    // Last content must be from the user, otherwise the model won't respond.
    if (contents.isEmpty()
        || !Ascii.equalsIgnoreCase(Iterables.getLast(contents).role().orElse(""), "user")) {
      Content userContent =
          Content.builder()
              .parts(ImmutableList.of(Part.fromText(CONTINUE_OUTPUT_MESSAGE)))
              .role("user")
              .build();
      return Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }
    return contents;
  }

  /**
   * Extracts the first part of an LlmResponse, if available.
   *
   * @param llmResponse The LlmResponse to extract the first part from.
   * @return The first part, or an empty optional if not found.
   */
  public static Optional<Part> getPart0FromLlmResponse(LlmResponse llmResponse) {
    return llmResponse
        .content()
        .flatMap(Content::parts)
        .filter(parts -> !parts.isEmpty())
        .map(parts -> parts.get(0));
  }

  /**
   * Extracts text content from the first part of an LlmResponse, if available.
   *
   * @param llmResponse The LlmResponse to extract text from.
   * @return The text content, or an empty string if not found.
   */
  public static String getTextFromLlmResponse(LlmResponse llmResponse) {
    return llmResponse
        .content()
        .flatMap(Content::parts)
        .filter(parts -> !parts.isEmpty())
        .map(parts -> parts.get(0))
        .flatMap(Part::text)
        .orElse("");
  }

  /**
   * Determines if accumulated text should be emitted based on the current LlmResponse. We flush if
   * current response is not a text continuation (e.g., no content, no parts, or the first part is
   * not inline_data, meaning it's something else or just empty, thereby warranting a flush of
   * preceding text).
   *
   * @param currentLlmResponse The current LlmResponse being processed.
   * @return True if accumulated text should be emitted, false otherwise.
   */
  public static boolean shouldEmitAccumulatedText(LlmResponse currentLlmResponse) {
    // We should emit if the first part of the content does NOT have inlineData.
    // This means we return true if content, parts, or the first part's inlineData is empty.
    return currentLlmResponse
        .content()
        .flatMap(Content::parts)
        .filter(parts -> !parts.isEmpty())
        .map(parts -> parts.get(0))
        .flatMap(Part::inlineData)
        .isEmpty();
  }

  /** Removes any `Part` that contains only a `thought` from the content list. */
  public static ImmutableList<Content> stripThoughts(List<Content> originalContents) {
    return originalContents.stream()
        .map(
            content -> {
              ImmutableList<Part> nonThoughtParts =
                  content.parts().orElse(ImmutableList.of()).stream()
                      // Keep if thought is not present OR if thought is present but false
                      .filter(part -> part.thought().map(isThought -> !isThought).orElse(true))
                      .collect(toImmutableList());
              return content.toBuilder().parts(nonThoughtParts).build();
            })
        .collect(toImmutableList());
  }

  public static GenerateContentResponseUsageMetadata toGenerateContentResponseUsageMetadata(
      UsageMetadata usageMetadata) {
    GenerateContentResponseUsageMetadata.Builder builder =
        GenerateContentResponseUsageMetadata.builder();
    usageMetadata.promptTokenCount().ifPresent(builder::promptTokenCount);
    usageMetadata.cachedContentTokenCount().ifPresent(builder::cachedContentTokenCount);
    usageMetadata.responseTokenCount().ifPresent(builder::candidatesTokenCount);
    usageMetadata.toolUsePromptTokenCount().ifPresent(builder::toolUsePromptTokenCount);
    usageMetadata.thoughtsTokenCount().ifPresent(builder::thoughtsTokenCount);
    usageMetadata.totalTokenCount().ifPresent(builder::totalTokenCount);
    usageMetadata.promptTokensDetails().ifPresent(builder::promptTokensDetails);
    usageMetadata.cacheTokensDetails().ifPresent(builder::cacheTokensDetails);
    usageMetadata.responseTokensDetails().ifPresent(builder::candidatesTokensDetails);
    usageMetadata.toolUsePromptTokensDetails().ifPresent(builder::toolUsePromptTokensDetails);
    usageMetadata.trafficType().ifPresent(builder::trafficType);
    return builder.build();
  }
}
