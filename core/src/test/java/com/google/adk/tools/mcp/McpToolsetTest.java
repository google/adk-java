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
import com.google.adk.tools.BaseTool.ToolArgsConfig;
import com.google.adk.tools.mcp.McpToolset.McpToolsetConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class McpToolsetTest {

  @Test
  public void testFromConfig_withStdioServerParams_succeeds() throws ConfigurationException {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put(
        "stdio_server_params",
        ImmutableMap.of("command", "my-command", "args", ImmutableList.of("--foo", "bar")));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    McpToolset toolset = McpToolset.fromConfig(toolConfig, "/fake/path");
    assertThat(toolset).isNotNull();
  }

  @Test
  public void testFromConfig_withSseServerParams_succeeds() throws ConfigurationException {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put(
        "sse_server_params",
        ImmutableMap.of(
            "url",
            "http://localhost:8080",
            "headers",
            ImmutableMap.of("X-Test-Header", "test-value")));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    McpToolset toolset = McpToolset.fromConfig(toolConfig, "/fake/path");
    assertThat(toolset).isNotNull();
  }

  @Test
  public void testFromConfig_withToolFilter_succeeds() throws ConfigurationException {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put(
        "stdio_server_params",
        ImmutableMap.of("command", "my-command", "args", ImmutableList.of("--foo", "bar")));
    args.put("tool_filter", ImmutableList.of("tool1", "tool2"));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    McpToolset toolset = McpToolset.fromConfig(toolConfig, "/fake/path");
    assertThat(toolset).isNotNull();
  }

  @Test
  public void testFromConfig_withBothServerParams_fails() {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put("stdio_server_params", ImmutableMap.of("command", "my-command"));
    args.put("sse_server_params", ImmutableMap.of("url", "http://localhost:8080"));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    ConfigurationException e =
        assertThrows(
            ConfigurationException.class, () -> McpToolset.fromConfig(toolConfig, "/fake/path"));
    assertThat(e)
        .hasMessageThat()
        .contains("Exactly one of stdio_server_params or sse_server_params must be set");
  }

  @Test
  public void testFromConfig_withNoServerParams_fails() {
    ToolArgsConfig args = new ToolArgsConfig();
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    ConfigurationException e =
        assertThrows(
            ConfigurationException.class, () -> McpToolset.fromConfig(toolConfig, "/fake/path"));
    assertThat(e)
        .hasMessageThat()
        .contains("Exactly one of stdio_server_params or sse_server_params must be set");
  }

  @Test
  public void testFromConfig_withMissingCommandInStdio_fails() {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put("stdio_server_params", ImmutableMap.of("args", ImmutableList.of("--foo")));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    ConfigurationException e =
        assertThrows(
            ConfigurationException.class, () -> McpToolset.fromConfig(toolConfig, "/fake/path"));
    assertThat(e).hasMessageThat().contains("stdio_server_params.command is required");
  }

  @Test
  public void testFromConfig_withMissingUrlInSse_fails() {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put("sse_server_params", ImmutableMap.of("headers", ImmutableMap.of("X-Foo", "bar")));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    ConfigurationException e =
        assertThrows(
            ConfigurationException.class, () -> McpToolset.fromConfig(toolConfig, "/fake/path"));
    assertThat(e).hasMessageThat().contains("sse_server_params.url is required");
  }

  @Test
  public void testMcpToolsetCoalesceToMcpToolsetConfig() throws ConfigurationException {
    ToolArgsConfig args = new ToolArgsConfig();
    args.put(
        "stdio_server_params",
        ImmutableMap.of(
            "command", "my-command",
            "args", ImmutableList.of("--foo", "bar"),
            "env", ImmutableMap.of("MY_VAR", "my_value")));
    args.put("tool_filter", ImmutableList.of("my-tool"));
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig("mcp-toolset", args);

    McpToolsetConfig mcpConfig =
        BaseTool.ToolArgsConfig.getMapper().convertValue(toolConfig.args(), McpToolsetConfig.class);

    assertThat(mcpConfig.stdioServerParams()).isNotNull();
    assertThat(mcpConfig.sseServerParams()).isNull();
    assertThat(mcpConfig.toolFilter()).isNotNull();
    assertThat(mcpConfig.toolFilter()).containsExactly("my-tool");
    assertThat(mcpConfig.stdioServerParams().get("command")).isEqualTo("my-command");
  }
}
