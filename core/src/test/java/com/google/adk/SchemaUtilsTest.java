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

import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SchemaUtils}. */
@RunWith(JUnit4.class)
public final class SchemaUtilsTest {

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
}
