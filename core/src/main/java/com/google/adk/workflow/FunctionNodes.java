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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.google.adk.annotations.Experimental;

/**
 * Creates {@link FunctionNode}s with a type per port, as a {@code Class} or, for a generic type
 * such as {@code List<Order>}, a {@code TypeReference}. An {@code Object} input port takes its
 * input as it is, a {@code Content} or {@code Event} port reads its own type, a {@code Void} port
 * ignores its input, and Jackson reads any other type; every output is written in JSON form.
 */
@Experimental
public final class FunctionNodes {

  private static final String BUILDER = "a FunctionNodes.builder node";

  /**
   * Returns a node named {@code name} that runs {@code body} on each input read as {@code
   * inputType} and writes its {@code outputType} output in JSON form.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write {@code inputType} or
   *     write {@code outputType}, or if a port type has no single concrete JSON shape; a field type
   *     Jackson cannot handle fails on the first value instead
   */
  public static <I, O> FunctionNode<I, O> of(
      String name, Class<I> inputType, Class<O> outputType, NodeBody<I, O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /** Like {@link #of(String, Class, Class, NodeBody)}, for generic port types. */
  public static <I, O> FunctionNode<I, O> of(
      String name, TypeReference<I> inputType, TypeReference<O> outputType, NodeBody<I, O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /** Like {@link #of(String, Class, Class, NodeBody)}, for a generic output type. */
  public static <I, O> FunctionNode<I, O> of(
      String name, Class<I> inputType, TypeReference<O> outputType, NodeBody<I, O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /** Like {@link #of(String, Class, Class, NodeBody)}, for a generic input type. */
  public static <I, O> FunctionNode<I, O> of(
      String name, TypeReference<I> inputType, Class<O> outputType, NodeBody<I, O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /**
   * Returns a builder for a node with these port types, for properties {@link #of} does not take.
   *
   * @throws WorkflowConfigurationException as {@link #of} does
   */
  public static <I, O> FunctionNode.Builder<I, O> builder(Class<I> inputType, Class<O> outputType) {
    return builder(PortCodec.javaType(inputType), PortCodec.javaType(outputType), BUILDER);
  }

  /** Like {@link #builder(Class, Class)}, for generic port types. */
  public static <I, O> FunctionNode.Builder<I, O> builder(
      TypeReference<I> inputType, TypeReference<O> outputType) {
    return builder(PortCodec.javaType(inputType), PortCodec.javaType(outputType), BUILDER);
  }

  /** Like {@link #builder(Class, Class)}, for a generic output type. */
  public static <I, O> FunctionNode.Builder<I, O> builder(
      Class<I> inputType, TypeReference<O> outputType) {
    return builder(PortCodec.javaType(inputType), PortCodec.javaType(outputType), BUILDER);
  }

  /** Like {@link #builder(Class, Class)}, for a generic input type. */
  public static <I, O> FunctionNode.Builder<I, O> builder(
      TypeReference<I> inputType, Class<O> outputType) {
    return builder(PortCodec.javaType(inputType), PortCodec.javaType(outputType), BUILDER);
  }

  private static <I, O> FunctionNode<I, O> node(
      String name, JavaType inputType, JavaType outputType, NodeBody<I, O> body) {
    return FunctionNodes.<I, O>builder(inputType, outputType, "node '" + name + "'")
        .name(name)
        .body(body)
        .build();
  }

  private static <I, O> FunctionNode.Builder<I, O> builder(
      JavaType inputType, JavaType outputType, String node) {
    return new FunctionNode.Builder<>(
        PortCodec.input(inputType, "the input of " + node),
        PortCodec.output(outputType, "the output of " + node));
  }

  private FunctionNodes() {}
}
