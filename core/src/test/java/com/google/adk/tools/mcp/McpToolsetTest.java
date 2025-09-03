/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset.McpToolsetConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class McpToolsetTest {

  @Test
  public void testMcpToolsetConfig_withStdioServerParams_parsesCorrectly() {
    McpToolsetConfig mcpConfig = new McpToolsetConfig();
    StdioServerParameters stdioParams =
        StdioServerParameters.builder()
            .command("my-command")
            .args(ImmutableList.of("--foo", "bar"))
            .build();
    mcpConfig.setStdioServerParams(stdioParams);

    assertThat(mcpConfig.stdioServerParams()).isNotNull();
    assertThat(mcpConfig.sseServerParams()).isNull();
    assertThat(mcpConfig.stdioServerParams().command()).isEqualTo("my-command");
    assertThat(mcpConfig.stdioServerParams().args()).containsExactly("--foo", "bar").inOrder();
  }

  @Test
  public void testMcpToolsetConfig_withSseServerParams_parsesCorrectly() {
    McpToolsetConfig mcpConfig = new McpToolsetConfig();
    SseServerParameters sseParams =
        SseServerParameters.builder().url("http://localhost:8080").build();
    mcpConfig.setSseServerParams(sseParams);

    assertThat(mcpConfig.sseServerParams()).isNotNull();
    assertThat(mcpConfig.stdioServerParams()).isNull();
    assertThat(mcpConfig.sseServerParams().url()).isEqualTo("http://localhost:8080");
  }

  @Test
  public void testMcpToolsetConfig_withToolFilter_parsesCorrectly() {
    McpToolsetConfig mcpConfig = new McpToolsetConfig();
    StdioServerParameters stdioParams =
        StdioServerParameters.builder().command("my-command").build();
    mcpConfig.setStdioServerParams(stdioParams);
    mcpConfig.setToolFilter(ImmutableList.of("my-tool"));

    assertThat(mcpConfig.stdioServerParams()).isNotNull();
    assertThat(mcpConfig.sseServerParams()).isNull();
    assertThat(mcpConfig.toolFilter()).isNotNull();
    assertThat(mcpConfig.toolFilter()).containsExactly("my-tool");
    assertThat(mcpConfig.stdioServerParams().command()).isEqualTo("my-command");
  }

  @Test
  public void testFromConfig_nullArgs_throwsConfigurationException() {
    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", null);
    String configPath = "/path/to/config.yaml";

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> McpToolset.fromConfig(config, configPath));

    assertThat(exception).hasMessageThat().contains("Tool args is null for McpToolset");
  }

  @Test
  public void testFromConfig_bothStdioAndSseParams_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put(
        "stdio_server_params",
        ImmutableMap.of("command", "test-command", "args", ImmutableList.of("arg1", "arg2")));
    args.put("sse_server_params", ImmutableMap.of("url", "http://localhost:8080"));
    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> McpToolset.fromConfig(config, configPath));

    assertThat(exception)
        .hasMessageThat()
        .contains("Exactly one of stdio_server_params or sse_server_params must be set");
  }

  @Test
  public void testFromConfig_neitherStdioNorSseParams_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("tool_filter", ImmutableList.of("tool1", "tool2"));
    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> McpToolset.fromConfig(config, configPath));

    assertThat(exception)
        .hasMessageThat()
        .contains("Exactly one of stdio_server_params or sse_server_params must be set");
  }

  @Test
  public void testFromConfig_validStdioParams_createsToolset() throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put(
        "stdio_server_params",
        ImmutableMap.of(
            "command", "test-command",
            "args", ImmutableList.of("arg1", "arg2"),
            "env", ImmutableMap.of("KEY1", "value1")));
    args.put("tool_filter", ImmutableList.of("tool1", "tool2"));

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // The toolset should be created successfully with stdio parameters
  }

  @Test
  public void testFromConfig_validSseParams_createsToolset() throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put(
        "sse_server_params",
        ImmutableMap.of(
            "url",
            "http://localhost:8080",
            "headers",
            ImmutableMap.of("Authorization", "Bearer token")));

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // The toolset should be created successfully with SSE parameters
  }

  @Test
  public void testFromConfig_onlySseParams_doesNotUseStdioBranch() throws ConfigurationException {
    // This test ensures that when only SSE params are provided, the SSE branch is taken
    // If line 328 is mutated to if(true), this test should fail because it would try to
    // call stdioServerParams().toServerParameters() on null, causing a NullPointerException
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("sse_server_params", ImmutableMap.of("url", "http://localhost:8080"));
    // Explicitly NOT setting stdio_server_params

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    // This should succeed and use the SSE constructor
    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // If the mutation changes line 328 to if(true), it would try to call
    // stdioServerParams().toServerParameters() which would throw NullPointerException
    // because stdioServerParams() is null
  }

  @Test
  public void testFromConfig_onlyStdioParams_doesNotUseSseBranch() throws ConfigurationException {
    // This test ensures that when only stdio params are provided, the stdio branch is taken
    // It protects against mutations that might force the else branch
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("stdio_server_params", ImmutableMap.of("command", "test-command"));
    // Explicitly NOT setting sse_server_params

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    // This should succeed and use the stdio constructor
    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // If a mutation forced the else branch (line 332), it would try to use
    // sseServerParams() which is null, causing issues
  }

  @Test
  public void testFromConfig_invalidArgsFormat_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Put invalid data that can't be converted to McpToolsetConfig
    args.put("stdio_server_params", "invalid_string_instead_of_map");

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> McpToolset.fromConfig(config, configPath));
    assertThat(exception)
        .hasMessageThat()
        .contains("Failed to parse McpToolsetConfig from ToolArgsConfig");
  }

  @Test
  public void testFromConfig_stdioParamsNoToolFilter_createsToolset()
      throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("stdio_server_params", ImmutableMap.of("command", "test-command"));

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // The toolset should be created successfully without tool filter
  }

  @Test
  public void testFromConfig_emptyToolFilter_createsToolset() throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("stdio_server_params", ImmutableMap.of("command", "test-command"));
    args.put("tool_filter", ImmutableList.of());

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // The toolset should be created successfully with empty tool filter
  }
}
