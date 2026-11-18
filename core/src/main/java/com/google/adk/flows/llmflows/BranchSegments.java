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

package com.google.adk.flows.llmflows;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Reads the {@code <name>@<run id>} segments of dot-joined branches. */
final class BranchSegments {

  private BranchSegments() {}

  /** Returns the segments of {@code branch} below {@code base}, or none when it is not below. */
  static ImmutableList<String> below(@Nullable String branch, @Nullable String base) {
    String rest = Strings.nullToEmpty(branch);
    String prefix = Strings.nullToEmpty(base);
    if (!prefix.isEmpty()) {
      if (!rest.startsWith(prefix + ".")) {
        return ImmutableList.of();
      }
      rest = rest.substring(prefix.length() + 1);
    }
    return rest.isEmpty() ? ImmutableList.of() : ImmutableList.copyOf(Splitter.on('.').split(rest));
  }

  /** Returns the name of a {@code <name>@<run id>} segment, or empty when it lacks either part. */
  static Optional<String> name(String segment) {
    int at = segment.lastIndexOf('@');
    return at > 0 && at < segment.length() - 1
        ? Optional.of(segment.substring(0, at))
        : Optional.empty();
  }

  /**
   * Returns the run ID of a {@code <name>@<run id>} segment, or empty when it lacks either part.
   */
  static Optional<String> runId(String segment) {
    int at = segment.lastIndexOf('@');
    return at > 0 && at < segment.length() - 1
        ? Optional.of(segment.substring(at + 1))
        : Optional.empty();
  }
}
