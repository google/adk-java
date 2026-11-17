/*
 * Copyright 2025 Google LLC
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

package com.google.adk;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SchemaUtils}. */
@RunWith(JUnit4.class)
public final class SchemaUtilsTest {

  private static final Schema INTEGER = Schema.builder().type("INTEGER").build();
  private static final Schema STRING = Schema.builder().type("STRING").build();

  @Test
  public void validateMapOnSchema_nullableField_allowsNull() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "nullableField", Schema.builder().type("STRING").nullable(true).build()))
            .build();

    Map<String, Object> args = new HashMap<>();
    args.put("nullableField", null);

    // Should not throw exception
    SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ true);
  }

  @Test
  public void validateMapOnSchema_nonNullableField_throwsException() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "nonNullableField", Schema.builder().type("STRING").nullable(false).build()))
            .build();

    Map<String, Object> args = new HashMap<>();
    args.put("nonNullableField", null);

    assertThrows(
        IllegalArgumentException.class,
        () -> SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ true));
  }

  @Test
  public void validateMapOnSchema_implicitNonNullableField_throwsException() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("defaultField", Schema.builder().type("STRING").build()))
            .build();

    Map<String, Object> args = new HashMap<>();
    args.put("defaultField", null);

    assertThrows(
        IllegalArgumentException.class,
        () -> SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ true));
  }

  @Test
  public void validateMapOnSchema_integerField_allowsIntegerAndLong() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("intField", Schema.builder().type("INTEGER").build()))
            .build();

    Map<String, Object> args = new HashMap<>();
    args.put("intField", 1234567890123L);

    // Should not throw exception
    SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ true);

    args.put("intField", 123);
    SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ true);
  }

  @Test
  public void validateMapOnSchema_untypedProperty_acceptsAnyValue() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("anything", Schema.builder().build()))
            .build();

    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("anything", ImmutableMap.of("nested", 1)), schema, /* isInput= */ false);
    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("anything", "text"), schema, /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_objectWithoutProperties_acceptsAnyKeys() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("metadata", Schema.builder().type("OBJECT").build()))
            .build();

    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("metadata", ImmutableMap.of("source", "web", "score", 0.5)),
        schema,
        /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_topLevelObjectWithoutProperties_stillEnforcesRequired() {
    Schema schema = Schema.builder().type("OBJECT").required(ImmutableList.of("id")).build();

    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("id", "1", "extra", true), schema, /* isInput= */ true);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("extra", true), schema, /* isInput= */ true));
  }

  @Test
  public void validateMapOnSchema_arrayWithoutItems_acceptsAnyElements() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("tags", Schema.builder().type("ARRAY").build()))
            .build();

    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("tags", ImmutableList.of("a", 1, ImmutableMap.of())),
        schema,
        /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_arrayWithoutItems_stillRejectsANonList() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("tags", Schema.builder().type("ARRAY").build()))
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("tags", "a"), schema, /* isInput= */ false));
  }

  @Test
  public void validateMapOnSchema_nonObjectSchema_throwsException() {
    Schema schema = Schema.builder().type("ARRAY").build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(ImmutableMap.of("a", 1), schema, /* isInput= */ false));
  }

  @Test
  public void validateMapOnSchema_untypedProperty_acceptsNull() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("anything", Schema.builder().build()))
            .build();
    Map<String, Object> args = new HashMap<>();
    args.put("anything", null);

    SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_untypedNonNullableProperty_acceptsNull() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("anything", Schema.builder().nullable(false).build()))
            .build();
    Map<String, Object> args = new HashMap<>();
    args.put("anything", null);

    SchemaUtils.validateMapOnSchema(args, schema, /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_untypedPropertyWithRequired_isAnObject() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of("item", Schema.builder().required(ImmutableList.of("id")).build()))
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("item", 5), schema, /* isInput= */ false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("item", ImmutableMap.of()), schema, /* isInput= */ false));
    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("item", ImmutableMap.of("id", "x")), schema, /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_untypedPropertyWithItems_isAnArray() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "tags",
                    Schema.builder().items(Schema.builder().type("STRING").build()).build()))
            .build();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("tags", 5), schema, /* isInput= */ false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("tags", ImmutableList.of(1)), schema, /* isInput= */ false));
    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("tags", ImmutableList.of("a")), schema, /* isInput= */ false);
  }

  @Test
  public void validateMapOnSchema_untypedNestedSchemaWithProperties_validatesAsObject() {
    Schema nested =
        Schema.builder()
            .properties(ImmutableMap.of("a", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("a"))
            .build();
    Schema schema =
        Schema.builder().type("OBJECT").properties(ImmutableMap.of("nested", nested)).build();

    SchemaUtils.validateMapOnSchema(
        ImmutableMap.of("nested", ImmutableMap.of("a", "x")), schema, /* isInput= */ false);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("nested", 5), schema, /* isInput= */ false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SchemaUtils.validateMapOnSchema(
                ImmutableMap.of("nested", ImmutableMap.of()), schema, /* isInput= */ false));
    Map<String, Object> nullNested = new HashMap<>();
    nullNested.put("nested", null);
    assertThrows(
        IllegalArgumentException.class,
        () -> SchemaUtils.validateMapOnSchema(nullNested, schema, /* isInput= */ false));
  }

  @Test
  public void findMismatch_valueMatchingOneAnyOfAlternative_matches() {
    Schema schema = Schema.builder().anyOf(ImmutableList.of(INTEGER, STRING)).build();

    Optional<String> mismatch = SchemaUtils.findMismatch(7, schema);

    assertThat(mismatch).isEmpty();
  }

  @Test
  public void findMismatch_valueMatchingNoAnyOfAlternative_explainsIt() {
    Schema schema = Schema.builder().anyOf(ImmutableList.of(INTEGER, STRING)).build();

    Optional<String> mismatch = SchemaUtils.findMismatch(true, schema);

    assertThat(mismatch.orElseThrow()).contains("anyOf");
  }

  @Test
  public void findMismatch_objectSchemaWithoutProperties_acceptsAnyMap() {
    Schema schema = Schema.builder().type("OBJECT").build();

    Optional<String> mismatch = SchemaUtils.findMismatch(ImmutableMap.of("a", 1), schema);

    assertThat(mismatch).isEmpty();
  }

  @Test
  public void findMismatch_undeclaredKey_explainsItWithoutNamingTheKey() {
    Schema schema =
        Schema.builder().type("OBJECT").properties(ImmutableMap.of("a", INTEGER)).build();

    Optional<String> mismatch = SchemaUtils.findMismatch(ImmutableMap.of("secret_key", 1), schema);

    assertThat(mismatch.orElseThrow()).doesNotContain("secret_key");
  }

  @Test
  public void findMismatch_nullForANullableSchema_matches() {
    Schema schema = Schema.builder().type("INTEGER").nullable(true).build();

    assertThat(SchemaUtils.findMismatch(null, schema)).isEmpty();
  }

  @Test
  public void findMismatch_bigIntegerForAnIntegerSchema_matches() {
    assertThat(SchemaUtils.findMismatch(new BigInteger("123456789012345678901"), INTEGER))
        .isEmpty();
  }

  @Test
  public void findMismatch_arrayItemOfTheWrongType_explainsIt() {
    Schema schema = Schema.builder().type("ARRAY").items(INTEGER).build();

    assertThat(SchemaUtils.findMismatch(ImmutableList.of(1, "two"), schema)).isPresent();
  }

  @Test
  public void findMismatch_missingRequiredProperty_namesIt() {
    Schema schema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("a", INTEGER))
            .required(ImmutableList.of("a"))
            .build();

    Optional<String> mismatch = SchemaUtils.findMismatch(ImmutableMap.of(), schema);

    assertThat(mismatch.orElseThrow()).contains("'a'");
  }

  @Test
  public void findMismatch_mapForANonObjectSchema_explainsIt() {
    assertThat(SchemaUtils.findMismatch(ImmutableMap.of("a", 1), INTEGER)).isPresent();
  }

  @Test
  public void parseModelOutput_quotedTextWithAStringSchema_decodesIt() {
    Object output = SchemaUtils.parseModelOutput("\"Paris\"", STRING);

    assertThat(output).isEqualTo("Paris");
  }

  @Test
  public void parseModelOutput_numberWithAStringSchema_keepsTheText() {
    Object output = SchemaUtils.parseModelOutput("42", STRING);

    assertThat(output).isEqualTo("42");
  }

  @Test
  public void parseModelOutput_numberWithTrailingTextForAnIntegerSchema_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> SchemaUtils.parseModelOutput("7 days", INTEGER));
  }

  @Test
  public void parseModelOutput_textWithAnAnyOfThatAcceptsAString_keepsTheText() {
    Schema schema = Schema.builder().anyOf(ImmutableList.of(INTEGER, STRING)).build();

    assertThat(SchemaUtils.parseModelOutput("seven", schema)).isEqualTo("seven");
  }

  @Test
  public void parseModelOutput_blankText_givesNull() {
    Object output = SchemaUtils.parseModelOutput("  ", STRING);

    assertThat(output).isNull();
  }

  @Test
  public void parseModelOutput_valueMatchingAnAnyOfAlternative_givesIt() {
    Schema schema = Schema.builder().anyOf(ImmutableList.of(INTEGER, STRING)).build();

    Object output = SchemaUtils.parseModelOutput("7", schema);

    assertThat(output).isEqualTo(7);
  }

  @Test
  public void parseModelOutput_stringForAnIntegerProperty_throwsWithoutQuotingIt() {
    Schema schema =
        Schema.builder().type("OBJECT").properties(ImmutableMap.of("n", INTEGER)).build();

    var thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> SchemaUtils.parseModelOutput("{\"n\": \"seven\"}", schema));

    assertThat(thrown).hasMessageThat().contains("validation error");
    assertThat(thrown).hasMessageThat().doesNotContain("seven");
  }

  @Test
  public void parseModelOutput_jsonNullWithANullableSchema_givesNull() {
    Schema schema = Schema.builder().type("INTEGER").nullable(true).build();

    Object output = SchemaUtils.parseModelOutput("null", schema);

    assertThat(output).isNull();
  }

  @Test
  public void parseModelOutput_jsonNullWithANonNullableSchema_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> SchemaUtils.parseModelOutput("null", INTEGER));
  }

  @Test
  public void validateOutput_arraySchema_returnsTheList() throws Exception {
    Schema schema = Schema.builder().type("ARRAY").items(STRING).build();

    Object output = SchemaUtils.validateOutput("[\"a\",\"b\"]", schema);

    assertThat(output).isEqualTo(ImmutableList.of("a", "b"));
  }

  @Test
  public void validateOutput_untypedSchemaWithOnlyItems_returnsTheList() throws Exception {
    Schema schema = Schema.builder().items(STRING).build();

    Object output = SchemaUtils.validateOutput("[\"a\"]", schema);

    assertThat(output).isEqualTo(ImmutableList.of("a"));
  }

  @Test
  public void validateOutput_objectSchema_returnsTheMap() throws Exception {
    Schema schema =
        Schema.builder().type("OBJECT").properties(ImmutableMap.of("a", INTEGER)).build();

    Object output = SchemaUtils.validateOutput("{\"a\":1}", schema);

    assertThat(output).isEqualTo(ImmutableMap.of("a", 1));
  }

  @Test
  public void validateOutput_arrayItemOfTheWrongType_throws() {
    Schema schema = Schema.builder().type("ARRAY").items(INTEGER).build();

    assertThrows(
        IllegalArgumentException.class, () -> SchemaUtils.validateOutput("[1,\"two\"]", schema));
  }

  @Test
  public void validateOutput_textForAnArraySchema_throwsJsonProcessingException() {
    Schema schema = Schema.builder().type("ARRAY").items(STRING).build();

    assertThrows(
        JsonProcessingException.class, () -> SchemaUtils.validateOutput("not json", schema));
  }

  @Test
  public void describesObject_unknownTypeWithProperties_isAnObject() {
    Schema schema =
        Schema.builder().type("TYPE_UNSPECIFIED").properties(ImmutableMap.of("a", INTEGER)).build();

    assertThat(SchemaUtils.describesObject(schema)).isTrue();
  }

  @Test
  public void describesObject_untypedSchemaWithOnlyItems_isNotAnObject() {
    assertThat(SchemaUtils.describesObject(Schema.builder().items(STRING).build())).isFalse();
  }
}
