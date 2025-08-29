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
import java.util.Optional;
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

    assertThat(thrown.getMessage()).contains("Name cannot be null or empty");
  }

  @Test
  public void testRegisterWithEmptyName() {
    ComponentRegistry registry = new ComponentRegistry();

    IllegalArgumentException thrown1 =
        assertThrows(IllegalArgumentException.class, () -> registry.register("", "value"));
    IllegalArgumentException thrown2 =
        assertThrows(IllegalArgumentException.class, () -> registry.register("   ", "value"));

    assertThat(thrown1.getMessage()).contains("Name cannot be null or empty");
    assertThat(thrown2.getMessage()).contains("Name cannot be null or empty");
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

    assertThat(thrown.getMessage()).contains("Value cannot be null");
  }

  @Test
  public void testSubclassExtension() {
    class CustomComponentRegistry extends ComponentRegistry {
      public CustomComponentRegistry() {
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
    assertThat(thrown.getMessage()).contains("not in registry or not a subclass of BaseAgent");
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
  public void testResolveToolsetClass_withSimpleName() {
    Optional<Class<? extends BaseToolset>> mcpToolsetClass =
        ComponentRegistry.resolveToolsetClass("McpToolset");
    assertThat(mcpToolsetClass).isPresent();
    assertThat(mcpToolsetClass.get()).isEqualTo(McpToolset.class);
  }

  @Test
  public void testResolveToolsetClass_withGoogleAdkToolsPrefix() {
    ComponentRegistry.getInstance().register("google.adk.tools.TestToolset", McpToolset.class);

    Optional<Class<? extends BaseToolset>> testToolsetClass =
        ComponentRegistry.resolveToolsetClass("TestToolset");
    assertThat(testToolsetClass).isPresent();
    assertThat(testToolsetClass.get()).isEqualTo(McpToolset.class);
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
  }

  @Test
  public void testResolveToolsetClass_withFullyQualifiedName() {
    ComponentRegistry.getInstance()
        .register("com.google.adk.tools.mcp.McpToolset", McpToolset.class);

    Optional<Class<? extends BaseToolset>> mcpToolsetClass =
        ComponentRegistry.resolveToolsetClass("com.google.adk.tools.mcp.McpToolset");
    assertThat(mcpToolsetClass).isPresent();
    assertThat(mcpToolsetClass.get()).isEqualTo(McpToolset.class);
  }
}
