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

package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.assertEqualIgnoringFunctionIds;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.Model;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LlmAgent}. */
@RunWith(JUnit4.class)
public final class LlmAgentTest {

  private static ComponentRegistry originalRegistry;
  private TestComponentRegistry testRegistry;

  @BeforeClass
  public static void saveOriginalRegistry() {
    originalRegistry = ComponentRegistry.getInstance();
  }

  @Before
  public void setUp() {
    testRegistry = new TestComponentRegistry();
    ComponentRegistry.setInstance(testRegistry);
  }

  @After
  public void tearDown() {
    ComponentRegistry.setInstance(originalRegistry);
  }

  @Test
  public void testResolveToolInstance_fromRegistry() throws Exception {
    FunctionTool testTool = FunctionTool.create(TestToolWithMethods.class, "method1");
    testRegistry.register("test.tool.instance", testTool);

    BaseTool resolved = LlmAgent.resolveToolInstance("test.tool.instance");

    assertThat(resolved).isEqualTo(testTool);
  }

  @Test
  public void testResolveToolInstance_viaReflection() throws Exception {
    String toolName = TestToolWithStaticField.class.getName() + ".INSTANCE";

    BaseTool resolved = LlmAgent.resolveToolInstance(toolName);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isInstanceOf(TestToolWithStaticField.class);

    // Should now be registered for reuse
    Optional<BaseTool> resolvedAgain = ComponentRegistry.resolveToolInstance(toolName);
    assertThat(resolvedAgain).isPresent();
    assertThat(resolvedAgain.get()).isSameInstanceAs(resolved);
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_extractsFullClassName() throws Exception {
    // This test ensures that the full class name is extracted correctly
    // and will fail if substring(1, lastDotIndex) is used instead of substring(0, lastDotIndex)
    String toolsetName = TestToolsetWithStaticField.class.getName() + ".TEST_TOOLSET";

    BaseToolset resolved = LlmAgent.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isSameInstanceAs(TestToolsetWithStaticField.TEST_TOOLSET);
  }

  @Test
  public void testResolveInstanceViaReflection_extractsCorrectFieldName() throws Exception {
    // This test ensures that the field name is extracted correctly
    // and will fail if substring(lastDotIndex) or substring(lastDotIndex + 1 - 1) is used
    String toolName = TestToolWithDifferentFieldName.class.getName() + ".SPECIAL_INSTANCE";

    BaseTool resolved = LlmAgent.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isSameInstanceAs(TestToolWithDifferentFieldName.SPECIAL_INSTANCE);
    assertThat(resolved.name()).isEqualTo("special_tool");
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_noDotInName_returnsNull() throws Exception {
    String toolsetName = "NoDotsHere";

    BaseToolset resolved = LlmAgent.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_nonStaticField_returnsNull()
      throws Exception {
    String toolsetName = TestToolsetWithNonStaticField.class.getName() + ".instanceField";

    BaseToolset resolved = LlmAgent.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_nonBaseToolsetField_returnsNull()
      throws Exception {
    String toolsetName = TestClassWithNonToolsetField.class.getName() + ".NOT_A_TOOLSET";

    BaseToolset resolved = LlmAgent.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_fieldNotFound_returnsNull() throws Exception {
    String toolsetName = TestToolsetWithStaticField.class.getName() + ".NONEXISTENT_FIELD";

    BaseToolset resolved = LlmAgent.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_classNotFound_throwsException() {
    String toolsetName = "com.nonexistent.package.NonExistentClass.FIELD";

    assertThrows(
        ClassNotFoundException.class,
        () -> LlmAgent.resolveToolsetInstanceViaReflection(toolsetName));
  }

  @Test
  public void testResolveInstanceViaReflection_noDotInName_returnsNull() throws Exception {
    String toolName = "NoDotsHere";

    BaseTool resolved = LlmAgent.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_nonStaticField_returnsNull() throws Exception {
    String toolName = TestToolWithNonStaticField.class.getName() + ".instanceField";

    BaseTool resolved = LlmAgent.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_nonBaseToolField_returnsNull() throws Exception {
    String toolName = TestClassWithNonToolField.class.getName() + ".NOT_A_TOOL";

    BaseTool resolved = LlmAgent.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_fieldNotFound_returnsNull() throws Exception {
    String toolName = TestToolWithStaticField.class.getName() + ".NONEXISTENT_FIELD";

    BaseTool resolved = LlmAgent.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_classNotFound_throwsException() {
    String toolName = "com.nonexistent.package.NonExistentClass.FIELD";

    assertThrows(
        ClassNotFoundException.class, () -> LlmAgent.resolveInstanceViaReflection(toolName));
  }

  @Test
  public void testResolveToolInstance_withInvalidReflectionPath_returnsNull() {
    String toolName = "com.invalid.Class.FIELD";

    BaseTool resolved = LlmAgent.resolveToolInstance(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolFromClass_withFromConfigMethod() throws Exception {
    String className = TestToolWithFromConfig.class.getName();
    BaseTool.ToolArgsConfig args =
        new BaseTool.ToolArgsConfig().put("key1", "value1").put("key2", 42).put("key3", true);

    BaseTool resolved = LlmAgent.resolveToolFromClass(className, args);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isInstanceOf(TestToolWithFromConfig.class);
  }

  @Test
  public void testResolveToolFromClass_withDefaultConstructor() throws Exception {
    String className = TestToolWithDefaultConstructor.class.getName();

    // Test resolving tool from class with default constructor (no args)
    BaseTool resolved = LlmAgent.resolveToolFromClass(className, null);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isInstanceOf(TestToolWithDefaultConstructor.class);
  }

  @Test
  public void testResolveToolFromClass_missingFromConfigWithArgs() {
    String className = TestToolWithoutFromConfig.class.getName();
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig().put("testKey", "testValue");

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> LlmAgent.resolveToolFromClass(className, args));

    assertThat(exception)
        .hasMessageThat()
        .contains("does not have fromConfig method but args were provided");
  }

  @Test
  public void testResolveToolFromClass_missingDefaultConstructor() {
    String className = TestToolWithoutDefaultConstructor.class.getName();

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> LlmAgent.resolveToolFromClass(className, null));

    assertThat(exception).hasMessageThat().contains("does not have a default constructor");
  }

  @Test
  public void testResolveTools_mixedTypes() throws Exception {
    // Register one tool instance
    FunctionTool registeredTool = FunctionTool.create(TestToolWithMethods.class, "method1");
    testRegistry.register("registered.tool", registeredTool);

    ImmutableList<BaseTool.ToolConfig> toolConfigs =
        ImmutableList.of(
            createToolConfig("registered.tool", null), // From registry
            createToolConfig(TestToolWithDefaultConstructor.class.getName(), null), // From class
            createToolConfig(
                TestToolWithStaticField.class.getName() + ".INSTANCE", null) // Via reflection
            );

    ImmutableList<BaseTool> resolved = LlmAgent.resolveTools(toolConfigs, "/test/path");

    assertThat(resolved).hasSize(3);
    assertThat(resolved.get(0)).isEqualTo(registeredTool);
    assertThat(resolved.get(1)).isInstanceOf(TestToolWithDefaultConstructor.class);
    assertThat(resolved.get(2)).isInstanceOf(TestToolWithStaticField.class);
  }

  @Test
  public void testResolveTools_toolNotFound() {
    ImmutableList<BaseTool.ToolConfig> toolConfigs =
        ImmutableList.of(createToolConfig("non.existent.Tool", null));

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> LlmAgent.resolveTools(toolConfigs, "/test/path"));

    assertThat(exception).hasMessageThat().contains("Failed to resolve tool: non.existent.Tool");
  }

  @Test
  public void testRun_withNoCallbacks() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgent(testLlm);
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(modelContent);
  }

  @Test
  public void testRun_withOutputKey_savesState() {
    Content modelContent = Content.fromParts(Part.fromText("Saved output"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).containsEntry("myOutput", "Saved output");
  }

  @Test
  public void testRun_withOutputKey_savesMultiPartState() {
    Content modelContent = Content.fromParts(Part.fromText("Part 1."), Part.fromText(" Part 2."));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myMultiPartOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta())
        .containsEntry("myMultiPartOutput", "Part 1. Part 2.");
  }

  @Test
  public void testRun_withoutOutputKey_doesNotSaveState() {
    Content modelContent = Content.fromParts(Part.fromText("Some output"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).isEmpty();
  }

  @Test
  public void run_withToolsAndMaxSteps_stopsAfterMaxSteps() {
    ImmutableMap<String, Object> echoArgs = ImmutableMap.of("arg", "value");
    Content contentWithFunctionCall =
        Content.fromParts(Part.fromText("text"), Part.fromFunctionCall("echo_tool", echoArgs));
    Content unreachableContent = Content.fromParts(Part.fromText("This should never be returned."));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(contentWithFunctionCall),
            createLlmResponse(contentWithFunctionCall),
            createLlmResponse(unreachableContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).tools(new EchoTool()).maxSteps(2).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    Content expectedFunctionResponseContent =
        Content.fromParts(
            Part.fromFunctionResponse(
                "echo_tool", ImmutableMap.<String, Object>of("result", echoArgs)));
    assertThat(events).hasSize(4);
    assertEqualIgnoringFunctionIds(events.get(0).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(events.get(1).content().get(), expectedFunctionResponseContent);
    assertEqualIgnoringFunctionIds(events.get(2).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(events.get(3).content().get(), expectedFunctionResponseContent);
  }

  @Test
  public void build_withOutputSchemaAndTools_throwsIllegalArgumentException() {
    BaseTool tool =
        new BaseTool("test_tool", "test_description") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.empty();
          }
        };

    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("status", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("status"))
            .build();

    // Expecting an IllegalArgumentException when building the agent
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LlmAgent.builder() // Use the agent builder directly
                    .name("agent with invalid tool config")
                    .outputSchema(outputSchema) // Set the output schema
                    .tools(ImmutableList.of(tool)) // Set tools (this should cause the error)
                    .build()); // Attempt to build the agent

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Invalid config for agent agent with invalid tool config: if outputSchema is set, tools"
                + " must be empty");
  }

  @Test
  public void build_withOutputSchemaAndSubAgents_throwsIllegalArgumentException() {
    ImmutableList<BaseAgent> subAgents =
        ImmutableList.of(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .name("test_sub_agent")
                .description("test_sub_agent_description")
                .build());

    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("status", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("status"))
            .build();

    // Expecting an IllegalArgumentException when building the agent
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LlmAgent.builder() // Use the agent builder directly
                    .name("agent with invalid tool config")
                    .outputSchema(outputSchema) // Set the output schema
                    .subAgents(subAgents) // Set subAgents (this should cause the error)
                    .build()); // Attempt to build the agent

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Invalid config for agent agent with invalid tool config: if outputSchema is set,"
                + " subAgents must be empty to disable agent transfer.");
  }

  @Test
  public void testBuild_withNullInstruction_setsInstructionToEmptyString() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction((String) null)
            .build();

    assertThat(agent.instruction()).isEqualTo(new Instruction.Static(""));
  }

  @Test
  public void testCanonicalInstruction_acceptsPlainString() {
    String instruction = "Test static instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void testBuild_withNullGlobalInstruction_setsGlobalInstructionToEmptyString() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction((String) null)
            .build();

    assertThat(agent.globalInstruction()).isEqualTo(new Instruction.Static(""));
  }

  @Test
  public void testCanonicalGlobalInstruction_acceptsPlainString() {
    String instruction = "Test static global instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalGlobalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalGlobalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider global instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalGlobalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void resolveModel_withModelName_resolvesFromRegistry() {
    String modelName = "test-model";
    TestLlm testLlm = createTestLlm(LlmResponse.builder().build());
    LlmRegistry.registerLlm(modelName, (name) -> testLlm);
    LlmAgent agent = createTestAgentBuilder(testLlm).model(modelName).build();
    Model resolvedModel = agent.resolvedModel();

    assertThat(resolvedModel.modelName()).hasValue(modelName);
    assertThat(resolvedModel.model()).hasValue(testLlm);
  }

  /** A test tool with multiple methods annotated with @Schema. */
  public static class TestToolWithMethods {
    @Annotations.Schema(name = "method1", description = "This is the first test method.")
    public static String method1(
        @Annotations.Schema(name = "param1", description = "A test parameter") String param1) {
      return "method1 response: " + param1;
    }

    @Annotations.Schema(name = "method2", description = "This is the second test method.")
    public static int method2(
        @Annotations.Schema(name = "param2", description = "Another test parameter") int param2) {
      return param2 * 2;
    }

    // This method is not annotated and should not be picked up
    public void nonToolMethod() {
      // No-op
    }
  }

  /** A public subclass of ComponentRegistry to allow instantiation in the test. */
  public static class TestComponentRegistry extends ComponentRegistry {
    public TestComponentRegistry() {
      super();
    }
  }

  // Helper test classes
  public static class TestToolWithStaticField extends BaseTool {
    public static final TestToolWithStaticField INSTANCE = new TestToolWithStaticField();

    private TestToolWithStaticField() {
      super("test_tool", "Test tool description");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static class TestToolsetWithStaticField implements BaseToolset {
    public static final TestToolsetWithStaticField TEST_TOOLSET = new TestToolsetWithStaticField();

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
      return Flowable.empty();
    }

    @Override
    public void close() throws Exception {
      // No resources to clean up
    }
  }

  public static class TestToolWithDifferentFieldName extends BaseTool {
    public static final TestToolWithDifferentFieldName SPECIAL_INSTANCE =
        new TestToolWithDifferentFieldName();

    private TestToolWithDifferentFieldName() {
      super("special_tool", "Test tool with different field name");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static class TestToolWithFromConfig extends BaseTool {
    private TestToolWithFromConfig(String param) {
      super("test_tool_from_config", "Test tool from config: " + param);
    }

    public static TestToolWithFromConfig fromConfig(BaseTool.ToolArgsConfig args) {
      return new TestToolWithFromConfig("test_param");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static class TestToolWithDefaultConstructor extends BaseTool {
    public TestToolWithDefaultConstructor() {
      super("test_tool_default", "Test tool with default constructor");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static class TestToolWithoutFromConfig extends BaseTool {
    public TestToolWithoutFromConfig() {
      super("test_tool_no_from_config", "Test tool without fromConfig");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static class TestToolWithoutDefaultConstructor extends BaseTool {
    public TestToolWithoutDefaultConstructor(String required) {
      super("test_tool_no_default", "Test tool without default constructor");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static class TestToolsetWithNonStaticField implements BaseToolset {
    public final BaseToolset instanceField = new TestToolsetWithStaticField();

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
      return Flowable.empty();
    }

    @Override
    public void close() throws Exception {
      // No resources to clean up
    }
  }

  public static final class TestClassWithNonToolsetField {
    public static final String NOT_A_TOOLSET = "This is not a BaseToolset";

    private TestClassWithNonToolsetField() {}
  }

  public static class TestToolWithNonStaticField extends BaseTool {
    public final BaseTool instanceField = new TestToolWithStaticField();

    public TestToolWithNonStaticField() {
      super("test_tool_non_static", "Test tool with non-static field");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.empty();
    }
  }

  public static final class TestClassWithNonToolField {
    public static final String NOT_A_TOOL = "This is not a BaseTool";

    private TestClassWithNonToolField() {}
  }

  private BaseTool.ToolConfig createToolConfig(String name, BaseTool.ToolArgsConfig args) {
    return new BaseTool.ToolConfig(name, args);
  }
}
