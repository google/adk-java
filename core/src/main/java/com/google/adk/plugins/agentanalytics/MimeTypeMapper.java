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

package com.google.adk.plugins.agentanalytics;

import com.google.common.collect.ImmutableMap;

/** Utility to map MIME types to file extensions. */
final class MimeTypeMapper {
  private static final ImmutableMap<String, String> MIME_TO_EXT =
      ImmutableMap.<String, String>builder()
          // Images
          .put("image/jpeg", ".jpg")
          .put("image/png", ".png")
          .put("image/gif", ".gif")
          .put("image/webp", ".webp")
          .put("image/bmp", ".bmp")
          .put("image/tiff", ".tiff")
          // Audio
          .put("audio/mpeg", ".mp3")
          .put("audio/ogg", ".ogg")
          .put("audio/wav", ".wav")
          .put("audio/x-wav", ".wav")
          .put("audio/webm", ".webm")
          .put("audio/aac", ".aac")
          .put("audio/midi", ".mid")
          .put("audio/x-m4a", ".m4a")
          // Video
          .put("video/mp4", ".mp4")
          .put("video/mpeg", ".mpeg")
          .put("video/ogg", ".ogv")
          .put("video/webm", ".webm")
          .put("video/avi", ".avi")
          .put("video/x-msvideo", ".avi")
          .put("video/quicktime", ".mov")
          .buildOrThrow();

  private MimeTypeMapper() {}

  /**
   * Returns the file extension (including the dot) for the given MIME type. Returns an empty string
   * if the MIME type is unknown.
   */
  static String getExtension(String mimeType) {
    return MIME_TO_EXT.getOrDefault(mimeType, "");
  }
}
