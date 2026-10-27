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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link SchemaFit}, which checks the schemas of a workflow edge when it is built. */
@RunWith(JUnit4.class)
public final class SchemaFitTest {

  private static final Schema NULL = typed(Type.Known.NULL);
  private static final Schema INTEGER = typed(Type.Known.INTEGER);
  private static final Schema STRING = typed(Type.Known.STRING);
  private static final Schema INTEGER_OR_STRING = Schema.builder().anyOf(INTEGER, STRING).build();
  private static final Schema RED = Schema.builder().type(Type.Known.STRING).enum_("red").build();
  private static final Schema COLORS =
      Schema.builder().type(Type.Known.STRING).enum_("red", "blue").build();
  private static final Schema ID_ONLY = object(ImmutableMap.of("id", STRING), "id");
  private static final Schema ID_AND_NOTE =
      object(ImmutableMap.of("id", STRING, "note", STRING), "id");

  @Test
  public void fits_everySourceShapeIntoEveryTargetShape_matchesTheAssignabilityTable() {
    // Arrange: "closed object" is "open object" as a node infers it, so it takes no other key.
    ImmutableMap<String, Schema> shapes =
        ImmutableMap.<String, Schema>builder()
            .put("integer", INTEGER)
            .put("number", typed(Type.Known.NUMBER))
            .put("string", STRING)
            .put("untyped", Schema.builder().build())
            .put("integer or string", INTEGER_OR_STRING)
            .put("red", RED)
            .put("nullable integer", INTEGER.toBuilder().nullable(true).build())
            .put("map", typed(Type.Known.OBJECT))
            .put("open object", ID_ONLY)
            .put("closed object", ID_ONLY)
            .put("id and note", ID_AND_NOTE)
            .put("integer list", array(INTEGER))
            .buildOrThrow();
    ImmutableSet<String> objects =
        ImmutableSet.of("untyped", "map", "open object", "closed object", "id and note");
    ImmutableMap<String, ImmutableSet<String>> expected =
        ImmutableMap.<String, ImmutableSet<String>>builder()
            .put(
                "integer",
                ImmutableSet.of(
                    "integer", "number", "untyped", "integer or string", "nullable integer"))
            .put("number", ImmutableSet.of("number", "untyped"))
            .put("string", ImmutableSet.of("string", "untyped", "integer or string"))
            .put("untyped", shapes.keySet())
            .put("integer or string", ImmutableSet.of("untyped", "integer or string"))
            .put("red", ImmutableSet.of("string", "untyped", "integer or string", "red"))
            .put("nullable integer", ImmutableSet.of("untyped", "nullable integer"))
            .put("map", objects)
            .put("open object", objects)
            .put("closed object", objects)
            .put("id and note", ImmutableSet.of("untyped", "map", "open object", "id and note"))
            .put("integer list", ImmutableSet.of("untyped", "integer list"))
            .buildOrThrow();

    // Act
    ImmutableMap<String, ImmutableSet<String>> actual =
        shapes.keySet().stream()
            .collect(
                toImmutableMap(
                    source -> source,
                    source ->
                        shapes.keySet().stream()
                            .filter(
                                target ->
                                    SchemaFit.fits(
                                        shapes.get(source),
                                        shapes.get(target),
                                        target.equals("closed object")))
                            .collect(toImmutableSet())));

    // Assert
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void fits_nullableSourceOrUnion_needsATargetThatTakesNull() {
    // Arrange
    Schema integerOrNull = Schema.builder().anyOf(INTEGER, NULL).build();
    Schema nullableIntegerOrString = INTEGER_OR_STRING.toBuilder().nullable(true).build();

    // Act / Assert
    assertThat(fits(INTEGER.toBuilder().nullable(true).build(), integerOrNull)).isTrue();
    assertThat(fits(nullableIntegerOrString, INTEGER_OR_STRING)).isFalse();
    assertThat(fits(NULL, nullableIntegerOrString)).isTrue();
    assertThat(fits(NULL, INTEGER_OR_STRING)).isFalse();
  }

  @Test
  public void fits_enums_stayWithinTheTargetEnum() {
    // Arrange: an untyped enum lists strings, as a typed one does.
    Schema untypedRed = Schema.builder().enum_("red").build();

    // Act / Assert
    assertThat(fits(RED, COLORS)).isTrue();
    assertThat(fits(COLORS, RED)).isFalse();
    assertThat(fits(STRING, RED)).isFalse();
    assertThat(fits(untypedRed, STRING)).isTrue();
    assertThat(fits(untypedRed, INTEGER)).isFalse();
    assertThat(fits(Schema.builder().enum_("green").build(), COLORS)).isFalse();
  }

  @Test
  public void fits_objectsAndArrays_checkRequiredDeclaredAndClosedKeysAtEachLevel() {
    // Arrange
    Schema integerId = object(ImmutableMap.of("id", INTEGER), "id");
    Schema requiresId = Schema.builder().type(Type.Known.OBJECT).required("id").build();
    Schema noteOnly = object(ImmutableMap.of("note", STRING));
    Schema holdsIdAndNote = object(ImmutableMap.of("order", ID_AND_NOTE), "order");
    Schema holdsIdOnly = object(ImmutableMap.of("order", ID_ONLY), "order");

    // Act / Assert
    assertThat(fits(integerId, ID_ONLY)).isFalse();
    assertThat(fits(ID_ONLY, ID_AND_NOTE.toBuilder().required("id", "note").build())).isFalse();
    assertThat(fits(requiresId, ID_ONLY)).isFalse();
    assertThat(SchemaFit.fits(requiresId, noteOnly, /* closed= */ true)).isFalse();
    assertThat(fits(holdsIdAndNote, holdsIdOnly)).isTrue();
    assertThat(SchemaFit.fits(holdsIdAndNote, holdsIdOnly, /* closed= */ true)).isFalse();
    // A map lists no properties, so it stays open inside a closed target.
    assertThat(SchemaFit.fits(ID_ONLY, typed(Type.Known.OBJECT), /* closed= */ true)).isTrue();
    assertThat(fits(array(STRING), array(INTEGER))).isFalse();
    assertThat(SchemaFit.fits(array(ID_AND_NOTE), array(ID_ONLY), /* closed= */ true)).isFalse();
    assertThat(fits(typed(Type.Known.ARRAY), array(INTEGER))).isTrue();
  }

  @Test
  public void fits_unions_needEverySourceBranchAndOneTargetBranch() {
    // Arrange
    Schema green = Schema.builder().type(Type.Known.STRING).enum_("green").build();
    Schema requiresId = Schema.builder().required("id").build();
    Schema requiresNote = object(ImmutableMap.of("note", STRING), "note");
    Schema stringRed = Schema.builder().type(Type.Known.STRING).anyOf(RED).build();

    // Act / Assert
    assertThat(fits(INTEGER_OR_STRING, INTEGER)).isFalse();
    assertThat(fits(INTEGER, Schema.builder().type(Type.Known.STRING).anyOf(INTEGER).build()))
        .isFalse();
    assertThat(fits(Schema.builder().anyOf(RED).build(), COLORS)).isTrue();
    assertThat(fits(Schema.builder().anyOf(RED, green).build(), COLORS)).isFalse();
    assertThat(fits(Schema.builder().enum_("green").anyOf(COLORS).build(), COLORS)).isFalse();
    // An untyped union's own enum takes its branches' type.
    assertThat(fits(Schema.builder().enum_("1").anyOf(INTEGER).build(), INTEGER)).isTrue();
    // An untyped branch takes its union's type, so its keys count.
    assertThat(
            fits(Schema.builder().type(Type.Known.OBJECT).anyOf(requiresId).build(), requiresNote))
        .isFalse();
    // A union that rejects null drops its NULL branch.
    assertThat(fits(Schema.builder().type(Type.Known.STRING).anyOf(STRING, NULL).build(), STRING))
        .isTrue();
    // A closed target stays closed for each branch of a union.
    assertThat(
            SchemaFit.fits(
                Schema.builder().anyOf(ID_AND_NOTE).build(), ID_ONLY, /* closed= */ true))
        .isFalse();
    // Only identity lets this union fit itself: its own part fits none of its branches.
    assertThat(fits(stringRed, stringRed)).isTrue();
  }

  private static boolean fits(Schema source, Schema target) {
    return SchemaFit.fits(source, target, /* closed= */ false);
  }

  private static Schema typed(Type.Known type) {
    return Schema.builder().type(type).build();
  }

  private static Schema array(Schema items) {
    return Schema.builder().type(Type.Known.ARRAY).items(items).build();
  }

  private static Schema object(ImmutableMap<String, Schema> properties, String... required) {
    Schema.Builder object = Schema.builder().type(Type.Known.OBJECT).properties(properties);
    return (required.length == 0 ? object : object.required(required)).build();
  }
}
