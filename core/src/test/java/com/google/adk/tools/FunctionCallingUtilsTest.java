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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link FunctionCallingUtils}. */
@RunWith(JUnit4.class)
public final class FunctionCallingUtilsTest {

  public static class PojoWithFields {
    public String field1;
    public int field2;
  }

  public static class PojoWithOptionalFields {
    public Optional<String> optionalField;
    public Optional<PojoWithFields> optionalPojo;
    public Optional<List<String>> optionalList;
  }

  @Test
  public void buildSchemaFromType_optionalString_returnsNullableString() {
    Type type = new TypeReference<Optional<String>>() {}.getType();

    Schema schema = FunctionCallingUtils.buildSchemaFromType(type);

    assertThat(schema).isEqualTo(Schema.builder().type("STRING").nullable(true).build());
  }

  @Test
  public void buildSchemaFromType_optionalPojo_returnsNullablePojoWithProperties() {
    Type type = new TypeReference<Optional<PojoWithFields>>() {}.getType();

    Schema schema = FunctionCallingUtils.buildSchemaFromType(type);

    assertThat(schema)
        .isEqualTo(
            Schema.builder()
                .type("OBJECT")
                .nullable(true)
                .properties(
                    ImmutableMap.of(
                        "field1", Schema.builder().type("STRING").build(),
                        "field2", Schema.builder().type("INTEGER").build()))
                .build());
  }

  @Test
  public void buildSchemaFromType_pojoWithOptionalFields_generatesCorrectSchema() {
    Type type = PojoWithOptionalFields.class;

    Schema schema = FunctionCallingUtils.buildSchemaFromType(type);

    Schema expectedSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "optionalField",
                    Schema.builder().type("STRING").nullable(true).build(),
                    "optionalPojo",
                    Schema.builder()
                        .type("OBJECT")
                        .nullable(true)
                        .properties(
                            ImmutableMap.of(
                                "field1", Schema.builder().type("STRING").build(),
                                "field2", Schema.builder().type("INTEGER").build()))
                        .build(),
                    "optionalList",
                    Schema.builder()
                        .type("ARRAY")
                        .nullable(true)
                        .items(Schema.builder().type("STRING").build())
                        .build()))
            .build();

    assertThat(schema).isEqualTo(expectedSchema);
  }
}
