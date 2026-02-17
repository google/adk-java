// Copyright 2025 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.example.mcpfilesystem;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.google.common.collect.ImmutableList;

/** Defines an agent that wires the MCP stdio filesystem server via {@link McpToolset}. */
public final class McpFilesystemAgent {
  /** Root agent instance exposed to runners and registries. */
  public static final LlmAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("filesystem_agent")
          .description("Assistant that performs file operations through the MCP filesystem server.")
          .model("gemini-2.0-flash")
          .instruction(
              """
              You are a file system assistant. Use the provided tools to read, write, search, and manage
              files and directories. Ask clarifying questions when unsure about file operations.
              When the user requests that you append to a file, read the current contents, add the
              appended text with a newline if needed, then overwrite the file with the combined
              content.
              """)
          .tools(ImmutableList.of(createMcpToolset()))
          .build();

  private McpFilesystemAgent() {}

  private static McpToolset createMcpToolset() {
    StdioServerParameters stdioParams =
        StdioServerParameters.builder()
            .command("npx")
            .args(
                ImmutableList.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp/mcp-demo"))
            .build();
    return new McpToolset(stdioParams.toServerParameters());
  }
}
