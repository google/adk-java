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

import com.google.adk.tools.mcp.McpToolset.McpToolsetConfig;
import com.google.common.collect.ImmutableList;
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
  public void testFromConfig_withBothServerParams_throwsConfigurationException() {
    McpToolsetConfig mcpConfig = new McpToolsetConfig();
    mcpConfig.setStdioServerParams(StdioServerParameters.builder().command("my-command").build());
    mcpConfig.setSseServerParams(
        SseServerParameters.builder().url("http://localhost:8080").build());

    boolean hasStdio = mcpConfig.stdioServerParams() != null;
    boolean hasSse = mcpConfig.sseServerParams() != null;
    boolean xorCondition = hasStdio == hasSse;

    assertThat(xorCondition).isTrue();
  }

  @Test
  public void testFromConfig_withNoServerParams_throwsConfigurationException() {
    McpToolsetConfig mcpConfig = new McpToolsetConfig();
    mcpConfig.setToolFilter(ImmutableList.of("my-tool"));

    boolean hasStdio = mcpConfig.stdioServerParams() != null;
    boolean hasSse = mcpConfig.sseServerParams() != null;
    boolean xorCondition = hasStdio == hasSse;

    assertThat(xorCondition).isTrue();
  }
}
