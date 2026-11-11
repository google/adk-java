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
import io.reactivex.rxjava3.core.Flowable;
import org.jspecify.annotations.Nullable;

/**
 * The body of a {@link FunctionNode} that sends several items, or runs asynchronously, as a {@link
 * Flowable}. The node calls it and subscribes on the calling thread, ignoring {@link
 * FunctionNode.Builder#scheduler} and the {@code Runner}'s scheduler, so it must not block;
 * canceling the node disposes the {@code Flowable}. A {@code Maybe} or {@code Single} becomes a
 * body with {@code toFlowable()}, as in {@code (ctx, order) -> lookUp(order).toFlowable()}.
 */
@Experimental
@FunctionalInterface
public interface FlowableNodeBody<I> {

  /**
   * Returns the items to send: events, a {@code RequestInput} to ask for input, and at most one
   * output, which the node writes as its output type; a second output fails the node. An event's
   * output counts as the node's one output and is checked against the output type like a returned
   * item, unless that type is {@code Event}. Without {@code rerunOnResume}, a {@code
   * RequestInput}'s answer becomes the output, checked against the output type, and a {@code Void}
   * node outputs nothing.
   *
   * <p>A {@code Content} item is the output, not a message: to show a message and use it as the
   * output, emit it as a plain event, then return the same {@code Content}; an event marked
   * message-as-output fails the node. {@code input} is null when the predecessor sent no output or
   * the port is {@code Void}, and a primitive port fails with {@link NodeInputValidationException}
   * instead. Write state in {@code apply} before returning, or on an event's {@code
   * actions().stateDelta()}, as a write made later may be sent with an earlier event.
   */
  Flowable<?> apply(Context context, @Nullable I input) throws Exception;
}
