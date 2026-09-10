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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Pins the guarantee {@link SafeSerializer} exists to make: a debug plugin describing an arbitrary
 * tool result never throws, and never puts a null into an immutable collection.
 *
 * <p>Everything is exercised through {@code serializeMap}, the class's only entry point, so the
 * tests cover what production actually calls rather than a convenience overload.
 */
@RunWith(JUnit4.class)
public class SafeSerializerTest {

  private static final String KEY = "v";

  /** Serializes {@code raw} as a map value and hands back what the trace would show for it. */
  private static Object valueOf(Object raw) {
    Map<String, Object> holder = new HashMap<>();
    holder.put(KEY, raw);
    return SafeSerializer.serializeMap(holder).get(KEY);
  }

  @Test
  public void serializeMap_empty_isEmptyNotNull() {
    assertThat(SafeSerializer.serializeMap(new HashMap<>())).isEmpty();
  }

  @Test
  public void serializeMap_nullValue_becomesTheNullMarker() {
    assertThat(valueOf(null)).isEqualTo("<null>");
  }

  @Test
  public void serializeMap_primitives_passThrough() {
    assertThat(valueOf("text")).isEqualTo("text");
    assertThat(valueOf(42)).isEqualTo(42);
    assertThat(valueOf(1.5)).isEqualTo(1.5);
    assertThat(valueOf(true)).isEqualTo(true);
  }

  @Test
  public void serializeMap_bytes_recordLengthNotContent() {
    assertThat(valueOf(new byte[] {1, 2, 3, 4, 5})).isEqualTo("<bytes: 5 bytes>");
  }

  @Test
  public void serializeMap_nestedStructures_areRecursivelyImmutable() {
    Map<String, Object> nested = new HashMap<>();
    nested.put("inner", Arrays.asList("a", "b"));

    Object serialized = valueOf(nested);

    assertThat(serialized).isInstanceOf(ImmutableMap.class);
    assertThat(serialized).isEqualTo(ImmutableMap.of("inner", ImmutableList.of("a", "b")));
  }

  @Test
  public void serializeMap_nullInsideList_becomesMarkerAndKeepsPosition() {
    assertThat(valueOf(Arrays.asList("a", null, "c")))
        .isEqualTo(ImmutableList.of("a", "<null>", "c"));
  }

  @Test
  public void serializeMap_array_isTreatedAsList() {
    assertThat(valueOf(new Object[] {"a", null})).isEqualTo(ImmutableList.of("a", "<null>"));
  }

  @Test
  public void serializeMap_arbitraryObject_fallsBackToItsDescription() {
    assertThat(valueOf(Optional.of("x"))).isEqualTo("Optional[x]");
  }

  @Test
  public void serializeMap_objectWhoseToStringThrows_yieldsMarkerInsteadOfPropagating() {
    assertThat(valueOf(new ThrowingToString())).isEqualTo("<unserializable>");
  }

  /**
   * Without the identity guard this input recurses until the stack overflows. adk-java's own {@code
   * JsonFormatter} keeps an identity set for the same reason.
   */
  @Test
  public void serializeMap_selfReferentialMap_reportsACycleInsteadOfOverflowing() {
    Map<String, Object> cyclic = new HashMap<>();
    cyclic.put("name", "root");
    cyclic.put("self", cyclic);

    assertThat(SafeSerializer.serializeMap(cyclic))
        .isEqualTo(ImmutableMap.of("name", "root", "self", "<cycle detected>"));
  }

  @Test
  public void serializeMap_cycleThroughAList_isAlsoCut() {
    List<Object> cyclic = new ArrayList<>();
    cyclic.add("first");
    cyclic.add(cyclic);

    assertThat(valueOf(cyclic)).isEqualTo(ImmutableList.of("first", "<cycle detected>"));
  }

  /** A value appearing twice in a tree is not a cycle — the guard must not over-trigger. */
  @Test
  public void serializeMap_sameValueTwiceInATree_isSerializedTwice() {
    Map<String, Object> shared = new HashMap<>();
    shared.put("k", "v");

    ImmutableMap<String, Object> expected = ImmutableMap.of("k", "v");
    assertThat(valueOf(Arrays.asList(shared, shared)))
        .isEqualTo(ImmutableList.of(expected, expected));
  }

  /** A tool result value that misbehaves exactly where the original's {@code str(obj)} would. */
  private static final class ThrowingToString {
    @Override
    public String toString() {
      throw new IllegalStateException("boom");
    }
  }
}
