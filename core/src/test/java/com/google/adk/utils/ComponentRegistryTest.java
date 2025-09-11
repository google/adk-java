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

package com.google.adk.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.tools.mcp.McpToolset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ComponentRegistryTest {

  @Test
  public void testPreWiredEntries() {
    ComponentRegistry registry = new ComponentRegistry();

    Optional<GoogleSearchTool> searchTool = registry.get("google_search", GoogleSearchTool.class);
    assertThat(searchTool).isPresent();
  }

  @Test
  public void testRegisterAndGet() {
    ComponentRegistry registry = new ComponentRegistry();
    String testValue = "test value";

    registry.register("test_key", testValue);

    Optional<String> result = registry.get("test_key", String.class);
    assertThat(result).hasValue(testValue);
  }

  @Test
  public void testGetWithoutType() {
    ComponentRegistry registry = new ComponentRegistry();
    String testValue = "test value";

    registry.register("test_key", testValue);

    Optional<Object> result = registry.get("test_key");
    assertThat(result).hasValue(testValue);
  }

  @Test
  public void testGetNonExistentKey() {
    ComponentRegistry registry = new ComponentRegistry();

    Optional<String> result = registry.get("non_existent", String.class);
    assertThat(result).isEmpty();

    Optional<Object> resultNoType = registry.get("non_existent");
    assertThat(resultNoType).isEmpty();
  }

  @Test
  public void testGetWithWrongType() {
    ComponentRegistry registry = new ComponentRegistry();
    registry.register("test_key", "string value");

    Optional<Integer> result = registry.get("test_key", Integer.class);
    assertThat(result).isEmpty();
  }

  @Test
  public void testOverridePreWiredEntry() {
    ComponentRegistry registry = new ComponentRegistry();
    String customSearchTool = "custom search tool";

    registry.register("google_search", customSearchTool);

    Optional<String> result = registry.get("google_search", String.class);
    assertThat(result).hasValue(customSearchTool);

    Optional<GoogleSearchTool> originalTool = registry.get("google_search", GoogleSearchTool.class);
    assertThat(originalTool).isEmpty();
  }

  @Test
  public void testRegisterWithNullName() {
    ComponentRegistry registry = new ComponentRegistry();

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> registry.register(null, "value"));

    assertThat(thrown).hasMessageThat().contains("Name cannot be null or empty");
  }

  @Test
  public void testRegisterWithEmptyName() {
    ComponentRegistry registry = new ComponentRegistry();

    IllegalArgumentException thrown1 =
        assertThrows(IllegalArgumentException.class, () -> registry.register("", "value"));
    IllegalArgumentException thrown2 =
        assertThrows(IllegalArgumentException.class, () -> registry.register("   ", "value"));

    assertThat(thrown1).hasMessageThat().contains("Name cannot be null or empty");
    assertThat(thrown2).hasMessageThat().contains("Name cannot be null or empty");
  }

  @Test
  public void testGetWithNullName() {
    ComponentRegistry registry = new ComponentRegistry();

    Optional<String> result = registry.get(null, String.class);
    assertThat(result).isEmpty();

    Optional<Object> resultNoType = registry.get(null);
    assertThat(resultNoType).isEmpty();
  }

  @Test
  public void testGetWithEmptyName() {
    ComponentRegistry registry = new ComponentRegistry();

    Optional<String> result = registry.get("", String.class);
    assertThat(result).isEmpty();

    Optional<String> resultWhitespace = registry.get("   ", String.class);
    assertThat(resultWhitespace).isEmpty();
  }

  @Test
  public void testRegisterNullValue() {
    ComponentRegistry registry = new ComponentRegistry();

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> registry.register("null_test", null));

    assertThat(thrown).hasMessageThat().contains("Value cannot be null");
  }

  @Test
  public void testSubclassExtension() {
    class CustomComponentRegistry extends ComponentRegistry {
      CustomComponentRegistry() {
        super();
        register("custom_tool", "my custom tool");
        register("custom_agent", new Object());
      }
    }

    CustomComponentRegistry registry = new CustomComponentRegistry();

    Optional<GoogleSearchTool> prewiredTool = registry.get("google_search", GoogleSearchTool.class);
    assertThat(prewiredTool).isPresent();

    Optional<String> customTool = registry.get("custom_tool", String.class);
    assertThat(customTool).hasValue("my custom tool");

    Optional<Object> customAgent = registry.get("custom_agent");
    assertThat(customAgent).isPresent();
  }

  @Test
  public void testResolveAgentClass() {
    // Test all 4 agent classes can be resolved by simple name
    Class<? extends BaseAgent> llmAgentClass = ComponentRegistry.resolveAgentClass("LlmAgent");
    assertThat(llmAgentClass).isEqualTo(LlmAgent.class);

    Class<? extends BaseAgent> loopAgentClass = ComponentRegistry.resolveAgentClass("LoopAgent");
    assertThat(loopAgentClass).isEqualTo(LoopAgent.class);

    Class<? extends BaseAgent> parallelAgentClass =
        ComponentRegistry.resolveAgentClass("ParallelAgent");
    assertThat(parallelAgentClass).isEqualTo(ParallelAgent.class);

    Class<? extends BaseAgent> sequentialAgentClass =
        ComponentRegistry.resolveAgentClass("SequentialAgent");
    assertThat(sequentialAgentClass).isEqualTo(SequentialAgent.class);

    // Test default behavior (null/empty returns LlmAgent)
    assertThat(ComponentRegistry.resolveAgentClass(null)).isEqualTo(LlmAgent.class);
    assertThat(ComponentRegistry.resolveAgentClass("")).isEqualTo(LlmAgent.class);

    // Test full class name resolution
    Class<? extends BaseAgent> llmAgentFullName =
        ComponentRegistry.resolveAgentClass("com.google.adk.agents.LlmAgent");
    assertThat(llmAgentFullName).isEqualTo(LlmAgent.class);

    // Test unsupported agent class
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> ComponentRegistry.resolveAgentClass("UnsupportedAgent"));
    assertThat(thrown).hasMessageThat().contains("not in registry or not a subclass of BaseAgent");
  }

  @Test
  public void testResolveToolClass_withSimpleName() {
    ComponentRegistry.getInstance().register("GoogleSearchTool", GoogleSearchTool.class);

    Optional<Class<? extends BaseTool>> googleSearchClass =
        ComponentRegistry.resolveToolClass("GoogleSearchTool");
    assertThat(googleSearchClass).isPresent();
    assertThat(googleSearchClass.get()).isEqualTo(GoogleSearchTool.class);
  }

  @Test
  public void testResolveToolClass_withGoogleAdkToolsPrefix() {
    Optional<Class<? extends BaseTool>> googleSearchClass =
        ComponentRegistry.resolveToolClass("google_search");
    assertThat(googleSearchClass).isEmpty();

    ComponentRegistry.getInstance().register("google.adk.tools.TestTool", GoogleSearchTool.class);

    Optional<Class<? extends BaseTool>> testToolClass =
        ComponentRegistry.resolveToolClass("TestTool");
    assertThat(testToolClass).isPresent();
    assertThat(testToolClass.get()).isEqualTo(GoogleSearchTool.class);
  }

  @Test
  public void testResolveToolClass_withComGoogleAdkToolsPrefix() {
    // Register only with com.google.adk.tools prefix and ensure simple name resolves
    ComponentRegistry.getInstance()
        .register("com.google.adk.tools.TestTool", GoogleSearchTool.class);

    Optional<Class<? extends BaseTool>> testToolClass =
        ComponentRegistry.resolveToolClass("TestTool");
    assertThat(testToolClass).isPresent();
    assertThat(testToolClass.get()).isEqualTo(GoogleSearchTool.class);
  }

  @Test
  public void testResolveAgentClass_withGoogleAdkAgentsPrefixForSimpleName() {
    // Register only with google.adk.agents prefix and ensure simple name resolves
    ComponentRegistry.getInstance().register("google.adk.agents.CustomAgent", LlmAgent.class);

    Class<? extends BaseAgent> resolved = ComponentRegistry.resolveAgentClass("CustomAgent");
    assertThat(resolved).isEqualTo(LlmAgent.class);
  }

  @Test
  public void testResolveToolsetClass_withGoogleAdkToolsPrefix() {
    ComponentRegistry registry = ComponentRegistry.getInstance();
    registry.register("google.adk.tools.TestToolset", McpToolset.class);

    Optional<Class<? extends BaseToolset>> testToolsetClass =
        ComponentRegistry.resolveToolsetClass("TestToolset");
    assertThat(testToolsetClass).isPresent();
    assertThat(testToolsetClass.get()).isEqualTo(McpToolset.class);

    registry.register("google.adk.tools.TestSimpleToolset", McpToolset.class);
    Optional<Class<? extends BaseToolset>> simpleResolved =
        ComponentRegistry.resolveToolsetClass("TestSimpleToolset");
    assertThat(simpleResolved).isPresent();
    assertThat(simpleResolved.get()).isEqualTo(McpToolset.class);
  }

  @Test
  public void testResolveToolClass_withFullyQualifiedName() {
    Optional<Class<? extends BaseTool>> googleSearchClass =
        ComponentRegistry.resolveToolClass("com.google.adk.tools.GoogleSearchTool");
    assertThat(googleSearchClass).isEmpty();

    ComponentRegistry.getInstance()
        .register("com.google.adk.tools.GoogleSearchTool", GoogleSearchTool.class);

    Optional<Class<? extends BaseTool>> testToolClass =
        ComponentRegistry.resolveToolClass("com.google.adk.tools.GoogleSearchTool");
    assertThat(testToolClass).isPresent();
    assertThat(testToolClass.get()).isEqualTo(GoogleSearchTool.class);

    Optional<Class<? extends BaseTool>> nonExistentDotted =
        ComponentRegistry.resolveToolClass("com.example.NonExistentTool");
    assertThat(nonExistentDotted).isEmpty();
  }

  @Test
  public void testMcpToolsetRegistration() {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // Verify direct registry storage (tests lines 134, 136, 138, 142 in ComponentRegistry.java)
    Optional<Object> directFullName = registry.get("com.google.adk.tools.mcp.McpToolset");
    assertThat(directFullName).hasValue(McpToolset.class);

    Optional<Object> directSimpleName = registry.get("McpToolset");
    assertThat(directSimpleName).hasValue(McpToolset.class);

    Optional<Object> directPythonName = registry.get("google.adk.tools.McpToolset");
    assertThat(directPythonName).hasValue(McpToolset.class);

    Optional<Object> directMcpName = registry.get("mcp.McpToolset");
    assertThat(directMcpName).hasValue(McpToolset.class);

    // Verify resolveToolsetClass API works with all naming conventions
    Optional<Class<? extends BaseToolset>> resolvedFullName =
        ComponentRegistry.resolveToolsetClass("com.google.adk.tools.mcp.McpToolset");
    assertThat(resolvedFullName).isPresent();
    assertThat(resolvedFullName.get()).isEqualTo(McpToolset.class);

    Optional<Class<? extends BaseToolset>> resolvedPythonName =
        ComponentRegistry.resolveToolsetClass("google.adk.tools.McpToolset");
    assertThat(resolvedPythonName).isPresent();
    assertThat(resolvedPythonName.get()).isEqualTo(McpToolset.class);

    Optional<Class<? extends BaseToolset>> resolvedSimpleName =
        ComponentRegistry.resolveToolsetClass("McpToolset");
    assertThat(resolvedSimpleName).isPresent();
    assertThat(resolvedSimpleName.get()).isEqualTo(McpToolset.class);

    Optional<Class<? extends BaseToolset>> resolvedMcpName =
        ComponentRegistry.resolveToolsetClass("mcp.McpToolset");
    assertThat(resolvedMcpName).isPresent();
    assertThat(resolvedMcpName.get()).isEqualTo(McpToolset.class);

    // Verify all resolve to the same class instance
    assertThat(resolvedFullName.get()).isSameInstanceAs(resolvedPythonName.get());
    assertThat(resolvedPythonName.get()).isSameInstanceAs(resolvedSimpleName.get());
    assertThat(resolvedSimpleName.get()).isSameInstanceAs(resolvedMcpName.get());
  }

  @Test
  public void testResolveToolsetClass_withDynamicClassLoading() {
    ComponentRegistry registry = ComponentRegistry.getInstance();
    Optional<Object> notInRegistry = registry.get("com.google.adk.tools.mcp.McpToolset");
    if (notInRegistry.isPresent()) {}
    Optional<Class<? extends BaseToolset>> dynamicallyLoaded =
        ComponentRegistry.resolveToolsetClass("com.google.adk.tools.mcp.McpToolset");
    assertThat(dynamicallyLoaded).isPresent();
    assertThat(dynamicallyLoaded.get()).isEqualTo(McpToolset.class);

    Optional<Class<? extends BaseToolset>> nonExistent =
        ComponentRegistry.resolveToolsetClass("com.google.adk.tools.NonExistentToolset");
    assertThat(nonExistent).isEmpty();

    Optional<Class<? extends BaseToolset>> notBaseToolset =
        ComponentRegistry.resolveToolsetClass("java.lang.String");
    assertThat(notBaseToolset).isEmpty();

    Optional<Class<? extends BaseToolset>> arrayListClass =
        ComponentRegistry.resolveToolsetClass("java.util.ArrayList");
    assertThat(arrayListClass).isEmpty();

    Optional<Class<? extends BaseToolset>> hashMapClass =
        ComponentRegistry.resolveToolsetClass("java.util.HashMap");
    assertThat(hashMapClass).isEmpty();
  }

  @Test
  public void testResolveToolsetClass_nullAndEmptyInput() {
    Optional<Class<? extends BaseToolset>> nullResult = ComponentRegistry.resolveToolsetClass(null);
    assertThat(nullResult).isEmpty();

    Optional<Class<? extends BaseToolset>> emptyResult = ComponentRegistry.resolveToolsetClass("");
    assertThat(emptyResult).isEmpty();

    Optional<Class<? extends BaseToolset>> whitespaceResult =
        ComponentRegistry.resolveToolsetClass("   ");
    assertThat(whitespaceResult).isEmpty();
  }

  @Test
  public void testResolveToolsetClass_registryTakesPrecedenceOverDynamicLoading() {
    ComponentRegistry registry = ComponentRegistry.getInstance();
    registry.register("test.dummy.ToolsetClass", McpToolset.class);

    Optional<Class<? extends BaseToolset>> fromRegistry =
        ComponentRegistry.resolveToolsetClass("test.dummy.ToolsetClass");
    assertThat(fromRegistry).isPresent();
    assertThat(fromRegistry.get()).isEqualTo(McpToolset.class);
  }

  @Test
  public void testResolveToolsetClass_classNotAssignableFromBaseToolset() {
    ComponentRegistry registry = ComponentRegistry.getInstance();
    registry.register("not.a.toolset.StringClass", String.class);

    Optional<Class<? extends BaseToolset>> result =
        ComponentRegistry.resolveToolsetClass("not.a.toolset.StringClass");
    assertThat(result).isEmpty();
  }

  @Test
  public void testResolveToolClass_nullAndEmptyInput() {
    Optional<Class<? extends BaseTool>> nullResult = ComponentRegistry.resolveToolClass(null);
    assertThat(nullResult).isEmpty();

    Optional<Class<? extends BaseTool>> emptyResult = ComponentRegistry.resolveToolClass("");
    assertThat(emptyResult).isEmpty();

    Optional<Class<? extends BaseTool>> whitespaceResult =
        ComponentRegistry.resolveToolClass("   ");
    assertThat(whitespaceResult).isEmpty();
  }

  @Test
  public void testResolveToolClass_notAssignableFromBaseTool() {
    ComponentRegistry registry = ComponentRegistry.getInstance();
    registry.register("not.a.tool.StringClass", String.class);

    Optional<Class<? extends BaseTool>> result =
        ComponentRegistry.resolveToolClass("not.a.tool.StringClass");
    assertThat(result).isEmpty();

    registry.register("google.adk.tools.NotATool", ArrayList.class);
    Optional<Class<? extends BaseTool>> withPrefix = ComponentRegistry.resolveToolClass("NotATool");
    assertThat(withPrefix).isEmpty();
  }

  @Test
  public void testGetToolNamesWithPrefix() {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    registry.register("test.prefix.tool1", "tool1");
    registry.register("test.prefix.tool2", "tool2");
    registry.register("test.other.tool3", "tool3");
    registry.register("different.prefix.tool4", "tool4");

    Set<String> toolsWithTestPrefix = registry.getToolNamesWithPrefix("test.prefix");
    assertThat(toolsWithTestPrefix).containsExactly("test.prefix.tool1", "test.prefix.tool2");

    Set<String> toolsWithTestOther = registry.getToolNamesWithPrefix("test.other");
    assertThat(toolsWithTestOther).containsExactly("test.other.tool3");

    Set<String> toolsWithNonExistent = registry.getToolNamesWithPrefix("nonexistent.prefix");
    assertThat(toolsWithNonExistent).isEmpty();

    Set<String> allTestTools = registry.getToolNamesWithPrefix("test.");
    assertThat(allTestTools)
        .containsAtLeast("test.prefix.tool1", "test.prefix.tool2", "test.other.tool3");
  }

  @Test
  public void testResolveToolInstance() {
    Optional<BaseTool> nullInstance = ComponentRegistry.resolveToolInstance(null);
    assertThat(nullInstance).isEmpty();

    Optional<BaseTool> emptyInstance = ComponentRegistry.resolveToolInstance("");
    assertThat(emptyInstance).isEmpty();

    Optional<BaseTool> googleSearchBySimpleName =
        ComponentRegistry.resolveToolInstance("google_search");
    assertThat(googleSearchBySimpleName).isPresent();
    assertThat(googleSearchBySimpleName.get()).isInstanceOf(GoogleSearchTool.class);

    Optional<BaseTool> exitLoopTool = ComponentRegistry.resolveToolInstance("exit_loop");
    assertThat(exitLoopTool).isPresent();

    Optional<BaseTool> nonExistentTool = ComponentRegistry.resolveToolInstance("non_existent_tool");
    assertThat(nonExistentTool).isEmpty();
  }

  @Test
  public void testResolveToolsetInstance() {
    Optional<BaseToolset> nullInstance = ComponentRegistry.resolveToolsetInstance(null);
    assertThat(nullInstance).isEmpty();

    Optional<BaseToolset> emptyInstance = ComponentRegistry.resolveToolsetInstance("");
    assertThat(emptyInstance).isEmpty();

    Optional<BaseToolset> nonExistentInstance =
        ComponentRegistry.resolveToolsetInstance("NonExistentToolset");
    assertThat(nonExistentInstance).isEmpty();
  }

  @Test
  public void testSetInstance_nullThrowsException() {
    assertThrows(IllegalArgumentException.class, () -> ComponentRegistry.setInstance(null));
  }

  @Test
  public void testResolveToolClass_comGooglePrefixFallback_requiredForSimpleName() {

    ComponentRegistry testRegistry = new ComponentRegistry();
    ComponentRegistry originalRegistry = ComponentRegistry.getInstance();
    try {
      ComponentRegistry.setInstance(testRegistry);

      testRegistry.register("com.google.adk.tools.MutationCatcherTool", GoogleSearchTool.class);

      assertThat(testRegistry.get("MutationCatcherTool", Class.class)).isEmpty();

      Optional<Class<? extends BaseTool>> resolved =
          ComponentRegistry.resolveToolClass("MutationCatcherTool");
      assertThat(resolved).isPresent(); // Will fail if mutation removes the prefix check
      assertThat(resolved.get()).isEqualTo(GoogleSearchTool.class);
    } finally {
      ComponentRegistry.setInstance(originalRegistry);
    }
  }

  @Test
  public void testResolveAgentClass_rejectsNonAgentClassAndRequiresPrefixFallback() {

    ComponentRegistry testRegistry = new ComponentRegistry();
    ComponentRegistry originalRegistry = ComponentRegistry.getInstance();
    try {
      ComponentRegistry.setInstance(testRegistry);

      testRegistry.register("NotAnAgent", String.class);
      assertThrows(
          IllegalArgumentException.class, () -> ComponentRegistry.resolveAgentClass("NotAnAgent"));

      testRegistry.register("com.google.adk.agents.PrefixOnlyAgent", LlmAgent.class);
      assertThat(testRegistry.get("PrefixOnlyAgent", Class.class)).isEmpty();

      Class<? extends BaseAgent> resolved = ComponentRegistry.resolveAgentClass("PrefixOnlyAgent");
      assertThat(resolved).isEqualTo(LlmAgent.class);
    } finally {
      ComponentRegistry.setInstance(originalRegistry);
    }
  }
}
