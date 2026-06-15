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
}
