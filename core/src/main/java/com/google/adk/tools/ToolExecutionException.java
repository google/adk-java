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

package com.google.adk.tools;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Indicates that a tool failed while it was running.
 *
 * <p>A failure carries two things. The message is a sentence a user and a model both end up
 * reading, so it should name the tool and say what went wrong. The error type beside it is the
 * machine-readable half: it lets a caller tell a timeout apart from a refusal without parsing the
 * prose, and it is what fills the {@code error.type} span attribute. Classifying a failure is
 * optional, and one that was not classified reports {@link Optional#empty()}.
 *
 * <p>No constructor accepts a null error type. A failure with nothing to say about how it failed
 * uses {@link #ToolExecutionException(String)} or {@link #ToolExecutionException(String,
 * Throwable)} instead.
 *
 * <p>This is unchecked, so a tool body can fail inside an RxJava operator and reach the subscriber
 * intact.
 */
public class ToolExecutionException extends RuntimeException {

  private final @Nullable ToolErrorType errorType;

  /** Reports a failure with no classification. */
  public ToolExecutionException(String message) {
    super(message);
    this.errorType = null;
  }

  /** Reports a failure with no classification, brought about by {@code cause}. */
  public ToolExecutionException(String message, Throwable cause) {
    super(message, cause);
    this.errorType = null;
  }

  /** Reports a failure classified as {@code errorType}. */
  public ToolExecutionException(String message, ToolErrorType errorType) {
    super(message);
    this.errorType = errorType;
  }

  /** Reports a failure classified as {@code errorType}, brought about by {@code cause}. */
  public ToolExecutionException(String message, ToolErrorType errorType, Throwable cause) {
    super(message, cause);
    this.errorType = errorType;
  }

  /** How the tool failed, empty if the failure was never classified. */
  public Optional<ToolErrorType> errorType() {
    return Optional.ofNullable(errorType);
  }
}
