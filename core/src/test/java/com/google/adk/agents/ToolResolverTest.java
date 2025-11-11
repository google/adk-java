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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ToolResolver}. */
@RunWith(JUnit4.class)
public final class ToolResolverTest {

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

    BaseTool resolved = ToolResolver.resolveToolInstance("test.tool.instance");

    assertThat(resolved).isEqualTo(testTool);
  }

  @Test
  public void testResolveToolInstance_viaReflection() throws Exception {
    String toolName = TestToolWithStaticField.class.getName() + ".INSTANCE";

    BaseTool resolved = ToolResolver.resolveToolInstance(toolName);

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

    BaseToolset resolved = ToolResolver.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isSameInstanceAs(TestToolsetWithStaticField.TEST_TOOLSET);
  }

  @Test
  public void testResolveInstanceViaReflection_extractsCorrectFieldName() throws Exception {
    // This test ensures that the field name is extracted correctly
    // and will fail if substring(lastDotIndex) or substring(lastDotIndex + 1 - 1) is used
    String toolName = TestToolWithDifferentFieldName.class.getName() + ".SPECIAL_INSTANCE";

    BaseTool resolved = ToolResolver.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isSameInstanceAs(TestToolWithDifferentFieldName.SPECIAL_INSTANCE);
    assertThat(resolved.name()).isEqualTo("special_tool");
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_noDotInName_returnsNull() throws Exception {
    String toolsetName = "NoDotsHere";

    BaseToolset resolved = ToolResolver.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_nonStaticField_returnsNull()
      throws Exception {
    String toolsetName = TestToolsetWithNonStaticField.class.getName() + ".instanceField";

    BaseToolset resolved = ToolResolver.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_nonBaseToolsetField_returnsNull()
      throws Exception {
    String toolsetName = TestClassWithNonToolsetField.class.getName() + ".NOT_A_TOOLSET";

    BaseToolset resolved = ToolResolver.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_fieldNotFound_returnsNull() throws Exception {
    String toolsetName = TestToolsetWithStaticField.class.getName() + ".NONEXISTENT_FIELD";

    BaseToolset resolved = ToolResolver.resolveToolsetInstanceViaReflection(toolsetName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolsetInstanceViaReflection_classNotFound_throwsException() {
    String toolsetName = "com.nonexistent.package.NonExistentClass.FIELD";

    assertThrows(
        ClassNotFoundException.class,
        () -> ToolResolver.resolveToolsetInstanceViaReflection(toolsetName));
  }

  @Test
  public void testResolveInstanceViaReflection_noDotInName_returnsNull() throws Exception {
    String toolName = "NoDotsHere";

    BaseTool resolved = ToolResolver.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_nonStaticField_returnsNull() throws Exception {
    String toolName = TestToolWithNonStaticField.class.getName() + ".instanceField";

    BaseTool resolved = ToolResolver.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_nonBaseToolField_returnsNull() throws Exception {
    String toolName = TestClassWithNonToolField.class.getName() + ".NOT_A_TOOL";

    BaseTool resolved = ToolResolver.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_fieldNotFound_returnsNull() throws Exception {
    String toolName = TestToolWithStaticField.class.getName() + ".NONEXISTENT_FIELD";

    BaseTool resolved = ToolResolver.resolveInstanceViaReflection(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveInstanceViaReflection_classNotFound_throwsException() {
    String toolName = "com.nonexistent.package.NonExistentClass.FIELD";

    assertThrows(
        ClassNotFoundException.class, () -> ToolResolver.resolveInstanceViaReflection(toolName));
  }

  @Test
  public void testResolveToolInstance_withInvalidReflectionPath_returnsNull() {
    String toolName = "com.invalid.Class.FIELD";

    BaseTool resolved = ToolResolver.resolveToolInstance(toolName);

    assertThat(resolved).isNull();
  }

  @Test
  public void testResolveToolFromClass_withFromConfigMethod() throws Exception {
    String className = TestToolWithFromConfig.class.getName();
    BaseTool.ToolArgsConfig args =
        new BaseTool.ToolArgsConfig().put("key1", "value1").put("key2", 42).put("key3", true);

    BaseTool resolved = ToolResolver.resolveToolFromClass(className, args);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isInstanceOf(TestToolWithFromConfig.class);
  }

  @Test
  public void testResolveToolFromClass_withDefaultConstructor() throws Exception {
    String className = TestToolWithDefaultConstructor.class.getName();

    // Test resolving tool from class with default constructor (no args)
    BaseTool resolved = ToolResolver.resolveToolFromClass(className, null);

    assertThat(resolved).isNotNull();
    assertThat(resolved).isInstanceOf(TestToolWithDefaultConstructor.class);
  }

  @Test
  public void testResolveToolFromClass_missingFromConfigWithArgs() {
    String className = TestToolWithoutFromConfig.class.getName();
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig().put("testKey", "testValue");

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> ToolResolver.resolveToolFromClass(className, args));

    assertThat(exception)
        .hasMessageThat()
        .contains("does not have fromConfig method but args were provided");
  }

  @Test
  public void testResolveToolFromClass_missingDefaultConstructor() {
    String className = TestToolWithoutDefaultConstructor.class.getName();

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> ToolResolver.resolveToolFromClass(className, null));

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

    ImmutableList<BaseTool> resolved = ToolResolver.resolveTools(toolConfigs, "/test/path");

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
            ConfigurationException.class,
            () -> ToolResolver.resolveTools(toolConfigs, "/test/path"));

    assertThat(exception).hasMessageThat().contains("Failed to resolve tool: non.existent.Tool");
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
