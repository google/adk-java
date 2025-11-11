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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset.McpToolsetConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class McpToolsetTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private McpSessionManager mockMcpSessionManager;
  @Mock private McpSyncClient mockMcpSyncClient;
  @Mock private ReadonlyContext mockReadonlyContext;

  private static final McpJsonMapper jsonMapper = McpJsonMapper.getDefault();

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
        "stdioServerParams",
        ImmutableMap.of("command", "test-command", "args", ImmutableList.of("arg1", "arg2")));
    args.put("sseServerParams", ImmutableMap.of("url", "http://localhost:8080"));
    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> McpToolset.fromConfig(config, configPath));

    assertThat(exception)
        .hasMessageThat()
        .contains("Exactly one of stdioServerParams or sseServerParams must be set");
  }

  @Test
  public void testFromConfig_neitherStdioNorSseParams_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("toolFilter", ImmutableList.of("tool1", "tool2"));
    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> McpToolset.fromConfig(config, configPath));

    assertThat(exception)
        .hasMessageThat()
        .contains("Exactly one of stdioServerParams or sseServerParams must be set");
  }

  @Test
  public void testFromConfig_validStdioParams_createsToolset() throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put(
        "stdioServerParams",
        ImmutableMap.of(
            "command", "test-command",
            "args", ImmutableList.of("arg1", "arg2"),
            "env", ImmutableMap.of("KEY1", "value1")));
    args.put("toolFilter", ImmutableList.of("tool1", "tool2"));

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
  }

  @Test
  public void testFromConfig_validSseParams_createsToolset() throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put(
        "sseServerParams",
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
    args.put("sseServerParams", ImmutableMap.of("url", "http://localhost:8080"));
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
    args.put("stdioServerParams", ImmutableMap.of("command", "test-command"));
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
    args.put("stdioServerParams", "invalid_string_instead_of_map");

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
    args.put("stdioServerParams", ImmutableMap.of("command", "test-command"));

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // The toolset should be created successfully without tool filter
  }

  @Test
  public void testFromConfig_emptyToolFilter_createsToolset() throws ConfigurationException {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.put("stdioServerParams", ImmutableMap.of("command", "test-command"));
    args.put("toolFilter", ImmutableList.of());

    BaseTool.ToolConfig config = new BaseTool.ToolConfig("mcp_toolset", args);
    String configPath = "/path/to/config.yaml";

    McpToolset toolset = McpToolset.fromConfig(config, configPath);

    assertThat(toolset).isNotNull();
    // The toolset should be created successfully with empty tool filter
  }

  @Test
  public void getTools_withToolFilter_returnsFilteredTools() {
    ImmutableList<String> toolFilter = ImmutableList.of("tool1", "tool3");
    McpSchema.Tool mockTool1 =
        McpSchema.Tool.builder()
            .name("tool1")
            .description("desc1")
            .inputSchema(jsonMapper, "{}")
            .build();
    McpSchema.Tool mockTool2 =
        McpSchema.Tool.builder()
            .name("tool2")
            .description("desc2")
            .inputSchema(jsonMapper, "{}")
            .build();
    McpSchema.Tool mockTool3 =
        McpSchema.Tool.builder()
            .name("tool3")
            .description("desc3")
            .inputSchema(jsonMapper, "{}")
            .build();
    McpSchema.ListToolsResult mockResult =
        new McpSchema.ListToolsResult(ImmutableList.of(mockTool1, mockTool2, mockTool3), null);

    when(mockMcpSessionManager.createSession()).thenReturn(mockMcpSyncClient);
    when(mockMcpSyncClient.listTools()).thenReturn(mockResult);

    McpToolset toolset =
        new McpToolset(mockMcpSessionManager, JsonBaseModel.getMapper(), Optional.of(toolFilter));

    List<BaseTool> tools = toolset.getTools(mockReadonlyContext).toList().blockingGet();

    assertThat(tools.stream().map(BaseTool::name).collect(ImmutableList.toImmutableList()))
        .containsExactly("tool1", "tool3")
        .inOrder();
    verify(mockMcpSessionManager).createSession();
    verify(mockMcpSyncClient).listTools();
  }

  @Test
  public void getTools_retriesAndFailsAfterMaxRetries() {
    when(mockMcpSessionManager.createSession()).thenReturn(mockMcpSyncClient);
    when(mockMcpSyncClient.listTools()).thenThrow(new RuntimeException("Test Exception"));

    McpToolset toolset =
        new McpToolset(mockMcpSessionManager, JsonBaseModel.getMapper(), Optional.empty());

    toolset
        .getTools(mockReadonlyContext)
        .test()
        .awaitDone(5, SECONDS)
        .assertError(McpToolsetException.McpToolLoadingException.class);

    verify(mockMcpSessionManager, times(3)).createSession();
    verify(mockMcpSyncClient, times(3)).listTools();
  }

  @Test
  public void getTools_succeedsOnLastRetryAttempt() {
    McpSchema.ListToolsResult mockResult = new McpSchema.ListToolsResult(ImmutableList.of(), null);
    when(mockMcpSessionManager.createSession()).thenReturn(mockMcpSyncClient);
    when(mockMcpSyncClient.listTools())
        .thenThrow(new RuntimeException("Attempt 1 failed"))
        .thenThrow(new RuntimeException("Attempt 2 failed"))
        .thenReturn(mockResult);

    McpToolset toolset =
        new McpToolset(mockMcpSessionManager, JsonBaseModel.getMapper(), Optional.empty());

    List<BaseTool> tools = toolset.getTools(mockReadonlyContext).toList().blockingGet();

    assertThat(tools).isEmpty();
    verify(mockMcpSessionManager, times(3)).createSession();
    verify(mockMcpSyncClient, times(3)).listTools();
  }
}
