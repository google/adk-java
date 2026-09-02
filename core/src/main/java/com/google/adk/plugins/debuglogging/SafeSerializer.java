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

package com.google.adk.plugins.debuglogging;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Collections.newSetFromMap;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Converts an arbitrary value into something a YAML writer can render.
 *
 * <p>Port of adk-python's {@code DebugLoggingPlugin._safe_serialize}. Tool arguments and tool
 * results are declared {@code Map<String, Object>}, so the values inside them are whatever a tool
 * author chose to return — this class is the guarantee that a debug plugin never throws while
 * trying to describe them.
 *
 * <p>Deliberate differences from the original:
 *
 * <ul>
 *   <li>A {@code null} or empty input yields an empty map rather than a null, so nothing downstream
 *       has to null-check. Fields that should disappear when empty say so with
 *       {@code @JsonInclude(NON_EMPTY)} — which is also how pydantic's {@code exclude_none=True} is
 *       reproduced, without a stripping pass.
 *   <li>A {@code null} <em>inside</em> a list or map becomes {@link #NULL_MARKER}, because Guava's
 *       immutable collections reject null elements. The position is preserved, which is what
 *       matters when reading a trace.
 *   <li><b>Cycles are detected</b>, so a self-referential tool result cannot overflow the stack.
 *       adk-java's own {@code JsonFormatter} keeps an identity set for the same reason; it is
 *       package-private to its own package, so the guard is reimplemented here rather than shared.
 * </ul>
 */
final class SafeSerializer {

  private static final String BYTES_MARKER = "<bytes: %d bytes>";
  private static final String NULL_MARKER = "<null>";
  private static final String CYCLE_MARKER = "<cycle detected>";
  private static final String UNSERIALIZABLE_MARKER = "<unserializable>";

  private SafeSerializer() {}

  /**
   * The YAML-safe form of a map whose outer shape ADK or genai already fixes as {@code Map<String,
   * Object>} — tool arguments, tool results, a state delta, session state.
   *
   * <p>The only entry point. Callers that must omit the key when the map is empty declare
   * {@code @JsonInclude(NON_EMPTY)} on the field and let Jackson decide.
   *
   * <p>Only the map's <em>values</em> stay {@code Object}, and only because the declaring interface
   * says they are.
   */
  static ImmutableMap<String, Object> serializeMap(Map<String, Object> values) {
    if (values.isEmpty()) {
      return ImmutableMap.of();
    }
    Set<Object> ancestors = newSetFromMap(new IdentityHashMap<>());
    ancestors.add(values);
    return mapEntries(values, ancestors);
  }

  private static Object serializeNonNull(Object value, Set<Object> ancestors) {
    if (isScalar(value)) {
      return value;
    }
    if (value instanceof byte[] bytes) {
      return BYTES_MARKER.formatted(bytes.length);
    }
    if (!isContainer(value)) {
      return describe(value);
    }
    return serializeGuarded(value, ancestors);
  }

  /**
   * Descends into a container only if it is not already an ancestor of itself.
   *
   * <p>Membership is removed on the way out, so a value legitimately appearing twice in a
   * <em>tree</em> is serialized twice — only a genuine cycle is cut.
   */
  private static Object serializeGuarded(Object container, Set<Object> ancestors) {
    if (!ancestors.add(container)) {
      return CYCLE_MARKER;
    }
    try {
      return serializeContainer(container, ancestors);
    } finally {
      ancestors.remove(container);
    }
  }

  private static Object serializeContainer(Object container, Set<Object> ancestors) {
    if (container instanceof Map<?, ?> map) {
      return mapEntries(map, ancestors);
    }
    if (container instanceof Collection<?> collection) {
      return serializeCollection(collection, ancestors);
    }
    return serializeCollection(Arrays.asList((Object[]) container), ancestors);
  }

  private static ImmutableList<Object> serializeCollection(
      Collection<?> values, Set<Object> ancestors) {
    return values.stream()
        .map(element -> serializeElement(element, ancestors))
        .collect(toImmutableList());
  }

  private static ImmutableMap<String, Object> mapEntries(Map<?, ?> values, Set<Object> ancestors) {
    ImmutableMap.Builder<String, Object> entries = ImmutableMap.builder();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      entries.put(String.valueOf(entry.getKey()), serializeElement(entry.getValue(), ancestors));
    }
    return entries.buildKeepingLast();
  }

  /** A nested value, where absence has to be represented rather than omitted. */
  private static Object serializeElement(Object value, Set<Object> ancestors) {
    return value == null ? NULL_MARKER : serializeNonNull(value, ancestors);
  }

  private static boolean isScalar(Object value) {
    return value instanceof String || value instanceof Number || value instanceof Boolean;
  }

  private static boolean isContainer(Object value) {
    return value instanceof Map || value instanceof Collection || value instanceof Object[];
  }

  /** Last resort, mirroring the original's {@code str(obj)} with its exception guard. */
  private static Object describe(Object value) {
    try {
      return String.valueOf(value);
    } catch (RuntimeException e) {
      return UNSERIALIZABLE_MARKER;
    }
  }
}
