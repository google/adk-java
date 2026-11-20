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

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.google.adk.annotations.Experimental;
import com.google.genai.types.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Creates {@link FunctionNode}s with a type per port, as a {@code Class} or, for a generic type
 * such as {@code List<Order>}, a {@code TypeReference}, read and written as {@link FunctionNode}
 * describes. Port values also follow these rules:
 *
 * <ul>
 *   <li>A primitive or boxed number or boolean, also in a primitive array other than {@code byte[]}
 *       (base64) or as a map key, accepts only its own JSON type or the same value quoted ({@code
 *       "3"}). An integer also accepts a whole number such as {@code 3.0}, and a char only one
 *       character of text.
 *   <li>An enum or boolean reads a number only where Jackson writes it as one.
 *   <li>A port of {@code Event} or of a {@code com.google.genai.types} type such as {@code
 *       Content}, or of a list, map or array of them, rejects unknown keys anywhere in its value.
 *       Other ports drop them.
 * </ul>
 *
 * <p>A node's body asks the user for input with {@link #requestInput}.
 */
@Experimental
public final class FunctionNodes {

  private static final String BUILDER = "a FunctionNodes.builder node";

  /**
   * Returns a node named {@code name} that runs {@code body} on each input read as {@code
   * inputType} and writes its {@code outputType} output in JSON form. The body may send events
   * first with {@link com.google.adk.agents.Context#emit}.
   *
   * @throws WorkflowConfigurationException if Jackson cannot read and write {@code inputType} or
   *     write {@code outputType}, or if a port type has no single concrete JSON shape or is an
   *     interface or abstract class without a Jackson creator, such as {@code ZoneId}; a field type
   *     Jackson cannot handle fails on the first value instead
   */
  public static <I, O> FunctionNode<I, O> of(
      String name, Class<I> inputType, Class<O> outputType, NodeBody<? super I, ? extends O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /** Like {@link #of(String, Class, Class, NodeBody)}, for generic port types. */
  public static <I, O> FunctionNode<I, O> of(
      String name,
      TypeReference<I> inputType,
      TypeReference<O> outputType,
      NodeBody<? super I, ? extends O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /** Like {@link #of(String, Class, Class, NodeBody)}, for a generic output type. */
  public static <I, O> FunctionNode<I, O> of(
      String name,
      Class<I> inputType,
      TypeReference<O> outputType,
      NodeBody<? super I, ? extends O> body) {
    return node(name, PortCodec.javaType(inputType), PortCodec.javaType(outputType), body);
  }

  /** Like {@link #of(String, Class, Class, NodeBody)}, for a generic input type. */
  public static <I, O> FunctionNode<I, O> of(
      String name,
      TypeReference<I> inputType,
      Class<O> outputType,
      NodeBody<? super I, ? extends O> body) {
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

  private static <I, O> FunctionNode.Builder<I, O> builder(
      JavaType inputType, JavaType outputType, String node) {
    return new FunctionNode.Builder<>(
        PortCodec.input(inputType, "the input of " + node),
        PortCodec.output(outputType, "the output of " + node));
  }

  /**
   * Asks the user for input; it never returns. Unless the node reruns on resume, the answer,
   * checked against any {@code responseSchema}, becomes the node's output without being read as its
   * output type; if it reruns, the body runs again and must ask only when {@code
   * ctx.resumeInputs()} lacks the answer:
   *
   * <pre>{@code
   * if (!ctx.resumeInputs().containsKey("q")) {
   *   return FunctionNodes.requestInput("q", "How many?");
   * }
   * Object answer = ctx.resumeInputs().get("q");
   * }</pre>
   *
   * <p>Call it on the body's thread: it ends the body by throwing an internal {@link Error}, which
   * {@code catch (Exception e)} lets through and {@code catch (Throwable t)} stops.
   *
   * @param interruptId the ID the answer carries; keep it stable across reruns
   * @param message the text to show the user, or {@code null}
   * @param payload data for rendering the request, written in JSON form as a node output is, or
   *     {@code null}
   * @param responseSchema the schema the answer must match, or {@code null}
   * @throws IllegalArgumentException if the payload has no JSON form
   */
  @SuppressWarnings("TypeParameterUnusedInFormals") // T only lets a body return the call.
  public static <T> T requestInput(
      String interruptId,
      @Nullable String message,
      @Nullable Object payload,
      @Nullable Schema responseSchema) {
    return requestInput(
        RequestInput.builder()
            .interruptId(interruptId)
            .message(message)
            .payload(payload)
            .responseSchema(responseSchema)
            .build());
  }

  /** Like {@link #requestInput(String, String, Object, Schema)}, without a response schema. */
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public static <T> T requestInput(
      String interruptId, @Nullable String message, @Nullable Object payload) {
    return requestInput(interruptId, message, payload, /* responseSchema= */ null);
  }

  /** Like {@link #requestInput(String, String, Object, Schema)}, with only a message. */
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public static <T> T requestInput(String interruptId, @Nullable String message) {
    return requestInput(interruptId, message, /* payload= */ null, /* responseSchema= */ null);
  }

  /** Like {@link #requestInput(String, String, Object, Schema)}, with neither message nor data. */
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public static <T> T requestInput(String interruptId) {
    return requestInput(
        interruptId, /* message= */ null, /* payload= */ null, /* responseSchema= */ null);
  }

  /** Like {@link #requestInput(String, String, Object, Schema)}, with a random interrupt ID. */
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public static <T> T requestInput() {
    return requestInput(RequestInput.builder().build());
  }

  /** Like {@link #requestInput(String, String, Object, Schema)}, for a built {@code request}. */
  @SuppressWarnings("TypeParameterUnusedInFormals")
  public static <T> T requestInput(RequestInput request) {
    Object payload = checkNotNull(request).payload().orElse(null);
    throw new NodeInterruptedException(
        payload == null
            ? request
            : request.toBuilder()
                .payload(FunctionNode.ANY.encode(payload, "payload of requestInput"))
                .build());
  }

  private static <I, O> FunctionNode<I, O> node(
      String name, JavaType inputType, JavaType outputType, NodeBody<? super I, ? extends O> body) {
    return FunctionNodes.<I, O>builder(inputType, outputType, "node '" + name + "'")
        .name(name)
        .body(body)
        .build();
  }

  private FunctionNodes() {}
}
