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
 * The body of a {@link FunctionNode}, which takes the node run's context and its single input; the
 * input may be shared with other nodes, so the body must not mutate it. It runs on the node's
 * scheduler ({@link FunctionNode.Builder#scheduler}, {@code Schedulers.io()} by default), so it may
 * block; a scheduler set there needs a free thread for every body that waits, and an inline or
 * small fixed-size one can hang the run. Canceling the node interrupts the body's thread and drops
 * its result; a body that ignores the interrupt keeps its thread until it returns, and a retry can
 * overlap it.
 */
@Experimental
@FunctionalInterface
public interface NodeBody<I, O> {

  /**
   * Returns {@code null} for no output, an {@code Event} to send with any output in JSON form, or
   * the output, which is sent in its JSON form; a returned {@code Content} is the output, not a
   * message. {@code input} is null when the predecessor sent no output or the port is {@code Void};
   * a primitive port fails with {@link NodeInputValidationException} instead. A body that asks for
   * input is a {@link FlowableNodeBody} that emits a {@code RequestInput}.
   */
  @Nullable O apply(Context context, @Nullable I input) throws Exception;
}
