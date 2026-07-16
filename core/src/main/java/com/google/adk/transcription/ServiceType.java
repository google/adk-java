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

package com.google.adk.transcription;

/**
 * Enumeration of supported transcription service types.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public enum ServiceType {
  /** Whisper transcription service (hosted). */
  WHISPER("whisper"),

  /** Gemini Live API transcription (future). */
  GEMINI("gemini"),

  /** Azure Speech Services (future). */
  AZURE("azure"),

  /** AWS Transcribe (future). */
  AWS_TRANSCRIBE("aws_transcribe"),

  /** Sarvam AI transcription. */
  SARVAM("sarvam");

  private final String value;

  ServiceType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  /**
   * Creates ServiceType from string value.
   *
   * @param value String value
   * @return ServiceType
   * @throws IllegalArgumentException if value is unknown
   */
  public static ServiceType fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Service type value cannot be null");
    }

    for (ServiceType type : values()) {
      if (type.value.equalsIgnoreCase(value)) {
        return type;
      }
    }

    throw new IllegalArgumentException("Unknown service type: " + value);
  }
}
