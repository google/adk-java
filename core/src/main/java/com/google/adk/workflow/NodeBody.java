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

/**
 * The body of a {@link FunctionNode}, which takes the node run's context and its single input; the
 * input may be shared with other nodes, so the body must not mutate it. It runs on the {@code
 * Runner}'s scheduler, so it may block, and a small fixed-size scheduler can deadlock bodies that
 * wait for one another.
 */
@Experimental
@FunctionalInterface
public interface NodeBody<I, O> {

  /**
   * Returns {@code null} for no output, an {@code Event} to send with any output in JSON form, or
   * the output, which is sent in its JSON form; a returned {@code Content} is the output, not a
   * message.
   */
  O apply(Context context, I input) throws Exception;
}
