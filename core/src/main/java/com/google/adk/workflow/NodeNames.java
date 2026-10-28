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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.annotations.Experimental;
import com.google.common.base.CharMatcher;

/** The rules a node name follows so that the node paths and branches built from it stay intact. */
@Experimental
final class NodeNames {

  // '/' and '@' form node-path segments and '.' separates branch segments, so disallow all three.
  static final CharMatcher PATH_SEPARATORS = CharMatcher.anyOf("/@.");

  private NodeNames() {}

  /**
   * Throws {@link IllegalArgumentException} if {@code name} is empty or contains {@code /},
   * {@code @}, or {@code .}.
   */
  static void validate(String name) {
    checkArgument(!name.isEmpty(), "A node name must not be empty.");
    checkArgument(
        PATH_SEPARATORS.matchesNoneOf(name),
        "A node name must not contain '/', '@', or '.', which separate node paths and branches,"
            + " but '%s' does.",
        name);
  }
}
