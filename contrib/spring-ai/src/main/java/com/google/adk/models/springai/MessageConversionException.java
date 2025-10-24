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
package com.google.adk.models.springai;

/**
 * Exception thrown when message conversion between ADK and Spring AI formats fails.
 *
 * <p>This exception is thrown when there are issues converting between ADK's Content/Part format
 * and Spring AI's Message/ChatResponse format, such as JSON parsing errors, invalid message
 * structures, or unsupported content types.
 */
public class MessageConversionException extends RuntimeException {

  /**
   * Constructs a new MessageConversionException with the specified detail message.
   *
   * @param message the detail message
   */
  public MessageConversionException(String message) {
    super(message);
  }

  /**
   * Constructs a new MessageConversionException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public MessageConversionException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Constructs a new MessageConversionException with the specified cause.
   *
   * @param cause the cause of the exception
   */
  public MessageConversionException(Throwable cause) {
    super(cause);
  }

  /**
   * Creates a MessageConversionException for JSON parsing failures.
   *
   * @param context the context where the parsing failed (e.g., "tool call arguments")
   * @param cause the underlying JSON processing exception
   * @return a new MessageConversionException with appropriate message
   */
  public static MessageConversionException jsonParsingFailed(String context, Throwable cause) {
    return new MessageConversionException(
        String.format("Failed to parse JSON for %s", context), cause);
  }

  /**
   * Creates a MessageConversionException for invalid message structure.
   *
   * @param message description of the invalid structure
   * @return a new MessageConversionException
   */
  public static MessageConversionException invalidMessageStructure(String message) {
    return new MessageConversionException("Invalid message structure: " + message);
  }

  /**
   * Creates a MessageConversionException for unsupported content type.
   *
   * @param contentType the unsupported content type
   * @return a new MessageConversionException
   */
  public static MessageConversionException unsupportedContentType(String contentType) {
    return new MessageConversionException("Unsupported content type: " + contentType);
  }
}
