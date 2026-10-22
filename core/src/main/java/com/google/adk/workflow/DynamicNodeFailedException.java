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

package com.google.adk.workflow;

import com.google.adk.annotations.Experimental;

/**
 * An exception that {@link NodeRunner#runChild} signals when the child node fails. If the caller
 * does not handle this exception, the caller fails with the child's error without emitting an error
 * event of its own.
 */
@Experimental
final class DynamicNodeFailedException extends RuntimeException {

  private final String errorNodePath;

  DynamicNodeFailedException(String nodeName, Exception error, String errorNodePath) {
    super("Dynamic node '" + nodeName + "' failed.", error);
    this.errorNodePath = errorNodePath;
  }

  /** Returns the exception the child failed with, which is also this exception's cause. */
  public Exception error() {
    return (Exception) getCause();
  }

  /**
   * Returns the path of the node that raised {@link #error()}, which is either the child or one of
   * its descendants.
   */
  public String errorNodePath() {
    return errorNodePath;
  }
}
