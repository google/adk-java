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

/**
 * Signals a non-2xx, non-404 HTTP status from the Vertex AI Session API. Extends {@link
 * SessionException} so existing {@code catch (SessionException)} call sites still work.
 */
public final class VertexAiApiException extends SessionException {
  private final int statusCode;

  VertexAiApiException(int statusCode, String responseBody) {
    super(
        "Vertex AI Session API request failed with HTTP status "
            + statusCode
            + (responseBody == null || responseBody.isEmpty() ? "" : ": " + responseBody));
    this.statusCode = statusCode;
  }

  /** Returns the HTTP status code returned by the API. */
  public int statusCode() {
    return statusCode;
  }
}
