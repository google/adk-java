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
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.adk.JsonBaseModel;
import com.google.genai.types.Schema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    assertThat(schema.required().orElse(List.of())).isEmpty();
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
    assertThat(schema.required().orElse(List.of())).isEmpty();
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

  @Test
  public void fieldSchemaAndGetterJacksonName_areOneProperty() {
    assertPropertyAndBinding(FieldSchema.class, "fieldSchema");
  }

  @Test
  public void getterSchemaAndFieldJacksonName_areOneProperty() {
    assertPropertyAndBinding(GetterSchema.class, "getterSchema");
  }

  private static void assertPropertyAndBinding(Class<?> type, String function) {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(type);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("customer_id");
    assertThat(
            FunctionTool.create(Functions.class, function)
                .runAsync(Map.of("request", Map.of("customer_id", "42")), null)
                .blockingGet())
        .containsExactly("id", "42");
  }

  @Test
  public void readOnlyProperty_doesNotExposeBothNames() {
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(ReadOnlySchema.class)
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("customer_id");
  }

  @Test
  public void creatorAnnotation_agreesWithSchemaAndBinding() {
    assertPropertyAndBinding(CreatorSchema.class, "creatorSchema");
  }

  @Test
  public void creatorOnlyProperty_isIncludedWithItsValueType() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(CreatorOnlySchema.class);
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("customer_id");
    assertThat(schema.properties().orElseThrow().get("customer_id").type().orElseThrow().toString())
        .isEqualTo("STRING");
  }

  @Test
  public void conflictingSchemaNames_failWithPropertyAndNames() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> FunctionCallingUtils.buildSchemaFromType(ConflictingSchema.class));
    assertThat(error).hasMessageThat().contains("id");
    assertThat(error).hasMessageThat().contains("field_name");
    assertThat(error).hasMessageThat().contains("getter_name");
  }

  @Test
  public void descriptionOnlyRecord_missingValueRemainsOptional() {
    assertThat(
            FunctionTool.create(Functions.class, "describedRecord")
                .runAsync(Map.of("request", Map.of()), null)
                .blockingGet())
        .containsExactly("present", false);
  }

  @Test
  public void optionalRecord_missingValueBindsEmpty() {
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(OptionalRecord.class)
                .required()
                .orElse(List.of()))
        .isEmpty();
    assertThat(
            FunctionTool.create(Functions.class, "optionalRecord")
                .runAsync(Map.of("request", Map.of()), null)
                .blockingGet())
        .containsExactly("present", false);
  }

  @Test
  public void descriptionOnlyCreator_preservesJacksonRequired() {
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(RequiredCreator.class)
                .required()
                .orElseThrow())
        .containsExactly("id");
    assertThat(
            FunctionTool.create(Functions.class, "requiredCreator")
                .runAsync(Map.of("request", Map.of()), null)
                .blockingGet())
        .containsEntry("status", "error");
  }

  @Test
  public void optionalCreator_relaxesJacksonRequired() {
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(RelaxedCreator.class)
                .required()
                .orElse(List.of()))
        .isEmpty();
    assertThat(
            FunctionTool.create(Functions.class, "relaxedCreator")
                .runAsync(Map.of("request", Map.of()), null)
                .blockingGet())
        .containsExactly("present", false);
  }

  @Test
  public void annotatedNonGetter_isNotAPropertyOrInvoked() {
    MethodAnnotatedPojo.calls = 0;
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(MethodAnnotatedPojo.class)
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("value");
    assertThat(
            FunctionTool.create(Functions.class, "methodAnnotatedResult")
                .runAsync(Map.of(), null)
                .blockingGet())
        .containsExactly("value", "safe");
    assertThat(MethodAnnotatedPojo.calls).isEqualTo(0);
  }

  @Test
  public void annotatedListReturn_usesToolNamesInsideResult() {
    assertThat(
            FunctionTool.create(Functions.class, "currentCustomers")
                .runAsync(Map.of(), null)
                .blockingGet())
        .containsExactly("result", List.of(Map.of("customer_id", "result-42")));
  }

  @Test
  public void mapperSubclassWithoutCopy_acceptsLegacyTypes() throws Exception {
    ObjectMapper mapper = new ObjectMapper() {};
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(PlainRequest.class, mapper)
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("displayName");
    assertThat(
            new CustomMapperTool(mapper)
                .runAsync(Map.of("request", Map.of("displayName", "Ada")), null)
                .blockingGet())
        .containsExactly("name", "Ada");
  }

  @Test
  public void moduleRegisteredAfterConstruction_reachesExistingTool() throws Exception {
    ObjectMapper mapper = JsonBaseModel.getMapper().copy();
    FunctionTool tool = new CustomMapperTool(mapper);
    mapper.registerModule(
        new SimpleModule()
            .addDeserializer(
                PlainRequest.class,
                new JsonDeserializer<PlainRequest>() {
                  @Override
                  public PlainRequest deserialize(JsonParser parser, DeserializationContext context)
                      throws java.io.IOException {
                    parser.skipChildren();
                    PlainRequest request = new PlainRequest();
                    request.displayName = "module";
                    return request;
                  }
                }));
    assertThat(tool.runAsync(Map.of("request", Map.of("displayName", "Ada")), null).blockingGet())
        .containsExactly("name", "module");
  }

  @Test
  public void packageSchemaEntry_acceptsPlainMapper() throws Exception {
    Schema request =
        FunctionCallingUtils.buildFunctionDeclaration(
                Functions.class.getMethod("lookup", Customer.class),
                List.of(),
                JsonBaseModel.getMapper())
            .parameters()
            .orElseThrow()
            .properties()
            .orElseThrow()
            .get("request");
    assertThat(request.properties().orElseThrow().keySet()).containsExactly("customer_id");
  }

  @Test
  public void jacksonDescription_isFallbackForSchemaDescription() {
    Schema schema = FunctionCallingUtils.buildSchemaFromType(JacksonDescription.class);
    assertThat(schema.properties().orElseThrow().get("value").description())
        .hasValue("Jackson description");
  }

  @Test
  public void schemaDescription_takesPrecedenceOverJacksonDescription() {
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(BothDescriptions.class)
                .properties()
                .orElseThrow()
                .get("value")
                .description())
        .hasValue("Tool description");
  }

  @Test
  public void factoryCreatorAnnotation_agreesWithSchemaAndBinding() {
    assertPropertyAndBinding(FactorySchema.class, "factorySchema");
  }

  @Test
  public void topLevelParameter_stillRequiredByDefault() {
    assertThat(
            FunctionTool.create(Functions.class, "lookup")
                .declaration()
                .orElseThrow()
                .parameters()
                .orElseThrow()
                .required()
                .orElseThrow())
        .containsExactly("request");
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

    public static List<Customer> currentCustomers() {
      return List.of(currentCustomer());
    }

    public static Map<String, Object> fieldSchema(
        @Annotations.Schema(name = "request") FieldSchema request) {
      return Map.of("id", request.getId());
    }

    public static Map<String, Object> getterSchema(
        @Annotations.Schema(name = "request") GetterSchema request) {
      return Map.of("id", request.getId());
    }

    public static Map<String, Object> creatorSchema(
        @Annotations.Schema(name = "request") CreatorSchema request) {
      return Map.of("id", request.getId());
    }

    public static Map<String, Object> describedRecord(
        @Annotations.Schema(name = "request") DescribedRecord request) {
      return Map.of("present", request.id() != null);
    }

    public static Map<String, Object> optionalRecord(
        @Annotations.Schema(name = "request") OptionalRecord request) {
      return Map.of("present", request.id().isPresent());
    }

    public static Map<String, Object> requiredCreator(
        @Annotations.Schema(name = "request") RequiredCreator request) {
      return Map.of("present", request.id() != null);
    }

    public static Map<String, Object> relaxedCreator(
        @Annotations.Schema(name = "request") RelaxedCreator request) {
      return Map.of("present", request.id() != null);
    }

    public static MethodAnnotatedPojo methodAnnotatedResult() {
      return new MethodAnnotatedPojo();
    }

    public static Map<String, Object> factorySchema(
        @Annotations.Schema(name = "request") FactorySchema request) {
      return Map.of("id", request.getId());
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

  public static final class FieldSchema {
    @Annotations.Schema(name = "customer_id")
    private String id;

    @JsonProperty("business_id")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class GetterSchema {
    @JsonProperty("business_id")
    private String id;

    @Annotations.Schema(name = "customer_id")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class ReadOnlySchema {
    @Annotations.Schema(name = "customer_id")
    private final String id = "42";

    @JsonProperty("business_id")
    public String getId() {
      return id;
    }
  }

  public static final class CreatorSchema {
    private final String id;

    @JsonCreator
    public CreatorSchema(@JsonProperty("id") @Annotations.Schema(name = "customer_id") String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }
  }

  public static final class CreatorOnlySchema {
    @JsonCreator
    public CreatorOnlySchema(
        @JsonProperty("id") @Annotations.Schema(name = "customer_id") String id) {}
  }

  public static final class ConflictingSchema {
    @Annotations.Schema(name = "field_name")
    private String id;

    @Annotations.Schema(name = "getter_name")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public record DescribedRecord(@Annotations.Schema(description = "Identifier") String id) {}

  public record OptionalRecord(
      @Annotations.Schema(description = "Identifier") Optional<String> id) {}

  public record RequiredCreator(
      @JsonProperty(required = true) @Annotations.Schema(description = "Identifier") String id) {}

  public record RelaxedCreator(
      @JsonProperty(required = true) @Annotations.Schema(optional = true) String id) {}

  public static final class MethodAnnotatedPojo {
    static int calls;

    @Annotations.Schema(name = "value")
    public String value = "safe";

    @Annotations.Schema
    public String execute() {
      calls++;
      return "unexpected";
    }
  }

  public static final class JacksonDescription {
    @JsonPropertyDescription("Jackson description")
    @Annotations.Schema
    public String value;
  }

  public static final class BothDescriptions {
    @JsonPropertyDescription("Business description")
    @Annotations.Schema(description = "Tool description")
    public String value;
  }

  public static final class FactorySchema {
    private final String id;

    private FactorySchema(String id) {
      this.id = id;
    }

    @JsonCreator
    public static FactorySchema of(
        @JsonProperty("id") @Annotations.Schema(name = "customer_id") String id) {
      return new FactorySchema(id);
    }

    public String getId() {
      return id;
    }
  }

  private static final class CustomMapperTool extends FunctionTool {
    CustomMapperTool(ObjectMapper mapper) throws NoSuchMethodException {
      super(null, Functions.class.getMethod("readPlain", PlainRequest.class), false, mapper);
    }
  }
}
