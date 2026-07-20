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

package com.google.adk.models.sarvamai;

import java.util.Optional;

/**
 * Domain exception for Sarvam AI API errors. Carries structured error information from the API
 * response for programmatic error handling.
 *
 * @author Sandeep Belgavi
 */
public class SarvamAiException extends RuntimeException {

  private final int statusCode;
  private final String errorCode;
  private final String requestId;

  public SarvamAiException(String message, int statusCode, String errorCode, String requestId) {
    super(message);
    this.statusCode = statusCode;
    this.errorCode = errorCode;
    this.requestId = requestId;
  }

  public SarvamAiException(String message, Throwable cause) {
    super(message, cause);
    this.statusCode = 0;
    this.errorCode = null;
    this.requestId = null;
  }

  public SarvamAiException(String message) {
    super(message);
    this.statusCode = 0;
    this.errorCode = null;
    this.requestId = null;
  }

  public int statusCode() {
    return statusCode;
  }

  public Optional<String> errorCode() {
    return Optional.ofNullable(errorCode);
  }

  public Optional<String> requestId() {
    return Optional.ofNullable(requestId);
  }

  public boolean isRetryable() {
    return statusCode == 429 || statusCode == 503 || statusCode >= 500;
  }
}
