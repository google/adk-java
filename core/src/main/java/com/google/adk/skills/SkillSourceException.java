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

package com.google.adk.skills;

/**
 * Exception for {@link SkillSource} implementations to signal recoverable errors that will have the
 * message sending back to the LLM.
 */
public final class SkillSourceException extends Exception {

  public static final String SKILL_LOAD_ERROR = "SKILL_LOAD_ERROR";
  public static final String SKILL_NOT_FOUND = "SKILL_NOT_FOUND";
  public static final String SKILL_FORMAT_ERROR = "SKILL_FORMAT_ERROR";
  public static final String RESOURCE_LOAD_ERROR = "RESOURCE_LOAD_ERROR";
  public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

  private final String errorCode;

  /**
   * Constructs a new exception with the specified detail message and error code.
   *
   * @param message The detail message.
   * @param errorCode The specific error code categorizing the failure.
   */
  public SkillSourceException(String message, String errorCode) {
    super(message);
    this.errorCode = errorCode;
  }

  /**
   * Constructs a new exception with the specified detail message, error code, and cause.
   *
   * @param message The detail message.
   * @param errorCode The specific error code categorizing the failure.
   * @param cause The cause.
   */
  public SkillSourceException(String message, String errorCode, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /**
   * Returns the error code categorizing the failure.
   *
   * @return The error code string.
   */
  public String getErrorCode() {
    return errorCode;
  }
}
