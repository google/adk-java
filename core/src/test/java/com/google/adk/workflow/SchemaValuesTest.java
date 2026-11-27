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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link SchemaValues}, which checks values against workflow schemas. */
@RunWith(JUnit4.class)
public final class SchemaValuesTest {

  private static final Schema ANY = Schema.builder().build();
  private static final Schema INTEGER = typed(Type.Known.INTEGER);
  private static final Schema STRING = typed(Type.Known.STRING);
  private static final Schema OBJECT = typed(Type.Known.OBJECT);
  private static final Schema COUNT =
      OBJECT.toBuilder().properties(ImmutableMap.of("count", INTEGER)).build();
  private static final String WHAT = "output of node 'n'";

  /** A value no failure message may quote. */
  private static final String SECRET = "SENTINEL-4711";

  private record Count(int count) {}

  private record Named(String name, @Nullable String nickname) {}

  /** Written as its text, so a null text writes null. */
  private record Label(@JsonValue @Nullable String text) {}

  private enum Tag {
    A
  }

  @Test
  public void check_convertedValue_returnsSessionForm() {
    // Act
    Object checked = SchemaValues.check(new HashMap<>(ImmutableMap.of("count", "3")), COUNT, WHAT);

    // Assert
    assertThat(checked).isEqualTo(ImmutableMap.of("count", 3));
    Map<?, ?> map = (Map<?, ?>) checked;
    assertThat(map.get("count")).isInstanceOf(Integer.class);
    assertThrows(UnsupportedOperationException.class, map::clear);
  }

  @Test
  public void check_mismatch_namesWhatAndPathButNotTheValue() {
    // Act
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaValues.check(ImmutableMap.of("count", SECRET), COUNT, WHAT));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'n' does not match its schema: 'count' is not an"
                + " integer");
    assertThat(error).hasMessageThat().doesNotContain(SECRET);
  }

  @Test
  public void check_event_passesAnUntypedSchemaAsItselfAndFailsAnObjectSchema() {
    // Arrange
    Event event = Event.builder().id("e").author("n").build();

    // Act
    Object checked = SchemaValues.check(event, ANY, WHAT);
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> SchemaValues.check(event, OBJECT, WHAT));

    // Assert
    assertThat(checked).isSameInstanceAs(event);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'n' does not match its schema: the value is not an"
                + " object");
  }

  @Test
  public void check_untypedSchema_keepsJsonValuesAndWritesARecordAsItsMap() {
    // Arrange
    Map<String, Object> json = ImmutableMap.of("n", 3, "tags", ImmutableList.of("a", true));

    // Act
    Object checkedJson = SchemaValues.check(json, ANY, WHAT);
    Object checkedRecord = SchemaValues.check(new Count(3), ANY, WHAT);

    // Assert
    assertThat(checkedJson).isEqualTo(json);
    assertThat(checkedRecord).isEqualTo(ImmutableMap.of("count", 3));
  }

  @Test
  public void check_messageInAMapBesideAnEnumOrRecord_readsAsTheSchemaSays() {
    // Arrange
    Content hi = Content.fromParts(Part.fromText("hi"));
    Schema withTag =
        OBJECT.toBuilder().properties(ImmutableMap.of("msg", STRING, "tag", STRING)).build();
    Schema withCount =
        OBJECT.toBuilder().properties(ImmutableMap.of("msg", STRING, "count", COUNT)).build();

    // Act
    Object besideEnum = SchemaValues.check(ImmutableMap.of("msg", hi, "tag", Tag.A), withTag, WHAT);
    Object besideRecord =
        SchemaValues.check(ImmutableMap.of("msg", hi, "count", new Count(3)), withCount, WHAT);

    // Assert
    assertThat(besideEnum).isEqualTo(ImmutableMap.of("msg", "hi", "tag", "A"));
    assertThat(besideRecord)
        .isEqualTo(ImmutableMap.of("msg", "hi", "count", ImmutableMap.of("count", 3)));
  }

  @Test
  public void check_messageInAListBesideAnEnum_readsAsTheSchemaSays() {
    // Arrange
    Content hi = Content.fromParts(Part.fromText("hi"));
    Schema strings = Schema.builder().type(Type.Known.ARRAY).items(STRING).build();

    // Act
    Object checked = SchemaValues.check(ImmutableList.of(hi, Tag.A), strings, WHAT);

    // Assert
    assertThat(checked).isEqualTo(ImmutableList.of("hi", "A"));
  }

  @Test
  public void check_messageInAMapInAListInAMap_readsAsTheSchemaSays() {
    // Arrange
    Content hi = Content.fromParts(Part.fromText("hi"));
    Schema item =
        OBJECT.toBuilder().properties(ImmutableMap.of("msg", STRING, "tag", STRING)).build();
    Schema outer =
        OBJECT.toBuilder()
            .properties(
                ImmutableMap.of(
                    "items", Schema.builder().type(Type.Known.ARRAY).items(item).build()))
            .build();
    Object value =
        ImmutableMap.of("items", ImmutableList.of(ImmutableMap.of("msg", hi, "tag", Tag.A)));

    // Act
    Object checked = SchemaValues.check(value, outer, WHAT);

    // Assert
    assertThat(checked)
        .isEqualTo(
            ImmutableMap.of("items", ImmutableList.of(ImmutableMap.of("msg", "hi", "tag", "A"))));
  }

  @Test
  public void check_optionalInAMapOrList_readsAsItsValueOrNull() {
    // Arrange
    Schema nullableNick =
        OBJECT.toBuilder()
            .properties(ImmutableMap.of("nick", STRING.toBuilder().nullable(true).build()))
            .build();
    Map<String, @Nullable Object> nullNick = new LinkedHashMap<>();
    nullNick.put("nick", null);

    // Act
    Object inMap =
        SchemaValues.check(ImmutableMap.of("nick", Optional.empty()), nullableNick, WHAT);
    Object inList =
        SchemaValues.check(ImmutableList.of(Optional.empty(), Optional.of(Tag.A)), ANY, WHAT);

    // Assert
    assertThat(inMap).isEqualTo(nullNick);
    assertThat(inList).isEqualTo(Arrays.asList(null, "A"));
  }

  @Test
  public void check_presentOptionalMessageInAMap_readsAsTheSchemaSays() {
    // Arrange
    Schema text = OBJECT.toBuilder().properties(ImmutableMap.of("msg", STRING)).build();
    Object value = ImmutableMap.of("msg", Optional.of(Content.fromParts(Part.fromText("hi"))));

    // Act
    Object checked = SchemaValues.check(value, text, WHAT);

    // Assert
    assertThat(checked).isEqualTo(ImmutableMap.of("msg", "hi"));
  }

  @Test
  public void check_presentOptionalMessageInAList_readsAsTheSchemaSays() {
    // Arrange
    Schema strings = Schema.builder().type(Type.Known.ARRAY).items(STRING).build();
    Object value = ImmutableList.of(Optional.of(Content.fromParts(Part.fromText("hi"))));

    // Act
    Object checked = SchemaValues.check(value, strings, WHAT);

    // Assert
    assertThat(checked).isEqualTo(ImmutableList.of("hi"));
  }

  @Test
  public void check_messageUnderObjectBesideAnEnum_fails() {
    // Arrange
    Schema object = OBJECT.toBuilder().properties(ImmutableMap.of("m", OBJECT)).build();
    Object value = ImmutableMap.of("m", Content.fromParts(Part.fromText("hi")), "t", Tag.A);

    // Act
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> SchemaValues.check(value, object, WHAT));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'n' does not match its schema: 'm' is not an"
                + " object");
  }

  @Test
  public void check_partThatWritesNullInAMap_readsAsNull() {
    // Arrange
    Map<String, @Nullable Object> nulls = new LinkedHashMap<>();
    nulls.put("n", null);
    nulls.put("label", null);

    // Act
    Object checked =
        SchemaValues.check(
            ImmutableMap.of("n", OptionalInt.empty(), "label", new Label(null)), ANY, WHAT);

    // Assert
    assertThat(checked).isEqualTo(nulls);
  }

  @Test
  public void check_topLevelValueThatWritesNull_fails() {
    // Act
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaValues.check(OptionalInt.empty(), ANY, WHAT));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .isEqualTo("validation error: " + WHAT + " writes null as java.lang.Object.");
  }

  @Test
  public void check_bigDecimalUnderInteger_passesWholeAndFailsFraction() {
    // Act
    Object whole = SchemaValues.check(new BigDecimal("3"), INTEGER, WHAT);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaValues.check(new BigDecimal("2.5"), INTEGER, WHAT));

    // Assert
    assertThat(whole).isEqualTo(3);
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'n' does not match its schema: the value is not an"
                + " integer");
  }

  @Test
  public void check_recordWithANullField_keepsTheField() {
    // Arrange
    Schema named =
        OBJECT.toBuilder()
            .properties(ImmutableMap.of("name", STRING, "nickname", STRING))
            .required("name")
            .build();
    Map<String, @Nullable Object> withNull = new LinkedHashMap<>();
    withNull.put("name", "Ann");
    withNull.put("nickname", null);

    // Act
    Object untyped = SchemaValues.check(new Named("Ann", null), ANY, WHAT);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaValues.check(new Named("Ann", null), named, WHAT));
    Object withoutKey = SchemaValues.check(ImmutableMap.of("name", "Ann"), named, WHAT);

    // Assert
    assertThat(untyped).isEqualTo(withNull);
    assertThat(error).hasMessageThat().endsWith("'nickname' is null");
    assertThat(withoutKey).isEqualTo(ImmutableMap.of("name", "Ann"));
  }

  @Test
  public void check_mapWithANonStringKey_isWrittenWholeWithTheMessageAsItsJson() {
    // Arrange
    Content hi = Content.fromParts(Part.fromText("hi"));
    Schema text = OBJECT.toBuilder().properties(ImmutableMap.of("1", STRING)).build();

    // Act
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaValues.check(ImmutableMap.of(1, hi), text, WHAT));
    Object stringKey = SchemaValues.check(ImmutableMap.of("1", hi), text, WHAT);

    // Assert
    assertThat(error).hasMessageThat().endsWith("'1' is not a string");
    assertThat(stringKey).isEqualTo(ImmutableMap.of("1", "hi"));
  }

  @Test
  public void check_nanUnderAnUntypedSchema_failsWithAValidationError() {
    // Act
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaValues.check(ImmutableMap.of("x", Double.NaN), ANY, WHAT));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "validation error: output of node 'n' holds a NaN or infinite number, which JSON"
                + " lacks.");
  }

  @Test
  public void isJsonForm_acceptsOnlyValuesTheCoercerReads() {
    // Act
    boolean json = SchemaValues.isJsonForm(ImmutableMap.of("n", 3L, "tags", ImmutableList.of("a")));
    boolean enumValue = SchemaValues.isJsonForm(ImmutableMap.of("tag", Tag.A));
    boolean bigDecimal = SchemaValues.isJsonForm(new BigDecimal("2.5"));

    // Assert
    assertThat(json).isTrue();
    assertThat(enumValue).isFalse();
    assertThat(bigDecimal).isFalse();
  }

  private static Schema typed(Type.Known type) {
    return Schema.builder().type(type).build();
  }
}
