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
 * Exception thrown when transcription operations fail.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class TranscriptionException extends Exception {

  public TranscriptionException(String message) {
    super(message);
  }

  public TranscriptionException(String message, Throwable cause) {
    super(message, cause);
  }

  public TranscriptionException(Throwable cause) {
    super(cause);
  }
}
