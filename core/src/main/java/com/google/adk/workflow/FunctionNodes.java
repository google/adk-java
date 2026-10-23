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
 * Creates {@link FunctionNode}s with a type per port. An {@code Object} input port takes its input
 * as it is, a {@code Content} or {@code Event} port reads its own type, a {@code Void} port ignores
 * its input, and Jackson reads any other type; every output is written in JSON form.
 */
@Experimental
public final class FunctionNodes {

  /**
   * Returns a node named {@code name} that runs {@code body} on each input read as {@code
   * inputType} and writes its {@code outputType} output in JSON form.
   */
  public static <I, O> FunctionNode<I, O> of(
      String name, Class<I> inputType, Class<O> outputType, NodeBody<I, O> body) {
    return builder(inputType, outputType).name(name).body(body).build();
  }

  /**
   * Returns a builder for a node with these port types, for properties {@link #of} does not take.
   */
  public static <I, O> FunctionNode.Builder<I, O> builder(Class<I> inputType, Class<O> outputType) {
    return new FunctionNode.Builder<>(PortCodec.of(inputType), PortCodec.of(outputType));
  }

  private FunctionNodes() {}
}
