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

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.Test;

public final class ToolSchemaObjectMapperTest {
  @Test
  public void unannotatedType_reusesOriginalMapperWithoutCopying() {
    ObjectMapper source = new ObjectMapper();
    assertThat(ToolSchemaObjectMapper.forType(source, Plain.class)).isSameInstanceAs(source);
  }

  @Test
  public void wrapping_isIdempotent() {
    ObjectMapper mapper = ToolSchemaObjectMapper.forType(new ObjectMapper(), Customer.class);
    assertThat(ToolSchemaObjectMapper.forType(mapper, Customer.class)).isSameInstanceAs(mapper);
  }

  @Test
  public void differentSources_neverShareToolMapper() {
    ObjectMapper source = new ObjectMapper();
    assertThat(ToolSchemaObjectMapper.forType(source, Customer.class))
        .isNotSameInstanceAs(ToolSchemaObjectMapper.forType(new ObjectMapper(), Customer.class));
  }

  @Test
  public void methodAnnotationAlone_doesNotCopyMapper() throws Exception {
    ObjectMapper source = new ObjectMapper();
    assertThat(
            ToolSchemaObjectMapper.forMethod(
                    source, Functions.class.getMethod("plain", Plain.class))
                .mapper())
        .isSameInstanceAs(source);
  }

  @Test
  public void multipleTools_shareOneConfiguredView() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool first = new CustomTool(source, "customer", Customer.class);
    FunctionTool second = new CustomTool(source, "customer", Customer.class);
    assertThat(ToolSchemaObjectMapper.forMethod(source, first.func()).mapper())
        .isSameInstanceAs(ToolSchemaObjectMapper.forMethod(source, second.func()).mapper());
    assertThat(first.declaration()).isEqualTo(second.declaration());
  }

  @Test
  public void mixInConfiguredBeforeConstruction_appliesToSchemaAndBinding() throws Exception {
    ObjectMapper source = new ObjectMapper().addMixIn(Plain.class, PlainMixin.class);
    FunctionTool tool = new CustomTool(source, "plain", Plain.class);
    assertThat(
            tool.declaration()
                .orElseThrow()
                .parameters()
                .orElseThrow()
                .properties()
                .orElseThrow()
                .get("request")
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("tool_name");
    assertThat(tool.runAsync(Map.of("request", Map.of("tool_name", "Ada")), null).blockingGet())
        .containsExactly("name", "Ada");
    assertThat(source.convertValue(Map.of("displayName", "business"), Plain.class).displayName)
        .isEqualTo("business");
  }

  @Test
  public void mixInReplacedBeforeFirstUse_appliesFinalConfiguration() {
    ObjectMapper source = new ObjectMapper().addMixIn(Plain.class, PlainMixin.class);
    source.addMixIn(Plain.class, OtherPlainMixin.class);
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(Plain.class, source)
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("other_name");
  }

  @Test
  public void nestedTypeGraph_enablesSchemaMapper() {
    ObjectMapper source = new ObjectMapper();
    assertThat(ToolSchemaObjectMapper.forType(source, Group.class)).isNotSameInstanceAs(source);
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(Group.class, source)
                .properties()
                .orElseThrow()
                .get("members")
                .items()
                .orElseThrow()
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("customer_id");
  }

  @Test
  public void recursiveUnannotatedType_doesNotCopyOrRecurseForever() {
    ObjectMapper source = new ObjectMapper();
    assertThat(ToolSchemaObjectMapper.forType(source, Recursive.class)).isSameInstanceAs(source);
  }

  @Test
  public void moduleRegisteredAfterConstruction_refreshesAnnotatedTool() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool = new CustomTool(source, "customer", Customer.class);
    assertThat(
            tool.runAsync(Map.of("request", Map.of("customer_id", "before")), null).blockingGet())
        .containsExactly("id", "before");
    source.registerModule(
        new SimpleModule()
            .addDeserializer(
                Customer.class,
                new JsonDeserializer<Customer>() {
                  @Override
                  public Customer deserialize(JsonParser parser, DeserializationContext context)
                      throws IOException {
                    parser.skipChildren();
                    Customer customer = new Customer();
                    customer.id = "module";
                    return customer;
                  }
                }));
    assertThat(tool.runAsync(Map.of("request", Map.of("customer_id", "42")), null).blockingGet())
        .containsExactly("id", "module");
  }

  @Test
  public void featureChangedAfterConstruction_refreshesAnnotatedTool() throws Exception {
    ObjectMapper source =
        new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    FunctionTool tool = new CustomTool(source, "customer", Customer.class);
    source.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    assertThat(
            tool.runAsync(Map.of("request", Map.of("customer_id", "42", "unknown", 1)), null)
                .blockingGet())
        .containsEntry("status", "error");
  }

  @Test
  public void sourceNamingChange_refreshesExistingDeclaration() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool = new CustomTool(source, "plain", Plain.class);
    source.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    assertThat(
            tool.declaration()
                .orElseThrow()
                .parameters()
                .orElseThrow()
                .properties()
                .orElseThrow()
                .get("request")
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("display_name");
  }

  @Test
  public void mapperSubclassInheritingCopy_preservesConfigurationForAnnotatedType() {
    ObjectMapper source = new ObjectMapper() {};
    source.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    ObjectMapper view = ToolSchemaObjectMapper.forType(source, Customer.class);
    assertThat(view.convertValue(Map.of("customer_id", "42"), Customer.class).id).isEqualTo("42");
    assertThat(view.getPropertyNamingStrategy())
        .isSameInstanceAs(source.getPropertyNamingStrategy());
  }

  @Test
  public void jsonMapperSubclassInheritingCopy_preservesConfiguration() {
    ObjectMapper source = new JsonMapper() {};
    source.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    ObjectMapper view = ToolSchemaObjectMapper.forType(source, Customer.class);
    assertThat(view.convertValue(Map.of("customer_id", "42"), Customer.class).id).isEqualTo("42");
    assertThat(view.getPropertyNamingStrategy())
        .isSameInstanceAs(source.getPropertyNamingStrategy());
    assertThat(source.convertValue(Map.of("business_id", "business"), Customer.class).id)
        .isEqualTo("business");
  }

  @Test
  public void mapperSubclassInheritingCustomCopy_honorsCopyImplementation() {
    ObjectMapper source = new CopyingMapper() {};
    ObjectMapper view = ToolSchemaObjectMapper.forType(source, Customer.class);
    assertThat(view).isInstanceOf(CopyingMapper.class);
    assertThat(view.convertValue(Map.of("customer_id", "42"), Customer.class).id).isEqualTo("42");
  }

  @Test
  public void customCopyFailure_isNotHiddenByFallback() {
    ObjectMapper source =
        new ObjectMapper() {
          @Override
          public ObjectMapper copy() {
            throw new IllegalStateException("custom copy failed");
          }
        };
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () -> ToolSchemaObjectMapper.forType(source, Customer.class)))
        .hasMessageThat()
        .isEqualTo("custom copy failed");
  }

  @Test
  public void inheritedFieldAndChildGetter_haveOneNameInSchemaBindingAndResults() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool = new CustomTool(source, "inheritedCustomer", InheritedCustomer.class);
    assertThat(
            FunctionCallingUtils.buildSchemaFromType(InheritedCustomer.class, source)
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("customer_id");
    assertThat(tool.runAsync(Map.of("request", Map.of("customer_id", "42")), null).blockingGet())
        .containsExactly("customer_id", "42");
    assertThat(source.convertValue(new InheritedCustomer("business"), Map.class))
        .containsExactly("business_id", "business");
  }

  @Test
  public void inheritedSchemaAndChildJacksonGetter_haveOneName() {
    ObjectMapper view = ToolSchemaObjectMapper.forType(new ObjectMapper(), InheritedSchema.class);
    assertThat(view.convertValue(Map.of("customer_id", "42"), InheritedSchema.class).id)
        .isEqualTo("42");
    assertThat(view.convertValue(new InheritedSchema(), Map.class))
        .containsExactly("customer_id", null);
  }

  @Test
  public void conflictingInheritedSchemaNames_failClearly() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> FunctionCallingUtils.buildSchemaFromType(ConflictingInheritedSchema.class)))
        .hasMessageThat()
        .contains("Conflicting @Schema names for property 'id'");
  }

  @Test
  public void parentAndSiblingTypes_doNotShareChildPropertyNames() {
    ObjectMapper source = new ObjectMapper();
    ObjectMapper view = ToolSchemaObjectMapper.forType(source, InheritedCustomer.class);
    assertThat(view.convertValue(new InheritedCustomer("first"), Map.class))
        .containsExactly("customer_id", "first");
    assertThat(view.convertValue(new BusinessCustomer("business"), Map.class))
        .containsExactly("business_id", "business");
    assertThat(view.convertValue(new OtherInheritedCustomer("second"), Map.class))
        .containsExactly("other_id", "second");
    assertThat(view.convertValue(new InheritedCustomer("again"), Map.class))
        .containsExactly("customer_id", "again");
  }

  @Test
  public void visibilityConfiguredBeforeConstruction_appliesToSchemaBindingAndResults()
      throws Exception {
    ObjectMapper source = new ObjectMapper();
    source.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    FunctionTool tool = new CustomTool(source, "privateCustomer", PrivateCustomer.class);
    assertThat(
            tool.declaration()
                .orElseThrow()
                .parameters()
                .orElseThrow()
                .properties()
                .orElseThrow()
                .get("request")
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("customer_id", "note", "secret");
    assertThat(
            tool.runAsync(
                    Map.of(
                        "request",
                        Map.of("customer_id", "42", "note", "visible", "secret", "private")),
                    null)
                .blockingGet())
        .containsExactly("customer_id", "42", "note", "visible", "secret", "private");
  }

  @Test
  public void visibilityConfiguredForUnannotatedType_appliesToDeclaration() throws Exception {
    ObjectMapper source = new ObjectMapper();
    source.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    FunctionTool tool = new CustomTool(source, "privatePlain", PrivatePlain.class);
    assertThat(
            tool.declaration()
                .orElseThrow()
                .parameters()
                .orElseThrow()
                .properties()
                .orElseThrow()
                .get("request")
                .properties()
                .orElseThrow()
                .keySet())
        .containsExactly("displayName", "note");
  }

  @Test
  public void defaultInclusionConfiguredBeforeFirstTool_appliesToResults() throws Exception {
    ObjectMapper source = new ObjectMapper();
    source.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    FunctionTool tool = new CustomTool(source, "privateCustomer", PrivateCustomer.class);
    Map<String, Object> input = Map.of("request", Map.of("customer_id", "42"));
    assertThat(tool.runAsync(input, null).blockingGet()).containsExactly("customer_id", "42");
  }

  @Test
  public void subtypeRegisteredBeforeConstruction_reachesTool() throws Exception {
    ObjectMapper source = new ObjectMapper();
    source.registerSubtypes(new NamedType(Dog.class, "dog"));
    FunctionTool tool = new CustomTool(source, "pet", PetRequest.class);
    assertThat(
            tool.runAsync(
                    Map.of("request", Map.of("pet", Map.of("kind", "dog", "name", "Spot"))), null)
                .blockingGet())
        .containsExactly("name", "Spot");
  }

  @Test
  public void subtypeRegistrationOverloads_configuredBeforeFirstUseArePreserved() {
    ObjectMapper source = new ObjectMapper();
    source.registerSubtypes(Dog.class);
    source.registerSubtypes(List.of(Cat.class));
    ObjectMapper after = ToolSchemaObjectMapper.forType(source, PetRequest.class);
    assertThat(
            after
                .convertValue(
                    Map.of("pet", Map.of("kind", "dog", "name", "Spot")), PetRequest.class)
                .animal
                .getName())
        .isEqualTo("Spot");
    assertThat(
            after
                .convertValue(
                    Map.of("pet", Map.of("kind", "cat", "name", "Mittens")), PetRequest.class)
                .animal
                .getName())
        .isEqualTo("Mittens");
  }

  @Test
  public void defaultSetterConfiguredBeforeFirstUse_appliesToBinding() {
    ObjectMapper source = new ObjectMapper();
    source.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.FAIL));
    Map<String, Object> input = new java.util.HashMap<>();
    input.put("customer_id", null);
    ObjectMapper after = ToolSchemaObjectMapper.forType(source, Customer.class);
    assertThrows(IllegalArgumentException.class, () -> after.convertValue(input, Customer.class));
  }

  @Test
  public void largePojoList_bindsAndConvertsEveryElement() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool = new CustomTool(source, "customers", List.class);
    Map<String, Object> result =
        tool.runAsync(
                Map.of("request", Collections.nCopies(1000, Map.of("customer_id", "42"))), null)
            .blockingGet();
    assertThat((List<?>) result.get("result")).hasSize(1000);
    assertThat(((List<?>) result.get("result")).get(999)).isEqualTo(Map.of("customer_id", "42"));
  }

  @Test
  public void multipleArgumentConversions_useSchemaNames() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool =
        new CustomTool(source, "severalArguments", List.class, Optional.class, Priority.class);
    assertThat(
            tool.runAsync(
                    Map.of(
                        "customers",
                        List.of(Map.of("customer_id", "first")),
                        "customer",
                        Map.of("customer_id", "second"),
                        "priority",
                        "HIGH"),
                    null)
                .blockingGet())
        .containsExactly("customer_id", "second");
  }

  @Test
  public void reactiveResults_keepInvocationMapperUntilSubscription() throws Exception {
    for (String function : List.of("singlePrivateCustomer", "maybePrivateCustomer")) {
      ObjectMapper source = new ObjectMapper();
      FunctionTool tool = new CustomTool(source, function, PrivateCustomer.class);
      Single<Map<String, Object>> pending =
          tool.runAsync(Map.of("request", Map.of("customer_id", "42")), null);
      source.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
      assertThat(pending.blockingGet()).containsEntry("note", null);
      assertThat(tool.runAsync(Map.of("request", Map.of("customer_id", "42")), null).blockingGet())
          .containsExactly("customer_id", "42", "Note", null);
    }
  }

  @Test
  public void callLive_reusesMapperForEveryListElement() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool = new CustomTool(source, "liveCustomers", List.class);
    assertThat(
            tool.callLive(
                    Map.of("request", Collections.nCopies(1000, Map.of("customer_id", "42"))),
                    null,
                    null)
                .blockingFirst())
        .containsExactly("count", 1000);
  }

  @Test
  public void typeOverrideConfiguredBeforeFirstTool_isPreserved() throws Exception {
    ObjectMapper source = new ObjectMapper();
    source
        .configOverride(PrivateCustomer.class)
        .setInclude(
            JsonInclude.Value.construct(
                JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
    FunctionTool tool = new CustomTool(source, "privateCustomer", PrivateCustomer.class);
    assertThat(tool.runAsync(Map.of("request", Map.of("customer_id", "42")), null).blockingGet())
        .containsExactly("customer_id", "42");
  }

  @Test
  public void coercionConfiguredBeforeFirstTool_isPreserved() throws Exception {
    List<Consumer<ObjectMapper>> configurations =
        List.of(
            source ->
                source
                    .coercionConfigDefaults()
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail),
            source ->
                source
                    .coercionConfigFor(LogicalType.Integer)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail),
            source ->
                source
                    .coercionConfigFor(int.class)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    for (Consumer<ObjectMapper> configure : configurations) {
      ObjectMapper source = new ObjectMapper();
      configure.accept(source);
      FunctionTool tool = new CustomTool(source, "numericCustomer", NumericCustomer.class);
      assertThat(tool.runAsync(Map.of("request", Map.of("customer_id", "42")), null).blockingGet())
          .containsEntry("status", "error");
      assertThat(tool.runAsync(Map.of("request", Map.of("customer_id", 42)), null).blockingGet())
          .containsExactly("customer_id", 42);
    }
  }

  public static final class Customer {
    @JsonProperty("business_id")
    @Annotations.Schema(name = "customer_id")
    public String id;
  }

  public static final class Plain {
    public String displayName;
  }

  public static final class NumericCustomer {
    @Annotations.Schema(name = "customer_id")
    public int id;
  }

  public static class BusinessCustomer {
    @JsonProperty("business_id")
    public String id;

    public BusinessCustomer() {}

    BusinessCustomer(String id) {
      this.id = id;
    }
  }

  public static final class InheritedCustomer extends BusinessCustomer {
    public InheritedCustomer() {}

    InheritedCustomer(String id) {
      super(id);
    }

    @Annotations.Schema(name = "customer_id")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class OtherInheritedCustomer extends BusinessCustomer {
    public OtherInheritedCustomer() {}

    OtherInheritedCustomer(String id) {
      super(id);
    }

    @Annotations.Schema(name = "other_id")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static class SchemaCustomer {
    @Annotations.Schema(name = "customer_id")
    public String id;
  }

  public static final class InheritedSchema extends SchemaCustomer {
    @JsonProperty("business_id")
    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }
  }

  public static final class ConflictingInheritedSchema extends SchemaCustomer {
    @Annotations.Schema(name = "other_id")
    public String getId() {
      return id;
    }
  }

  public static final class PrivateCustomer {
    @Annotations.Schema(name = "customer_id")
    public String id;

    private String note;
    private String secret;

    @JsonProperty
    public String getNote() {
      return note;
    }
  }

  public static final class PrivatePlain {
    public String displayName;
    private String note;
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
  public interface Pet {
    String getName();
  }

  @JsonTypeName("dog")
  public static final class Dog implements Pet {
    public String name;

    @Override
    public String getName() {
      return name;
    }
  }

  @JsonTypeName("cat")
  public static final class Cat implements Pet {
    public String name;

    @Override
    public String getName() {
      return name;
    }
  }

  public static final class PetRequest {
    @Annotations.Schema(name = "pet")
    public Pet animal;
  }

  public abstract static class PlainMixin {
    @Annotations.Schema(name = "tool_name")
    public String displayName;
  }

  public abstract static class OtherPlainMixin {
    @Annotations.Schema(name = "other_name")
    public String displayName;
  }

  public static final class Group {
    public List<Customer> members;
  }

  public static final class Recursive {
    public Recursive next;
  }

  public enum Priority {
    HIGH,
    LOW
  }

  public static final class Functions {
    public static Map<String, Object> customer(
        @Annotations.Schema(name = "request") Customer request) {
      return Map.of("id", request.id);
    }

    public static Map<String, Object> plain(@Annotations.Schema(name = "request") Plain request) {
      return Map.of("name", request.displayName);
    }

    public static InheritedCustomer inheritedCustomer(
        @Annotations.Schema(name = "request") InheritedCustomer request) {
      return request;
    }

    public static PrivateCustomer privateCustomer(
        @Annotations.Schema(name = "request") PrivateCustomer request) {
      return request;
    }

    public static PrivatePlain privatePlain(
        @Annotations.Schema(name = "request") PrivatePlain request) {
      return request;
    }

    public static Map<String, Object> pet(
        @Annotations.Schema(name = "request") PetRequest request) {
      return Map.of("name", request.animal.getName());
    }

    public static List<Customer> customers(
        @Annotations.Schema(name = "request") List<Customer> request) {
      return request;
    }

    public static NumericCustomer numericCustomer(
        @Annotations.Schema(name = "request") NumericCustomer request) {
      return request;
    }

    public static Customer severalArguments(
        @Annotations.Schema(name = "customers") List<Customer> customers,
        @Annotations.Schema(name = "customer") Optional<Customer> customer,
        @Annotations.Schema(name = "priority") Priority priority) {
      return customer.orElseThrow();
    }

    public static Single<PrivateCustomer> singlePrivateCustomer(
        @Annotations.Schema(name = "request") PrivateCustomer request) {
      return Single.just(request);
    }

    public static Maybe<PrivateCustomer> maybePrivateCustomer(
        @Annotations.Schema(name = "request") PrivateCustomer request) {
      return Maybe.just(request);
    }

    public static Flowable<Map<String, Object>> liveCustomers(
        @Annotations.Schema(name = "request") List<Customer> request) {
      return Flowable.just(Map.of("count", request.size()));
    }
  }

  private static final class CustomTool extends FunctionTool {
    CustomTool(ObjectMapper mapper, String name, Class<?>... parameters)
        throws NoSuchMethodException {
      super(null, Functions.class.getMethod(name, parameters), false, mapper);
    }
  }

  private static class CopyingMapper extends ObjectMapper {
    CopyingMapper() {}

    CopyingMapper(CopyingMapper source) {
      super(source);
    }

    @Override
    public ObjectMapper copy() {
      return new CopyingMapper(this);
    }
  }
}
