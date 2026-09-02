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

package com.google.adk.reasoning;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;

/** Shared helpers for the LLM-backed judge and extractor. */
final class LlmJsonSupport {

  private LlmJsonSupport() {}

  /** Joins the text of all parts of the response's content (empty string if none). */
  static String extractText(LlmResponse response) {
    StringBuilder sb = new StringBuilder();
    response
        .content()
        .flatMap(Content::parts)
        .ifPresent(parts -> parts.forEach(part -> part.text().ifPresent(sb::append)));
    return sb.toString();
  }

  /**
   * Strips a leading ```...```/```json fence (and trailing ```), tolerating models that wrap JSON.
   */
  static String stripCodeFence(String text) {
    String t = text.strip();
    if (t.startsWith("```")) {
      int firstNewline = t.indexOf('\n');
      if (firstNewline >= 0) {
        t = t.substring(firstNewline + 1);
      }
      if (t.endsWith("```")) {
        t = t.substring(0, t.length() - 3);
      }
    }
    return t.strip();
  }
}
