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

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import org.jspecify.annotations.Nullable;

/**
 * A predicate that decides for each input whether a {@link FunctionNode} run asks the user to
 * approve it, set with {@link FunctionNode.Builder#requireConfirmation(ConfirmationPredicate)}. It
 * runs before the body, and an exception it throws fails the run.
 */
@Experimental
@FunctionalInterface
public interface ConfirmationPredicate<I> {

  /**
   * Returns whether this run asks the user to approve it before the body runs. {@code input} is
   * null when the predecessor sent no output or the port is {@code Void}.
   */
  boolean test(Context context, @Nullable I input) throws Exception;
}
