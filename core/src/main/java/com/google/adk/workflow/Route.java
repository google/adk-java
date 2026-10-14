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
import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.adk.annotations.Experimental;

/**
 * A value on a workflow edge, and the value a node emits to select which of its outgoing edges are
 * followed. An edge fires when the value it carries matches one the node emitted.
 *
 * <p>On the wire a route is a bare scalar (a string, integer or boolean), and {@link Default} is
 * the {@link #DEFAULT_ROUTE_SENTINEL} string.
 */
@Experimental
public sealed interface Route permits Route.Tag, Route.Num, Route.Flag, Route.Default {

  /** The string that stands for {@link Default} on the wire. */
  String DEFAULT_ROUTE_SENTINEL = "__DEFAULT__";

  /**
   * A route identified by a string, which is the common case. Its value cannot be {@link
   * #DEFAULT_ROUTE_SENTINEL}; use {@link Default} for the default route.
   */
  record Tag(@JsonValue String value) implements Route {
    public Tag {
      checkNotNull(value);
      checkArgument(
          !value.equals(DEFAULT_ROUTE_SENTINEL),
          "Use Route.Default.INSTANCE for the default route.");
    }
  }

  /** A route identified by an integer. */
  record Num(@JsonValue long value) implements Route {}

  /** A route identified by a boolean, for a two-way branch. */
  record Flag(@JsonValue boolean value) implements Route {}

  /**
   * The fallback edge, followed when no other routed edge of the node matches what it emitted,
   * including when it emitted no route. A node may declare several, which all fire together, but a
   * default cannot share an edge with a concrete route.
   */
  enum Default implements Route {
    INSTANCE;

    @JsonValue
    String toJson() {
      return DEFAULT_ROUTE_SENTINEL;
    }
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  private static Route fromJson(Object value) {
    if (value instanceof String text) {
      return text.equals(DEFAULT_ROUTE_SENTINEL) ? Default.INSTANCE : new Tag(text);
    }
    if (value instanceof Integer || value instanceof Long) {
      return new Num(((Number) value).longValue());
    }
    if (value instanceof Boolean flag) {
      return new Flag(flag);
    }
    throw new IllegalArgumentException("A route must be a string, an integer or a boolean.");
  }
}
