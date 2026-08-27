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

package com.google.adk.models.chat;

import java.io.IOException;

/**
 * Thrown by {@link ChatCompletionsHttpClient} when the server returns a non-successful HTTP status
 * code. Extends {@link IOException} so existing {@code catch (IOException)} call sites continue to
 * work without modification. Callers that need to branch on the HTTP status (e.g. to apply bounded
 * retry/backoff on 429) can catch this subtype and call {@link #statusCode()} directly, without
 * parsing {@link Throwable#getMessage()}.
 */
public final class ChatCompletionsHttpException extends IOException {
  private final int statusCode;
  private final String responseBody;

  public ChatCompletionsHttpException(int statusCode, String message, String responseBody) {
    super("HTTP request failed with status: " + message + " - body: " + responseBody);
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  public int statusCode() {
    return statusCode;
  }

  public String responseBody() {
    return responseBody;
  }
}
