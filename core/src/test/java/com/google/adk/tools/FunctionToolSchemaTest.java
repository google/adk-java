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
package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.google.adk.JsonBaseModel;
import com.google.genai.types.Schema;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Verifies that tool schemas and argument binding agree without changing business JSON. */
@RunWith(JUnit4.class)
public final class FunctionToolSchemaTest {

  @Test
  public void schemaAnnotation_overridesJacksonNameAndAddsDescription() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(Customer.class);

    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("customer_id");
    assertThat(schema.properties().orElseThrow().get("customer_id").description())
        .hasValue("The customer identifier");
    assertThat(schema.required().orElseThrow()).containsExactly("customer_id");
  }

  @Test
  public void optionalAnnotation_overridesJacksonRequired() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(OptionalCustomer.class);

    assertThat(schema.required().orElse(List.of())).isEmpty();
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("business_id");
  }

  @Test
  public void runAsync_bindsModelFacingName() {
    FunctionTool tool = FunctionTool.create(Functions.class, "lookup");

    Map<String, Object> result =
        tool.runAsync(Map.of("request", Map.of("customer_id", "customer-42")), null).blockingGet();

    assertThat(result).containsExactly("id", "customer-42");
  }

  @Test
  public void runAsync_bindsNestedObjectsInLists() {
    FunctionTool tool = FunctionTool.create(Functions.class, "lookupMany");

    Map<String, Object> result =
        tool.runAsync(Map.of("requests", List.of(Map.of("customer_id", "customer-42"))), null)
            .blockingGet();

    assertThat(result).containsExactly("id", "customer-42");
  }

  @Test
  public void toolMapper_doesNotChangeSharedBusinessMapper() throws Exception {
    var mapper = JsonBaseModel.getMapper();
    var introspector = mapper.getSerializationConfig().getAnnotationIntrospector();
    var unused = FunctionTool.create(Functions.class, "lookup");
    Customer customer = new Customer();
    customer.setId("customer-42");

    assertThat(mapper.getSerializationConfig().getAnnotationIntrospector())
        .isSameInstanceAs(introspector);
    assertThat(mapper.readTree(mapper.writeValueAsString(customer)).has("business_id")).isTrue();
    assertThat(mapper.readTree(mapper.writeValueAsString(customer)).has("customer_id")).isFalse();
  }

  @Test
  public void fieldAnnotation_controlsNameDescriptionAndOptionality() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(FieldRequest.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("field_name");
    assertThat(schema.properties().orElseThrow().get("field_name").description())
        .hasValue("Field description");
    assertThat(schema.required().orElse(List.of())).isEmpty();

    FunctionTool tool = FunctionTool.create(Functions.class, "readField");
    assertThat(tool.runAsync(Map.of("request", Map.of("field_name", "value")), null).blockingGet())
        .containsExactly("value", "value");
  }

  @Test
  public void emptyAnnotationName_keepsJacksonName() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(OptionalCustomer.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("business_id");
    FunctionTool tool = FunctionTool.create(Functions.class, "readOptionalCustomer");
    assertThat(tool.runAsync(Map.of("request", Map.of("business_id", "value")), null).blockingGet())
        .containsExactly("id", "value");
  }

  @Test
  public void annotatedPrivateField_isIncludedWithoutExposingOtherPrivateFields() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(PrivateFieldRequest.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("value");
    FunctionTool tool = FunctionTool.create(Functions.class, "readPrivateField");
    assertThat(tool.runAsync(Map.of("request", Map.of("value", "selected")), null).blockingGet())
        .containsExactly("value", "selected");
  }

  @Test
  public void recordComponents_supportNamesAndOptionalCreatorProperties() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(RecordRequest.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("customer_id", "note");
    assertThat(schema.required().orElseThrow()).containsExactly("customer_id");
    assertThat(schema.properties().orElseThrow().get("customer_id").description())
        .hasValue("Record identifier");
    FunctionTool tool = FunctionTool.create(Functions.class, "readRecord");
    assertThat(
            tool.runAsync(Map.of("request", Map.of("customer_id", "record-42")), null)
                .blockingGet())
        .containsExactly("id", "record-42", "hasNote", false);
  }

  @Test
  public void setterAnnotation_agreesWithSchemaAndBinding() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(SetterRequest.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("setter_id");
    assertThat(schema.properties().orElseThrow().get("setter_id").description())
        .hasValue("Setter identifier");
    FunctionTool tool = FunctionTool.create(Functions.class, "readSetter");
    assertThat(
            tool.runAsync(Map.of("request", Map.of("setter_id", "setter-42")), null).blockingGet())
        .containsExactly("id", "setter-42");
  }

  @Test
  public void nestedObjectProperty_preservesGenericElementBinding() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(Group.class);
    Schema element = schema.properties().orElseThrow().get("members").items().orElseThrow();
    assertThat(element.properties().orElseThrow().keySet()).containsExactly("customer_id");
    FunctionTool tool = FunctionTool.create(Functions.class, "readGroup");
    assertThat(
            tool.runAsync(
                    Map.of(
                        "request", Map.of("members", List.of(Map.of("customer_id", "nested-42")))),
                    null)
                .blockingGet())
        .containsExactly("id", "nested-42");
  }

  @Test
  public void jsonIgnore_stillWinsOverSchemaAnnotation() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(IgnoredRequest.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("visible");
  }

  @Test
  public void unannotatedPojo_keepsJacksonNameAndRequiredFlag() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(LegacyRequest.class);
    assertThat(schema.properties().orElseThrow().keySet())
        .containsExactly("business_name", "limit");
    assertThat(schema.required().orElseThrow()).containsExactly("business_name");
  }

  @Test
  public void customMapper_configurationIsUsedForSchemaAndBindingWithoutMutation()
      throws Exception {
    ObjectMapper mapper =
        JsonBaseModel.getMapper()
            .copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    var original = mapper.getDeserializationConfig().getAnnotationIntrospector();
    FunctionTool tool = new CustomMapperTool(mapper);
    Schema request =
        tool.declaration()
            .orElseThrow()
            .parameters()
            .orElseThrow()
            .properties()
            .orElseThrow()
            .get("request");
    assertThat(request.properties().orElseThrow().keySet()).containsExactly("display_name");
    assertThat(tool.runAsync(Map.of("request", Map.of("display_name", "Ada")), null).blockingGet())
        .containsExactly("name", "Ada");
    assertThat(mapper.getDeserializationConfig().getAnnotationIntrospector())
        .isSameInstanceAs(original);
  }

  @Test
  public void annotatedReturnValue_matchesResponseSchema() {
    FunctionTool tool = FunctionTool.create(Functions.class, "currentCustomer");
    assertThat(
            tool.declaration()
                .orElseThrow()
                .response()
                .orElseThrow()
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("customer_id");
    assertThat(tool.runAsync(Map.of(), null).blockingGet())
        .containsExactly("customer_id", "result-42");
  }

  public static final class Customer {
    private String id;

    @JsonProperty("business_id")
    @Annotations.Schema(name = "customer_id", description = "The customer identifier")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class OptionalCustomer {
    private String id;

    @JsonProperty(value = "business_id", required = true)
    @Annotations.Schema(optional = true)
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class Functions {
    public static Map<String, Object> lookup(
        @Annotations.Schema(name = "request") Customer request) {
      return Map.of("id", request.getId());
    }

    public static Map<String, Object> lookupMany(
        @Annotations.Schema(name = "requests") List<Customer> requests) {
      return Map.of("id", requests.get(0).getId());
    }

    public static Map<String, Object> readField(
        @Annotations.Schema(name = "request") FieldRequest request) {
      return Map.of("value", request.value);
    }

    public static Map<String, Object> readOptionalCustomer(
        @Annotations.Schema(name = "request") OptionalCustomer request) {
      return Map.of("id", request.getId());
    }

    public static Map<String, Object> readPrivateField(
        @Annotations.Schema(name = "request") PrivateFieldRequest request) {
      return Map.of("value", request.value);
    }

    public static Map<String, Object> readRecord(
        @Annotations.Schema(name = "request") RecordRequest request) {
      return Map.of("id", request.customerId(), "hasNote", request.note() != null);
    }

    public static Map<String, Object> readSetter(
        @Annotations.Schema(name = "request") SetterRequest request) {
      return Map.of("id", request.getId());
    }

    public static Map<String, Object> readGroup(
        @Annotations.Schema(name = "request") Group request) {
      return Map.of("id", request.customers.get(0).getId());
    }

    public static Map<String, Object> readPlain(
        @Annotations.Schema(name = "request") PlainRequest request) {
      return Map.of("name", request.displayName);
    }

    public static Customer currentCustomer() {
      Customer customer = new Customer();
      customer.setId("result-42");
      return customer;
    }
  }

  public static final class FieldRequest {
    @JsonProperty(value = "business_field", required = true)
    @Annotations.Schema(name = "field_name", description = "Field description", optional = true)
    public String value;
  }

  public static final class PrivateFieldRequest {
    @Annotations.Schema(description = "Visible tool field")
    private String value;

    private String hidden;
  }

  public record RecordRequest(
      @JsonProperty("business_id")
          @Annotations.Schema(name = "customer_id", description = "Record identifier")
          String customerId,
      @JsonProperty(required = true) @Annotations.Schema(optional = true) String note) {}

  public static final class SetterRequest {
    private String id;

    public String getId() {
      return id;
    }

    @Annotations.Schema(name = "setter_id", description = "Setter identifier")
    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class Group {
    @Annotations.Schema(name = "members")
    public List<Customer> customers;
  }

  public static final class IgnoredRequest {
    @JsonIgnore
    @Annotations.Schema(name = "secret")
    public String ignored;

    public String visible;
  }

  public static final class LegacyRequest {
    @JsonProperty(value = "business_name", required = true)
    public String name;

    public int limit;
  }

  public static final class PlainRequest {
    public String displayName;
  }

  private static final class CustomMapperTool extends FunctionTool {
    CustomMapperTool(ObjectMapper mapper) throws NoSuchMethodException {
      super(null, Functions.class.getMethod("readPlain", PlainRequest.class), false, mapper);
    }
  }
}
