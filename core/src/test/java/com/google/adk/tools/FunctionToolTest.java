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
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.protobuf.Timestamp;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link FunctionTool}. */
@RunWith(JUnit4.class)
public final class FunctionToolTest {

  @Test
  public void create_withNonSerializableParameter_raisesIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> FunctionTool.create(Functions.class, "doThing"));
  }

  @Test
  public void create_withStaticMethod_success() throws NoSuchMethodException {
    Method method = Functions.class.getMethod("voidReturnWithoutSchema");

    FunctionTool tool = FunctionTool.create(method);

    assertThat(tool).isNotNull();
    assertThat(tool.name()).isEqualTo("voidReturnWithoutSchema");
    assertThat(tool.description()).isEmpty();
    assertThat(tool.declaration())
        .hasValue(
            FunctionDeclaration.builder()
                .name("voidReturnWithoutSchema")
                .parameters(
                    Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of())
                        .required(ImmutableList.of())
                        .build())
                .build());
  }

  @Test
  public void create_withClassAndStaticMethodName_success() {
    FunctionTool tool = FunctionTool.create(Functions.class, "voidReturnWithSchemaAndToolContext");

    assertThat(tool).isNotNull();
    assertThat(tool.name()).isEqualTo("my_function");
    assertThat(tool.description()).isEqualTo("A test function");
    assertThat(tool.declaration())
        .hasValue(
            FunctionDeclaration.builder()
                .name("my_function")
                .description("A test function")
                .parameters(
                    Schema.builder()
                        .type("OBJECT")
                        .properties(
                            ImmutableMap.of(
                                "first_param",
                                Schema.builder()
                                    .type("INTEGER")
                                    .description("An integer parameter")
                                    .build(),
                                "second_param",
                                Schema.builder()
                                    .type("STRING")
                                    .description("A string parameter")
                                    .build()))
                        .required(ImmutableList.of("first_param", "second_param"))
                        .build())
                .build());
  }

  @Test
  public void create_withClassAndMethodName_methodNotFound() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FunctionTool.create(Functions.class, "nonExistingMethod"));
  }

  @Test
  public void create_nonStaticMethodWithoutInstance_throwsException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> FunctionTool.create(Functions.class, "nonStaticVoidReturnWithoutSchema"));
  }

  @Test
  public void create_withInstanceAndNonStaticMethodName_success() throws NoSuchMethodException {
    Functions functions = new Functions();
    Method method = Functions.class.getMethod("nonStaticVoidReturnWithoutSchema");

    FunctionTool tool = FunctionTool.create(functions, method);

    assertThat(tool).isNotNull();
    assertThat(tool.name()).isEqualTo("nonStaticVoidReturnWithoutSchema");
    assertThat(tool.description()).isEmpty();
    assertThat(tool.declaration())
        .hasValue(
            FunctionDeclaration.builder()
                .name("nonStaticVoidReturnWithoutSchema")
                .parameters(
                    Schema.builder()
                        .type("OBJECT")
                        .properties(ImmutableMap.of())
                        .required(ImmutableList.of())
                        .build())
                .build());
  }

  @Test
  public void create_withMapReturnType() {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsMap");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().response())
        .hasValue(Schema.builder().type("OBJECT").build());
  }

  @Test
  public void create_withImmutableMapReturnType() {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsImmutableMap");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().response())
        .hasValue(Schema.builder().type("OBJECT").build());
  }

  @Test
  public void create_withAllSupportedParameterTypes() {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnAllSupportedParametersAsMap");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().parameters())
        .hasValue(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    ImmutableMap.<String, Schema>builder()
                        .put("stringParam", Schema.builder().type("STRING").build())
                        .put("primitiveBoolParam", Schema.builder().type("BOOLEAN").build())
                        .put("boolParam", Schema.builder().type("BOOLEAN").build())
                        .put("primitiveIntParam", Schema.builder().type("INTEGER").build())
                        .put("intParam", Schema.builder().type("INTEGER").build())
                        .put("primitiveLongParam", Schema.builder().type("NUMBER").build())
                        .put("longParam", Schema.builder().type("NUMBER").build())
                        .put("primitiveFloatParam", Schema.builder().type("NUMBER").build())
                        .put("floatParam", Schema.builder().type("NUMBER").build())
                        .put("primitiveDoubleParam", Schema.builder().type("NUMBER").build())
                        .put("doubleParam", Schema.builder().type("NUMBER").build())
                        .put(
                            "listParam",
                            Schema.builder()
                                .type("ARRAY")
                                .items(Schema.builder().type("STRING").build())
                                .build())
                        .put("mapParam", Schema.builder().type("OBJECT").build())
                        .buildOrThrow())
                .required(
                    ImmutableList.of(
                        "stringParam",
                        "primitiveBoolParam",
                        "boolParam",
                        "primitiveIntParam",
                        "intParam",
                        "primitiveLongParam",
                        "longParam",
                        "primitiveFloatParam",
                        "floatParam",
                        "primitiveDoubleParam",
                        "doubleParam",
                        "listParam",
                        "mapParam"))
                .build());
  }

  @Test
  public void create_withParameterizedList() {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsParameterizedList");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().parameters())
        .hasValue(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    ImmutableMap.<String, Schema>builder()
                        .put(
                            "listParam",
                            Schema.builder()
                                .type("ARRAY")
                                .items(Schema.builder().type("OBJECT").build())
                                .build())
                        .buildOrThrow())
                .required(ImmutableList.of("listParam"))
                .build());
  }

  @Test
  public void call_withAllSupportedParameterTypes() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnAllSupportedParametersAsMap");
    ToolContext toolContext =
        ToolContext.builder(
                new InvocationContext(
                    /* sessionService= */ null,
                    /* artifactService= */ null,
                    /* memoryService= */ null,
                    /* liveRequestQueue= */ Optional.empty(),
                    /* branch= */ Optional.empty(),
                    /* invocationId= */ null,
                    /* agent= */ null,
                    /* session= */ Session.builder("123").build(),
                    /* userContent= */ Optional.empty(),
                    /* runConfig= */ RunConfig.builder().build(),
                    /* endInvocation= */ false))
            .functionCallId("functionCallId")
            .build();

    Map<String, Object> result =
        tool.runAsync(
                ImmutableMap.<String, Object>builder()
                    .put("stringParam", "stringParam")
                    .put("primitiveBoolParam", true)
                    .put("boolParam", Boolean.FALSE)
                    .put("primitiveIntParam", 1)
                    .put("intParam", Integer.valueOf(2))
                    .put("primitiveLongParam", 3L)
                    .put("longParam", Long.valueOf(4))
                    .put("primitiveFloatParam", 5.0f)
                    .put("floatParam", Float.valueOf(5.0f))
                    .put("primitiveDoubleParam", 7.0)
                    .put("doubleParam", Double.valueOf(8.0))
                    .put("listParam", ImmutableList.of("a", "b"))
                    .put("mapParam", ImmutableMap.of("key1", "value1"))
                    .buildOrThrow(),
                toolContext)
            .blockingGet();

    assertThat(result)
        .containsExactlyEntriesIn(
            ImmutableMap.<String, Object>builder()
                .put("stringParam", "stringParam")
                .put("primitiveBoolParam", true)
                .put("boolParam", Boolean.FALSE)
                .put("primitiveIntParam", 1)
                .put("intParam", Integer.valueOf(2))
                .put("primitiveLongParam", 3L)
                .put("longParam", Long.valueOf(4))
                .put("primitiveFloatParam", 5.0f)
                .put("floatParam", Float.valueOf(5.0f))
                .put("primitiveDoubleParam", 7.0)
                .put("doubleParam", Double.valueOf(8.0))
                .put("listParam", ImmutableList.of("a", "b"))
                .put("mapParam", ImmutableMap.of("key1", "value1"))
                .put("toolContext", toolContext.toString())
                .buildOrThrow());
  }

  @Test
  public void create_withPojoParamWithFields() {
    FunctionTool tool = FunctionTool.create(Functions.class, "pojoParamWithFields");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().parameters())
        .hasValue(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    ImmutableMap.of(
                        "pojo",
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                ImmutableMap.of(
                                    "field1",
                                    Schema.builder().type("STRING").build(),
                                    "field2",
                                    Schema.builder().type("INTEGER").build()))
                            .build()))
                .required(ImmutableList.of("pojo"))
                .build());
  }

  @Test
  public void call_withPojoParamWithFields() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "pojoParamWithFields");
    PojoWithFields pojo = new PojoWithFields();
    pojo.field1 = "abc";
    pojo.field2 = 123;

    Map<String, Object> result = tool.runAsync(ImmutableMap.of("pojo", pojo), null).blockingGet();

    assertThat(result).containsExactly("field1", "abc", "field2", 123);
  }

  @Test
  public void call_withPojoParamWithGettersAndSetters() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "pojoParamWithGettersAndSetters");
    PojoWithGettersAndSetters pojo = new PojoWithGettersAndSetters();
    pojo.setField1("abc");
    pojo.setField2(123);

    Map<String, Object> result = tool.runAsync(ImmutableMap.of("pojo", pojo), null).blockingGet();

    assertThat(result).containsExactly("field1", "abc", "field2", 123);
  }

  @Test
  public void call_withParameterizedListParam() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsParameterizedList");
    ArrayList<Map<String, String>> listParam = new ArrayList<>();
    listParam.add(ImmutableMap.of("key1", "value1"));
    listParam.add(ImmutableMap.of("key2", "value2"));

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("listParam", listParam), null).blockingGet();

    assertThat(result).containsExactly("listParam", listParam);
  }

  @Test
  public void create_withPojoParamWithGettersAndSetters() {
    FunctionTool tool = FunctionTool.create(Functions.class, "pojoParamWithGettersAndSetters");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().parameters())
        .hasValue(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    ImmutableMap.of(
                        "pojo",
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                ImmutableMap.of(
                                    "field1",
                                    Schema.builder().type("STRING").build(),
                                    "field2",
                                    Schema.builder().type("INTEGER").build()))
                            .build()))
                .required(ImmutableList.of("pojo"))
                .build());
  }

  @Test
  public void create_withDiamondDependency_buildsCorrectSchema() {
    FunctionTool tool = FunctionTool.create(Functions.class, "processDiamond");

    // This is the expected schema for the object at the bottom of the diamond.
    Schema bottomSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("name", Schema.builder().type("STRING").build()))
            .build();

    // The full schema should correctly represent the structure, with the full
    // `bottomSchema` appearing in both the 'left' and 'right' branches. If the
    Schema expectedParams =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "top",
                    Schema.builder()
                        .type("OBJECT")
                        .properties(
                            ImmutableMap.of(
                                "left",
                                Schema.builder()
                                    .type("OBJECT")
                                    .properties(ImmutableMap.of("bottom", bottomSchema))
                                    .build(),
                                "right",
                                Schema.builder()
                                    .type("OBJECT")
                                    .properties(ImmutableMap.of("bottom", bottomSchema))
                                    .build()))
                        .build()))
            .required(ImmutableList.of("top"))
            .build();

    assertThat(tool.declaration().get().parameters()).hasValue(expectedParams);
  }

  @Test
  public void create_withCustomGenericType_buildsCorrectSchema() {
    FunctionTool tool = FunctionTool.create(Functions.class, "staticCustomGenericParam");

    assertThat(tool.declaration().get().parameters())
        .hasValue(
            Schema.builder()
                .type("OBJECT")
                .properties(
                    ImmutableMap.of(
                        "customType",
                        Schema.builder()
                            .type("OBJECT")
                            .properties(
                                ImmutableMap.of("value", Schema.builder().type("STRING").build()))
                            .build()))
                .required(ImmutableList.of("customType"))
                .build());
  }

  @Test
  public void create_withRecursiveParam_avoidsInfiniteRecursion() {
    Schema nodeSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "value",
                    Schema.builder().type("STRING").build(),
                    "next", // The recursive field
                    Schema.builder()
                        .type("OBJECT")
                        .description(
                            "Recursive reference to com.google.adk.tools.FunctionToolTest$Node"
                                + " omitted.")
                        .build()))
            .build();
    Schema expectedParameters =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("param", nodeSchema))
            .required(ImmutableList.of("param"))
            .build();

    FunctionTool tool = FunctionTool.create(Functions.class, "recursiveParam");
    assertThat(tool.declaration().get().parameters()).hasValue(expectedParameters);
  }

  @Test
  public void create_withMaybeMapReturnType() {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsMaybeMap");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().response())
        .hasValue(Schema.builder().type("OBJECT").build());
  }

  @Test
  public void call_withMaybeMapReturnType() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsMaybeMap");

    Map<String, Object> result = tool.runAsync(new HashMap<>(), null).blockingGet();

    assertThat(result).containsExactly("key", "value");
  }

  @Test
  public void create_withSingleMapReturnType() {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsSingleMap");

    assertThat(tool).isNotNull();
    assertThat(tool.declaration().get().response())
        .hasValue(Schema.builder().type("OBJECT").build());
  }

  @Test
  public void call_withSingleMapReturnType() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsSingleMap");

    Map<String, Object> result = tool.runAsync(new HashMap<>(), null).blockingGet();

    assertThat(result).containsExactly("key", "value");
  }

  @Test
  public void call_withPojoReturnType() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsPojo");
    Map<String, Object> result = tool.runAsync(ImmutableMap.of(), null).blockingGet();
    assertThat(result).containsExactly("field1", "abc", "field2", 123);
  }

  @Test
  public void call_withSinglePojoReturnType() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsSinglePojo");
    Map<String, Object> result = tool.runAsync(ImmutableMap.of(), null).blockingGet();
    assertThat(result).containsExactly("field1", "abc", "field2", 123);
  }

  @Test
  public void call_withMaybePojoReturnType() throws Exception {
    FunctionTool tool = FunctionTool.create(Functions.class, "returnsMaybePojo");
    Map<String, Object> result = tool.runAsync(ImmutableMap.of(), null).blockingGet();
    assertThat(result).containsExactly("field1", "abc", "field2", 123);
  }

  @Test
  @SuppressWarnings("BooleanLiteral")
  public void call_nonStaticWithAllSupportedParameterTypes() throws Exception {
    Functions functions = new Functions();
    FunctionTool tool =
        FunctionTool.create(functions, "nonStaticReturnAllSupportedParametersAsMap");
    ToolContext toolContext =
        ToolContext.builder(
                new InvocationContext(
                    /* sessionService= */ null,
                    /* artifactService= */ null,
                    /* memoryService= */ null,
                    /* liveRequestQueue= */ Optional.empty(),
                    /* branch= */ Optional.empty(),
                    /* invocationId= */ null,
                    /* agent= */ null,
                    /* session= */ Session.builder("123").build(),
                    /* userContent= */ Optional.empty(),
                    /* runConfig= */ null,
                    /* endInvocation= */ false))
            .functionCallId("functionCallId")
            .build();

    Map<String, Object> result =
        tool.runAsync(
                ImmutableMap.<String, Object>builder()
                    .put("stringParam", "stringParam")
                    .put("primitiveBoolParam", true)
                    .put("boolParam", Boolean.FALSE)
                    .put("primitiveIntParam", 1)
                    .put("intParam", Integer.valueOf(2))
                    .put("primitiveLongParam", 3L)
                    .put("longParam", Long.valueOf(4))
                    .put("primitiveFloatParam", 5.0f)
                    .put("floatParam", Float.valueOf(5.0f))
                    .put("primitiveDoubleParam", 7.0)
                    .put("doubleParam", Double.valueOf(8.0))
                    .put("listParam", ImmutableList.of("a", "b"))
                    .put("mapParam", ImmutableMap.of("key1", "value1"))
                    .buildOrThrow(),
                toolContext)
            .blockingGet();

    assertThat(result)
        .containsExactlyEntriesIn(
            ImmutableMap.<String, Object>builder()
                .put("stringParam", "stringParam")
                .put("primitiveBoolParam", true)
                .put("boolParam", Boolean.FALSE)
                .put("primitiveIntParam", 1)
                .put("intParam", Integer.valueOf(2))
                .put("primitiveLongParam", 3L)
                .put("longParam", Long.valueOf(4))
                .put("primitiveFloatParam", 5.0f)
                .put("floatParam", Float.valueOf(5.0f))
                .put("primitiveDoubleParam", 7.0)
                .put("doubleParam", Double.valueOf(8.0))
                .put("listParam", ImmutableList.of("a", "b"))
                .put("mapParam", ImmutableMap.of("key1", "value1"))
                .put("toolContext", toolContext.toString())
                .buildOrThrow());
  }

  static class Functions {

    @Annotations.Schema(
        name = "doThing",
        description = "This function fetches stats that the agent needs.")
    public static Maybe<Map<String, Object>> doThing(
        @Annotations.Schema(
                name = "recursiveParam",
                description = "Protobuf fields have a recursive property in them.")
            Timestamp recursiveParam) {
      return Maybe.just(ImmutableMap.of("key", "value"));
    }

    @Annotations.Schema(name = "my_function", description = "A test function")
    public static void voidReturnWithSchemaAndToolContext(
        @Annotations.Schema(name = "first_param", description = "An integer parameter") int param1,
        @Annotations.Schema(name = "second_param", description = "A string parameter")
            String param2,
        ToolContext toolContext) {}

    public static void voidReturnWithoutSchema() {}

    public static ImmutableMap<String, Object> returnsMap() {
      return ImmutableMap.of("key", "value");
    }

    public static ImmutableMap<String, Object> returnsImmutableMap() {
      return ImmutableMap.of("key", "value");
    }

    public static ImmutableMap<String, Object> returnAllSupportedParametersAsMap(
        String stringParam,
        boolean primitiveBoolParam,
        Boolean boolParam,
        int primitiveIntParam,
        Integer intParam,
        long primitiveLongParam,
        Long longParam,
        float primitiveFloatParam,
        Float floatParam,
        double primitiveDoubleParam,
        Double doubleParam,
        List<String> listParam,
        Map<String, String> mapParam,
        ToolContext toolContext) {
      return ImmutableMap.<String, Object>builder()
          .put("stringParam", stringParam)
          .put("primitiveBoolParam", primitiveBoolParam)
          .put("boolParam", boolParam)
          .put("primitiveIntParam", primitiveIntParam)
          .put("intParam", intParam)
          .put("primitiveLongParam", primitiveLongParam)
          .put("longParam", longParam)
          .put("primitiveFloatParam", primitiveFloatParam)
          .put("floatParam", floatParam)
          .put("primitiveDoubleParam", primitiveDoubleParam)
          .put("doubleParam", doubleParam)
          .put("listParam", listParam)
          .put("mapParam", mapParam)
          .put("toolContext", toolContext.toString())
          .buildOrThrow();
    }

    public static ImmutableMap<String, Object> returnsParameterizedList(
        List<Map<String, String>> listParam, ToolContext toolContext) {
      return ImmutableMap.<String, Object>builder().put("listParam", listParam).buildOrThrow();
    }

    public static ImmutableMap<String, Object> pojoParamWithFields(PojoWithFields pojo) {
      return ImmutableMap.of("field1", pojo.field1, "field2", pojo.field2);
    }

    public static ImmutableMap<String, Object> pojoParamWithGettersAndSetters(
        PojoWithGettersAndSetters pojo) {
      return ImmutableMap.of("field1", pojo.getField1(), "field2", pojo.getField2());
    }

    public static void processDiamond(DiamondTop top) {}

    public static Maybe<Map<String, Object>> returnsMaybeMap() {
      return Maybe.just(ImmutableMap.of("key", "value"));
    }

    public static Maybe<String> returnsMaybeString() {
      return Maybe.just("not supported");
    }

    public static Single<Map<String, Object>> returnsSingleMap() {
      return Single.just(ImmutableMap.of("key", "value"));
    }

    public static PojoWithGettersAndSetters returnsPojo() {
      PojoWithGettersAndSetters pojo = new PojoWithGettersAndSetters();
      pojo.setField1("abc");
      pojo.setField2(123);
      return pojo;
    }

    public static Single<PojoWithGettersAndSetters> returnsSinglePojo() {
      PojoWithGettersAndSetters pojo = new PojoWithGettersAndSetters();
      pojo.setField1("abc");
      pojo.setField2(123);
      return Single.just(pojo);
    }

    public static Maybe<PojoWithGettersAndSetters> returnsMaybePojo() {
      PojoWithGettersAndSetters pojo = new PojoWithGettersAndSetters();
      pojo.setField1("abc");
      pojo.setField2(123);
      return Maybe.just(pojo);
    }

    public void nonStaticVoidReturnWithoutSchema() {}

    public static ImmutableMap<String, Object> staticCustomGenericParam(
        ParametrizedCustomType<String> customType) {
      return ImmutableMap.of("customType", customType);
    }

    public static ImmutableMap<String, Object> recursiveParam(Node param) {
      return ImmutableMap.of("param", param);
    }

    public ImmutableMap<String, Object> nonStaticReturnAllSupportedParametersAsMap(
        String stringParam,
        boolean primitiveBoolParam,
        Boolean boolParam,
        int primitiveIntParam,
        Integer intParam,
        long primitiveLongParam,
        Long longParam,
        float primitiveFloatParam,
        Float floatParam,
        double primitiveDoubleParam,
        Double doubleParam,
        List<String> listParam,
        Map<String, String> mapParam,
        ToolContext toolContext) {
      return ImmutableMap.<String, Object>builder()
          .put("stringParam", stringParam)
          .put("primitiveBoolParam", primitiveBoolParam)
          .put("boolParam", boolParam)
          .put("primitiveIntParam", primitiveIntParam)
          .put("intParam", intParam)
          .put("primitiveLongParam", primitiveLongParam)
          .put("longParam", longParam)
          .put("primitiveFloatParam", primitiveFloatParam)
          .put("floatParam", floatParam)
          .put("primitiveDoubleParam", primitiveDoubleParam)
          .put("doubleParam", doubleParam)
          .put("listParam", listParam)
          .put("mapParam", mapParam)
          .put("toolContext", toolContext.toString())
          .buildOrThrow();
    }
  }

  public static class PojoWithFields {
    public String field1;
    public int field2;
  }

  public static class PojoWithGettersAndSetters {
    private String privateField1;
    private int privateField2;

    public String getField1() {
      return privateField1;
    }

    public void setField1(String value) {
      privateField1 = value;
    }

    public int getField2() {
      return privateField2;
    }

    public void setField2(int value) {
      privateField2 = value;
    }
  }

  private record DiamondBottom(String name) {}

  private record DiamondLeft(DiamondBottom bottom) {}

  private record DiamondRight(DiamondBottom bottom) {}

  private record DiamondTop(DiamondLeft left, DiamondRight right) {}

  private record ParametrizedCustomType<T>(T value) {}

  private record Node(String value, Node next) {}
}
