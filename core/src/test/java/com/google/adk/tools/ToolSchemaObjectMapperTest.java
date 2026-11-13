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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.adk.JsonBaseModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.Test;

public final class ToolSchemaObjectMapperTest {
  @Test
  public void unannotatedType_reusesOriginalMapperWithoutCopying() {
    CountingMapper source = new CountingMapper();
    assertThat(ToolSchemaObjectMapper.forType(source, Plain.class)).isSameInstanceAs(source);
    assertThat(source.copies).isEqualTo(0);
  }

  @Test
  public void sameConfiguration_reusesWarmMapper() {
    CountingMapper source = new CountingMapper();
    ObjectMapper first = ToolSchemaObjectMapper.forType(source, Customer.class);
    assertThat(first).isNotSameInstanceAs(source);
    assertThat(ToolSchemaObjectMapper.forType(source, Customer.class)).isSameInstanceAs(first);
    assertThat(source.copies).isEqualTo(1);
  }

  @Test
  public void defaultMapper_isSharedAcrossSchemaCalls() {
    ObjectMapper first = ToolSchemaObjectMapper.forType(JsonBaseModel.getMapper(), Customer.class);
    FunctionCallingUtils.buildSchemaFromType(Customer.class);
    assertThat(ToolSchemaObjectMapper.forType(JsonBaseModel.getMapper(), Customer.class))
        .isSameInstanceAs(first);
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
    CountingMapper source = new CountingMapper();
    assertThat(
            ToolSchemaObjectMapper.forMethod(
                source, Functions.class.getMethod("plain", Plain.class)))
        .isSameInstanceAs(source);
    assertThat(source.copies).isEqualTo(0);
  }

  @Test
  public void multipleTools_shareOneConfiguredCopy() throws Exception {
    CountingMapper source = new CountingMapper();
    FunctionTool first = new CustomTool(source, "customer", Customer.class);
    FunctionTool second = new CustomTool(source, "customer", Customer.class);
    FunctionCallingUtils.buildSchemaFromType(Customer.class, source);
    assertThat(source.copies).isEqualTo(1);
    assertThat(first.declaration()).isEqualTo(second.declaration());
  }

  @Test
  public void mixInAddedAfterConstruction_updatesSchemaAndBinding() throws Exception {
    ObjectMapper source = new ObjectMapper();
    FunctionTool tool = new CustomTool(source, "plain", Plain.class);
    source.addMixIn(Plain.class, PlainMixin.class);
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
  public void unchangedView_reusesCachedDeclaration() throws Exception {
    FunctionTool tool = new CustomTool(new ObjectMapper(), "customer", Customer.class);
    assertThat(tool.declaration().orElseThrow()).isSameInstanceAs(tool.declaration().orElseThrow());
  }

  @Test
  public void replacingMixIn_invalidatesViewEvenWhenCountIsUnchanged() {
    ObjectMapper source = new ObjectMapper().addMixIn(Plain.class, PlainMixin.class);
    ObjectMapper previous = ToolSchemaObjectMapper.forType(source, Plain.class);
    source.addMixIn(Plain.class, OtherPlainMixin.class);
    assertThat(ToolSchemaObjectMapper.forType(source, Plain.class)).isNotSameInstanceAs(previous);
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
    CountingMapper source = new CountingMapper();
    assertThat(ToolSchemaObjectMapper.forType(source, Recursive.class)).isSameInstanceAs(source);
    assertThat(source.copies).isEqualTo(0);
  }

  @Test
  public void moduleRegisteredAfterConstruction_refreshesAnnotatedTool() throws Exception {
    CountingMapper source = new CountingMapper();
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
    assertThat(source.copies).isEqualTo(2);
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
  public void concurrentFirstUse_createsOneCopy() throws Exception {
    CountingMapper source = new CountingMapper();
    var executor = Executors.newFixedThreadPool(4);
    try {
      List<Callable<ObjectMapper>> tasks = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        tasks.add(() -> ToolSchemaObjectMapper.forType(source, Customer.class));
      }
      ObjectMapper expected = null;
      for (var result : executor.invokeAll(tasks)) {
        ObjectMapper mapper = result.get();
        if (expected == null) {
          expected = mapper;
        }
        assertThat(mapper).isSameInstanceAs(expected);
      }
      assertThat(source.copies).isEqualTo(1);
    } finally {
      executor.shutdownNow();
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

  public static final class Functions {
    public static Map<String, Object> customer(
        @Annotations.Schema(name = "request") Customer request) {
      return Map.of("id", request.id);
    }

    public static Map<String, Object> plain(@Annotations.Schema(name = "request") Plain request) {
      return Map.of("name", request.displayName);
    }
  }

  private static final class CustomTool extends FunctionTool {
    CustomTool(ObjectMapper mapper, String name, Class<?> parameter) throws NoSuchMethodException {
      super(null, Functions.class.getMethod(name, parameter), false, mapper);
    }
  }

  private static final class CountingMapper extends ObjectMapper {
    int copies;

    CountingMapper() {}

    CountingMapper(CountingMapper source) {
      super(source);
    }

    @Override
    public ObjectMapper copy() {
      copies++;
      return new CountingMapper(this);
    }
  }
}
