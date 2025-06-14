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

package com.google.adk.sessions;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/** Utility functions for session service. */
public final class SessionUtils {

  /**
   * Private constructor to prevent instantiation of this utility class.
   * This class provides only static helper methods.
   */
  public SessionUtils() {}

  /**
   * Encodes the inline data (Blobs) within the parts of a {@link Content} object using Base64.
   * This method iterates through all parts of the content. If a part contains inline binary data,
   * that data is Base64 encoded, and the part is replaced with a new part containing the encoded data.
   * Other parts (e.g., text parts) remain unchanged.
   *
   * @param content The {@link Content} object whose inline data parts are to be encoded.
   * @return A new {@link Content} object with its inline data parts Base64 encoded.
   */
  public static Content encodeContent(Content content) {
    List<Part> encodedParts = new ArrayList<>();
    for (Part part : content.parts().orElse(ImmutableList.of())) {
      boolean isInlineDataPresent = false;
      if (part.inlineData() != null) {
        Optional<Blob> inlineDataOptional = part.inlineData();
        if (inlineDataOptional.isPresent()) {
          Blob inlineDataBlob = inlineDataOptional.get();
          Optional<byte[]> dataOptional = inlineDataBlob.data();
          if (dataOptional.isPresent()) {
            byte[] dataBytes = dataOptional.get();
            byte[] encodedData = Base64.getEncoder().encode(dataBytes);
            encodedParts.add(
                part.toBuilder().inlineData(Blob.builder().data(encodedData).build()).build());
            isInlineDataPresent = true;
          }
        }
      }
      if (!isInlineDataPresent) {
        encodedParts.add(part);
      }
    }
    return toContent(encodedParts, content.role());
  }

  /**
   * Decodes the Base64 encoded inline data (Blobs) within the parts of a {@link Content} object.
   * This method iterates through all parts of the content. If a part contains inline binary data
   * that is Base64 encoded, that data is decoded, and the part is replaced with a new part
   * containing the decoded raw data. Other parts (e.g., text parts) remain unchanged.
   *
   * @param content The {@link Content} object whose inline data parts are to be decoded.
   * @return A new {@link Content} object with its inline data parts Base64 decoded.
   */
  public static Content decodeContent(Content content) {
    List<Part> decodedParts = new ArrayList<>();
    for (Part part : content.parts().orElse(ImmutableList.of())) {
      boolean isInlineDataPresent = false;
      if (part.inlineData() != null) {
        Optional<Blob> inlineDataOptional = part.inlineData();
        if (inlineDataOptional.isPresent()) {
          Blob inlineDataBlob = inlineDataOptional.get();
          Optional<byte[]> dataOptional = inlineDataBlob.data();
          if (dataOptional.isPresent()) {
            byte[] dataBytes = dataOptional.get();
            byte[] decodedData = Base64.getDecoder().decode(dataBytes);
            decodedParts.add(
                part.toBuilder().inlineData(Blob.builder().data(decodedData).build()).build());
            isInlineDataPresent = true;
          }
        }
      }
      if (!isInlineDataPresent) {
        decodedParts.add(part);
      }
    }
    return toContent(decodedParts, content.role());
  }

  /**
   * Creates a {@link Content} object from a list of {@link Part} objects and an optional role.
   * This is a helper method used internally to construct content objects.
   *
   * @param parts The {@link List} of {@link Part} objects to include in the content.
   * @param role An {@link Optional} string representing the role of the content (e.g., "user", "model").
   * @return A new {@link Content} object.
   */
  private static Content toContent(List<Part> parts, Optional<String> role) {
    Content.Builder contentBuilder = Content.builder().parts(parts);
    role.ifPresent(contentBuilder::role);
    return contentBuilder.build();
  }
}
