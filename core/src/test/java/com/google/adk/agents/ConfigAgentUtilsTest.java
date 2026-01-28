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
import com.google.adk.examples.Example;
import com.google.adk.models.LlmRequest;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ExampleTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ConfigAgentUtils}. */
@RunWith(JUnit4.class)
public final class ConfigAgentUtilsTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void fromConfig_nonExistentFile_throwsException() {
    String nonExistentPath = new File(tempFolder.getRoot(), "nonexistent.yaml").getAbsolutePath();
    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class, () -> ConfigAgentUtils.fromConfig(nonExistentPath));
    assertThat(exception).hasMessageThat().isEqualTo("Config file not found: " + nonExistentPath);
  }

  @Test
  public void fromConfig_invalidYaml_throwsException() throws IOException {
    File configFile = tempFolder.newFile("invalid.yaml");
    Files.writeString(configFile.toPath(), "name: test\n  description: invalid indent");
    String configPath = configFile.getAbsolutePath();

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> ConfigAgentUtils.fromConfig(configPath));
    assertThat(exception).hasMessageThat().startsWith("Failed to load or parse config file:");
  }

  @Test
  public void fromConfig_validYamlLlmAgent_attemptsToCreateLlmAgent()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("valid.yaml");
    Files.writeString(
        configFile.toPath(),
        "name: testAgent\n"
            + "description: A test agent\n"
            + "instruction: test instruction\n"
            + "agent_class: LlmAgent\n");
    String configPath = configFile.getAbsolutePath();
    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);
    assertThat(agent).isNotNull();
    assertThat(agent).isInstanceOf(LlmAgent.class);
  }

  @Test
  public void fromConfig_customAgentClass_throwsUnsupportedException() throws IOException {
    File configFile = tempFolder.newFile("custom.yaml");
    String customAgentClass = "com.example.CustomAgent";
    Files.writeString(
        configFile.toPath(),
        String.format(
            "name: customAgent\n" + "description: A custom agent\n" + "agent_class: %s \n",
            customAgentClass));
    String configPath = configFile.getAbsolutePath();
    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> ConfigAgentUtils.fromConfig(configPath));
    assertThat(exception).hasMessageThat().contains("Failed to create agent from config:");
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "agentClass '"
                + customAgentClass
                + "' is not in registry or not a subclass of BaseAgent.");
  }

  @Test
  public void fromConfig_baseAgentClass_throwsUnsupportedException() throws IOException {
    File configFile = tempFolder.newFile("custom.yaml");
    String customAgentClass = "BaseAgent";
    Files.writeString(
        configFile.toPath(),
        "name: customAgent\n" + "description: A custom agent\n" + "agent_class: BaseAgent \n");
    String configPath = configFile.getAbsolutePath();
    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> ConfigAgentUtils.fromConfig(configPath));
    assertThat(exception).hasMessageThat().contains("Failed to create agent from config:");
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "agentClass '"
                + customAgentClass
                + "' is not in registry or not a subclass of BaseAgent.");
  }

  @Test
  public void fromConfig_emptyAgentClass_defaultsToLlmAgent()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("empty_class.yaml");
    Files.writeString(
        configFile.toPath(),
        "name: emptyClassAgent\n"
            + "description: Agent with empty class\n"
            + "instruction: test instruction\n"
            + "agent_class: \"\"\n");
    String configPath = configFile.getAbsolutePath();
    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);
    assertThat(agent).isNotNull();
    assertThat(agent).isInstanceOf(LlmAgent.class);
  }

  @Test
  public void fromConfig_withoutAgentClass_defaultsToLlmAgent()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("empty_class.yaml");
    Files.writeString(
        configFile.toPath(),
        "name: emptyClassAgent\n"
            + "description: Agent with empty class\n"
            + "instruction: test instruction\n");
    String configPath = configFile.getAbsolutePath();
    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);
    assertThat(agent).isNotNull();
    assertThat(agent).isInstanceOf(LlmAgent.class);
  }

  @Test
  public void fromConfig_yamlWithExtraFields_ignoresUnknownProperties()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("extra_fields.yaml");
    Files.writeString(
        configFile.toPath(),
        "name: flexibleAgent\n"
            + "description: Agent with extra fields\n"
            + "instruction: test instruction\n"
            + "agent_class: LlmAgent\n"
            + "unknown_field: some_value\n"
            + "another_unknown: 123\n"
            + "nested_unknown:\n"
            + "  key: value\n");
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isNotNull();
    assertThat(agent).isInstanceOf(LlmAgent.class);
    assertThat(agent.name()).isEqualTo("flexibleAgent");
    assertThat(agent.description()).isEqualTo("Agent with extra fields");
  }

  @Test
  public void fromConfig_missingRequiredFields_throwsException() throws IOException {
    File configFile = tempFolder.newFile("incomplete.yaml");
    Files.writeString(
        configFile.toPath(),
        "description: Agent missing required fields\n" + "agent_class: LlmAgent\n");
    String configPath = configFile.getAbsolutePath();

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> ConfigAgentUtils.fromConfig(configPath));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
    assertThat(exception).hasCauseThat().isNotNull();
  }

  @Test
  public void fromConfig_withModel_setsModelOnAgent() throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("with_model.yaml");
    Files.writeString(
        configFile.toPath(),
        "name: modelAgent\n"
            + "description: Agent with a model\n"
            + "instruction: test instruction\n"
            + "agent_class: LlmAgent\n"
            + "model: \"gemini-pro\"\n");
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.model()).isPresent();
    assertThat(llmAgent.model().get().modelName()).hasValue("gemini-pro");
  }

  @Test
  public void fromConfig_withEmptyModel_doesNotSetModelOnAgent()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("empty_model.yaml");
    Files.writeString(
        configFile.toPath(),
        "name: emptyModelAgent\n"
            + "description: Agent with an empty model\n"
            + "instruction: test instruction\n"
            + "agent_class: LlmAgent\n"
            + "model: \"\"\n");
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.model()).isEmpty();
  }

  @Test
  public void fromConfig_withBuiltInTool_loadsTool() throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("with_tool.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: search_agent
        model: gemini-1.5-flash
        description: 'an agent whose job it is to perform Google search queries and answer questions about the results.'
        instruction: You are an agent whose job is to perform Google search queries and answer questions about the results.
        agent_class: LlmAgent
        tools:
          - name: google_search
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.tools().blockingGet()).hasSize(1);
    assertThat(llmAgent.tools().blockingGet().get(0).name()).isEqualTo("google_search");
  }

  @Test
  public void fromConfig_withInvalidModel_throwsExceptionOnModelResolution()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("invalid_model.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: invalidModelAgent
        description: Agent with an invalid model
        instruction: test instruction
        agent_class: LlmAgent
        model: "invalid-model-name"
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, llmAgent::resolvedModel);
    assertThat(exception).hasMessageThat().contains("invalid-model-name");
  }

  @Test
  public void fromConfig_withSubAgents_createsHierarchy()
      throws IOException, ConfigurationException {
    File subAgentFile = tempFolder.newFile("sub_agent.yaml");
    Files.writeString(
        subAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: sub_agent
        description: A test subagent
        instruction: You are a helpful subagent
        """);

    File mainAgentFile = tempFolder.newFile("main_agent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with subagent
        instruction: You are a main agent that delegates to subagents
        sub_agents:
          - name: sub_agent
            config_path: sub_agent.yaml
        """);

    BaseAgent mainAgent = ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath());

    assertThat(mainAgent.name()).isEqualTo("main_agent");
    assertThat(mainAgent.description()).isEqualTo("Main agent with subagent");
    assertThat(mainAgent).isInstanceOf(LlmAgent.class);

    assertThat(mainAgent.subAgents()).hasSize(1);
    BaseAgent subAgent = mainAgent.subAgents().get(0);
    assertThat(subAgent.name()).isEqualTo("sub_agent");
    assertThat(subAgent.description()).isEqualTo("A test subagent");
    assertThat(subAgent).isInstanceOf(LlmAgent.class);

    assertThat(subAgent.parentAgent()).isEqualTo(mainAgent);

    LlmAgent llmSubAgent = (LlmAgent) subAgent;
    assertThat(llmSubAgent.instruction().toString()).contains("helpful subagent");
  }

  @Test
  public void resolveSubAgents_missingConfigPath_throwsConfigurationException() throws IOException {
    File mainAgentFile = tempFolder.newFile("main_agent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with invalid subagent
        instruction: You are a main agent
        sub_agents:
          - name: invalid_subagent
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
    // Ensure we don't throw a NullPointerException due to bad null/trim handling
    StringBuilder messages = new StringBuilder();
    Throwable t = exception.getCause();
    while (t != null) {
      messages.append(t.getMessage()).append("\n");
      t = t.getCause();
    }
    assertThat(messages.toString()).contains("must specify either 'configPath' or 'code'");
  }

  @Test
  public void resolveSubAgents_withWhitespaceCode_treatedAsMissing_throwsConfigurationException()
      throws IOException {
    File mainAgentFile = tempFolder.newFile("whitespace_code_subagent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent
        instruction: You are a main agent
        sub_agents:
          - name: ws_code
            code: "   "
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath()));

    StringBuilder messages = new StringBuilder();
    Throwable t = exception.getCause();
    while (t != null) {
      messages.append(t.getMessage()).append("\n");
      t = t.getCause();
    }
    assertThat(messages.toString()).contains("must specify either 'configPath' or 'code'");
  }

  @Test
  public void resolveSubAgents_withClassName_throwsUnsupportedException() throws IOException {
    File mainAgentFile = tempFolder.newFile("main_agent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with programmatic subagent
        instruction: You are a main agent
        sub_agents:
          - name: programmatic_subagent
            class_name: com.example.TestAgent
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
  }

  @Test
  public void resolveSubAgents_withStaticField_throwsUnsupportedException() throws IOException {
    File mainAgentFile = tempFolder.newFile("main_agent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with static field subagent
        instruction: You are a main agent
        sub_agents:
          - name: static_field_subagent
            static_field: TestAgent.INSTANCE
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
  }

  @Test
  public void fromConfig_withMcpToolset_loadsToolset() throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("with_mcp_toolset.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: mcp_agent
        model: gemini-1.5-flash
        instruction: You are an agent that uses an MCP toolset.
        agent_class: LlmAgent
        tools:
          - name: McpToolset
            args:
              stdio_server_params:
                command: "npx"
                args:
                  - "-y"
                  - "@notionhq/notion-mcp-server"
                env:
                  OPENAPI_MCP_HEADERS: '{"Authorization": "Bearer fake-key"}'
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.toolsets()).hasSize(1);
    assertThat(llmAgent.toolsets().get(0)).isInstanceOf(McpToolset.class);
  }

  @Test
  public void fromConfig_withMcpToolsetSseParams_loadsToolset()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("with_mcp_sse_toolset.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: mcp_sse_agent
        model: gemini-1.5-flash
        instruction: You are an agent that uses an MCP toolset with SSE connection.
        description: Agent with SSE-based MCP toolset for event streaming
        agent_class: LlmAgent
        tools:
          - name: McpToolset
            args:
              sse_server_params:
                url: "http://localhost:8080"
                sse_endpoint: "/events"
                headers:
                  Authorization: "Bearer test-token"
                  Content-Type: "text/event-stream"
                  X-Custom-Header: "custom-value"
                timeout: 10000
                sse_read_timeout: 300000
              tool_filter:
                - "allowed_tool_1"
                - "allowed_tool_2"
                - "allowed_tool_3"
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.name()).isEqualTo("mcp_sse_agent");
    assertThat(llmAgent.description())
        .isEqualTo("Agent with SSE-based MCP toolset for event streaming");
    assertThat(llmAgent.instruction()).isNotNull();
    assertThat(llmAgent.instruction().toString())
        .contains("You are an agent that uses an MCP toolset with SSE connection.");
    assertThat(llmAgent.model()).isPresent();

    assertThat(llmAgent.toolsets()).hasSize(1);
    assertThat(llmAgent.toolsets().get(0)).isInstanceOf(McpToolset.class);

    String originalYaml = Files.readString(configFile.toPath());
    assertThat(originalYaml).contains("sse_server_params");
    assertThat(originalYaml).contains("sse_endpoint");
    assertThat(originalYaml).contains("sse_read_timeout");
    assertThat(originalYaml).contains("tool_filter");
    assertThat(originalYaml).contains("agent_class");
  }

  @Test
  public void fromConfig_withGenerateContentConfig() throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("snake_case_conversion_test.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: snake_case_test_agent
        model: gemini-1.5-flash
        agent_class: LlmAgent
        instruction: Test snake_case to camelCase conversion
        disallow_transfer_to_parent: true
        disallow_transfer_to_peers: false
        output_key: test_output_key
        include_contents: none
        generate_content_config:
          temperature: 0.7
          top_p: 0.9
          max_output_tokens: 2048
          response_mime_type: "text/plain"
        tools:
          - name: McpToolset
            args:
              stdio_server_params:
                command: "test-cmd"
                args: ["--verbose"]
                env:
                  TEST_ENV: "value"
              tool_filter: ["tool1", "tool2"]
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;

    assertThat(llmAgent.name()).isEqualTo("snake_case_test_agent");
    assertThat(llmAgent.disallowTransferToParent()).isTrue();
    assertThat(llmAgent.disallowTransferToPeers()).isFalse();
    assertThat(llmAgent.outputKey()).hasValue("test_output_key");
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.NONE);

    assertThat(llmAgent.generateContentConfig()).isPresent();
    GenerateContentConfig config = llmAgent.generateContentConfig().get();
    assertThat(config).isNotNull();
    assertThat(config.temperature()).hasValue(0.7f);
    assertThat(config.topP()).hasValue(0.9f);
    assertThat(config.maxOutputTokens()).hasValue(2048);
    assertThat(config.responseMimeType()).hasValue("text/plain");

    assertThat(llmAgent.toolsets()).hasSize(1);
    assertThat(llmAgent.toolsets().get(0)).isInstanceOf(McpToolset.class);

    String originalYaml = Files.readString(configFile.toPath());
    assertThat(originalYaml).contains("agent_class");
    assertThat(originalYaml).contains("disallow_transfer_to_parent");
    assertThat(originalYaml).contains("disallow_transfer_to_peers");
    assertThat(originalYaml).contains("output_key");
    assertThat(originalYaml).contains("include_contents");
    assertThat(originalYaml).contains("generate_content_config");
    assertThat(originalYaml).contains("max_output_tokens");
    assertThat(originalYaml).contains("response_mime_type");
    assertThat(originalYaml).contains("stdio_server_params");
    assertThat(originalYaml).contains("tool_filter");
  }

  @Test
  public void fromConfig_withFullyQualifiedMcpToolset_loadsToolsetViaReflection()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("with_fq_mcp_toolset.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: fq_mcp_agent
        model: gemini-1.5-flash
        instruction: You are an agent that uses a fully qualified MCP toolset.
        agent_class: LlmAgent
        tools:
          - name: com.google.adk.tools.mcp.McpToolset
            args:
              stdio_server_params:
                command: "npx"
                args:
                  - "-y"
                  - "@notionhq/notion-mcp-server"
                env:
                  OPENAPI_MCP_HEADERS: '{"Authorization": "Bearer fake-key"}'
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.toolsets()).hasSize(1);
    assertThat(llmAgent.toolsets().get(0)).isInstanceOf(McpToolset.class);
  }

  @Test
  public void fromConfig_withIncludeContentsNone_setsIncludeContentsToNone()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("include_contents_none.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: includeContentsNoneAgent
        description: Agent with include_contents set to NONE
        instruction: test instruction
        agent_class: LlmAgent
        include_contents: none
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.NONE);
  }

  @Test
  public void fromConfig_withIncludeContentsDefault_setsIncludeContentsToDefault()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("include_contents_default.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: includeContentsDefaultAgent
        description: Agent with include_contents set to DEFAULT
        instruction: test instruction
        agent_class: LlmAgent
        include_contents: default
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.DEFAULT);
  }

  @Test
  public void fromConfig_withIncludeContentsLowercase_handlesCorrectly()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("include_contents_lowercase.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: includeContentsLowercaseAgent
        description: Agent with include_contents in lowercase
        instruction: test instruction
        agent_class: LlmAgent
        include_contents: none
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.NONE);
  }

  @Test
  public void fromConfig_withIncludeContentsMixedCase_handlesCorrectly()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("include_contents_mixedcase.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: includeContentsMixedCaseAgent
        description: Agent with include_contents in mixed case
        instruction: test instruction
        agent_class: LlmAgent
        include_contents: default
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.DEFAULT);
  }

  @Test
  public void fromConfig_withoutIncludeContents_defaultsToDefault()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("no_include_contents.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: noIncludeContentsAgent
        description: Agent without include_contents field
        instruction: test instruction
        agent_class: LlmAgent
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.DEFAULT);
  }

  @Test
  public void fromConfig_withInvalidIncludeContents_throwsException() throws IOException {
    File configFile = tempFolder.newFile("invalid_include_contents.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: invalidIncludeContentsAgent
        description: Agent with invalid include_contents value
        instruction: test instruction
        agent_class: LlmAgent
        include_contents: INVALID_VALUE
        """);
    String configPath = configFile.getAbsolutePath();

    ConfigurationException exception =
        assertThrows(ConfigurationException.class, () -> ConfigAgentUtils.fromConfig(configPath));

    assertThat(exception).hasMessageThat().contains("Failed to load or parse config file");

    Throwable cause = exception.getCause();
    assertThat(cause).isNotNull();
  }

  @Test
  public void fromConfig_withIncludeContentsAndOtherFields_parsesAllFieldsCorrectly()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("complete_config.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: completeAgent
        description: Agent with all fields including include_contents
        instruction: You are a complete test agent
        agent_class: LlmAgent
        model: gemini-1.5-flash
        include_contents: none
        output_key: testOutput
        disallow_transfer_to_parent: true
        disallow_transfer_to_peers: false
        tools:
          - name: google_search
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.name()).isEqualTo("completeAgent");
    assertThat(llmAgent.description())
        .isEqualTo("Agent with all fields including include_contents");
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.NONE);
    assertThat(llmAgent.outputKey()).hasValue("testOutput");
    assertThat(llmAgent.disallowTransferToParent()).isTrue();
    assertThat(llmAgent.disallowTransferToPeers()).isFalse();
    assertThat(llmAgent.tools().blockingGet()).hasSize(1);
    assertThat(llmAgent.model()).isPresent();
  }

  @Test
  public void fromConfig_withOutputKey_setsOutputKeyOnAgent()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("output_key.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        agent_class: LlmAgent
        name: InitialWriterAgent
        model: gemini-2.0-flash
        description: Writes the initial document draft based on the topic
        instruction: |
          You are a Creative Writing Assistant tasked with starting a story.
          Write the first draft of a short story.
        output_key: current_document
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.name()).isEqualTo("InitialWriterAgent");
    assertThat(llmAgent.outputKey()).hasValue("current_document");
  }

  @Test
  public void fromConfig_withEmptyOutputKey_doesNotSetOutputKey()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("empty_output_key.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        agent_class: LlmAgent
        name: AgentWithoutOutputKey
        instruction: Test instruction
        output_key:
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.outputKey()).isEmpty();
  }

  @Test
  public void fromConfig_withOutputKeyAndOtherFields_parsesAllFields()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("output_key_complete.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        agent_class: LlmAgent
        name: CompleteAgentWithOutputKey
        model: gemini-2.0-flash
        description: Agent with output key and other configurations
        instruction: Process and store output
        output_key: result_data
        include_contents: NONE
        disallow_transfer_to_parent: true
        disallow_transfer_to_peers: false
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.name()).isEqualTo("CompleteAgentWithOutputKey");
    assertThat(llmAgent.outputKey()).hasValue("result_data");
    assertThat(llmAgent.includeContents()).isEqualTo(LlmAgent.IncludeContents.NONE);
    assertThat(llmAgent.disallowTransferToParent()).isTrue();
    assertThat(llmAgent.disallowTransferToPeers()).isFalse();
    assertThat(llmAgent.model()).isPresent();
  }

  @Test
  public void fromConfig_withGenerateContentConfigSafetySettings()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("generate_content_config_safety.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        agent_class: LlmAgent
        model: gemini-2.5-flash
        name: root_agent
        description: dice agent
        instruction: You are a helpful assistant
        generate_content_config:
          safety_settings:
            - category: HARM_CATEGORY_DANGEROUS_CONTENT
              threshold: 'OFF'
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.name()).isEqualTo("root_agent");
    assertThat(llmAgent.description()).isEqualTo("dice agent");
    assertThat(llmAgent.model()).isPresent();
    assertThat(llmAgent.model().get().modelName()).hasValue("gemini-2.5-flash");

    assertThat(llmAgent.generateContentConfig()).isPresent();
    GenerateContentConfig config = llmAgent.generateContentConfig().get();
    assertThat(config).isNotNull();
    assertThat(config.safetySettings()).isPresent();
    assertThat(config.safetySettings().get()).hasSize(1);

    // Verify the safety settings are parsed correctly
    assertThat(config.safetySettings().get().get(0).category()).isPresent();
    assertThat(config.safetySettings().get().get(0).category().get().toString())
        .isEqualTo("HARM_CATEGORY_DANGEROUS_CONTENT");
    assertThat(config.safetySettings().get().get(0).threshold()).isPresent();
    assertThat(config.safetySettings().get().get(0).threshold().get().toString()).isEqualTo("OFF");
  }

  @Test
  public void fromConfig_withExamplesList_appendsExamplesInFlow()
      throws IOException, ConfigurationException {
    // Register an ExampleTool instance under short name used by YAML
    ComponentRegistry originalRegistry = ComponentRegistry.getInstance();
    class TestRegistry extends ComponentRegistry {
      TestRegistry() {
        super();
      }
    }
    ComponentRegistry testRegistry = new TestRegistry();
    Example example =
        Example.builder()
            .input(Content.fromParts(Part.fromText("qin")))
            .output(ImmutableList.of(Content.fromParts(Part.fromText("qout"))))
            .build();
    testRegistry.register(
        "multi_agent_llm_config.example_tool", ExampleTool.builder().addExample(example).build());
    ComponentRegistry.setInstance(testRegistry);
    File configFile = tempFolder.newFile("with_examples.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: examples_agent
        description: Agent with examples configured via tool
        instruction: You are a test agent
        agent_class: LlmAgent
        model: gemini-2.0-flash
        tools:
          - name: multi_agent_llm_config.example_tool
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent;
    try {
      agent = ConfigAgentUtils.fromConfig(configPath);
    } finally {
      ComponentRegistry.setInstance(originalRegistry);
    }

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;

    // Process tools to verify ExampleTool appends the examples to the request
    LlmRequest.Builder requestBuilder = LlmRequest.builder().model("gemini-2.0-flash");
    InvocationContext context = TestUtils.createInvocationContext(agent);
    llmAgent
        .canonicalTools(new ReadonlyContext(context))
        .concatMapCompletable(
            tool -> tool.processLlmRequest(requestBuilder, ToolContext.builder(context).build()))
        .blockingAwait();
    LlmRequest updated = requestBuilder.build();
    // Verify ExampleTool appended a system instruction with examples
    assertThat(updated.getSystemInstructions()).isNotEmpty();
  }

  @Test
  public void resolveSubAgents_withCode_resolvesSuccessfully()
      throws IOException, ConfigurationException {
    // Create a test agent
    LlmAgent testAgent =
        LlmAgent.builder()
            .name("test_agent")
            .description("Test agent for code resolution")
            .instruction("Test instruction")
            .build();

    // Register test agent in the ComponentRegistry using Python ADK style key
    ComponentRegistry.getInstance().register("sub_agents_config.test_agent.agent", testAgent);

    File mainAgentFile = tempFolder.newFile("main_agent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with code subagent
        instruction: You are a main agent
        sub_agents:
          - code: sub_agents_config.test_agent.agent
        """);

    BaseAgent mainAgent = ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath());

    assertThat(mainAgent).isNotNull();
    assertThat(mainAgent.subAgents()).hasSize(1);
    BaseAgent subAgent = mainAgent.subAgents().get(0);
    assertThat(subAgent).isInstanceOf(LlmAgent.class);
    assertThat(subAgent.name()).isEqualTo("test_agent");
    assertThat(subAgent.description()).isEqualTo("Test agent for code resolution");
  }

  @Test
  public void resolveSubAgents_withLifeAgentUsingCode_resolvesSuccessfully()
      throws IOException, ConfigurationException {
    // Create a LifeAgent similar to the Python ADK example
    LlmAgent lifeAgent =
        LlmAgent.builder()
            .name("life_agent")
            .description("Life agent")
            .instruction(
                "You are a life agent. You are responsible for answering questions about life.")
            .build();

    // Register the LifeAgent in the ComponentRegistry using Python ADK style key
    ComponentRegistry.getInstance().register("sub_agents_config.life_agent.agent", lifeAgent);

    File mainAgentFile = tempFolder.newFile("root_agent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        name: root_agent
        model: gemini-2.0-flash
        description: Root agent
        instruction: |
          If the user query is about life, you should route it to the life sub-agent.
          If the user query is about work, you should route it to the work sub-agent.
          If the user query is about anything else, you should answer it yourself.
        sub_agents:
          - code: sub_agents_config.life_agent.agent
        """);

    BaseAgent rootAgent = ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath());

    assertThat(rootAgent).isNotNull();
    assertThat(rootAgent.name()).isEqualTo("root_agent");
    assertThat(rootAgent.description()).isEqualTo("Root agent");
    assertThat(rootAgent).isInstanceOf(LlmAgent.class);

    // Verify the life agent is properly loaded as a subagent
    assertThat(rootAgent.subAgents()).hasSize(1);
    BaseAgent subAgent = rootAgent.subAgents().get(0);
    assertThat(subAgent).isInstanceOf(LlmAgent.class);
    assertThat(subAgent.name()).isEqualTo("life_agent");
    assertThat(subAgent.description()).isEqualTo("Life agent");

    LlmAgent llmSubAgent = (LlmAgent) subAgent;
    assertThat(llmSubAgent.instruction().toString())
        .contains("You are a life agent. You are responsible for answering questions about life.");
  }

  @Test
  public void fromConfig_agentClassWithGooglePrefix_resolvesToLlmAgent()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("google_prefix_agent.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: prefixed_agent
        description: Agent declared with Python-style qualified class
        instruction: test instruction
        agent_class: google.adk.agents.LlmAgent
        """);

    BaseAgent agent = ConfigAgentUtils.fromConfig(configFile.getAbsolutePath());

    assertThat(agent).isNotNull();
    assertThat(agent).isInstanceOf(LlmAgent.class);
    assertThat(agent.name()).isEqualTo("prefixed_agent");
  }

  @Test
  public void resolveSubAgents_withInvalidCodeKey_throwsConfigurationException()
      throws IOException {
    File mainAgentFile = tempFolder.newFile("invalid_code_subagent.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        name: root_agent
        description: Root agent
        instruction: test instruction
        sub_agents:
          - code: non.existent.registry.key
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
    // Unwrap nested causes (InvocationTargetException -> ConfigurationException -> ...)
    StringBuilder messages = new StringBuilder();
    Throwable t = exception.getCause();
    while (t != null) {
      messages.append(t.getMessage()).append("\n");
      t = t.getCause();
    }
    assertThat(messages.toString()).contains("Failed to resolve subagent");
    assertThat(messages.toString()).contains("code key: non.existent.registry.key");
  }

  @Test
  public void resolveSubAgents_withNullName_handlesGracefully() throws IOException {
    // This test catches the mutation where null check for subAgentConfig.name() is broken
    // Testing both scenarios: missing 'code' field and invalid 'code' field

    File mainAgentFile = tempFolder.newFile("main_agent_null_name.yaml");
    Files.writeString(
        mainAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with unnamed subagent
        instruction: You are a main agent
        sub_agents:
          - name:
        """);

    ConfigurationException exception1 =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile.getAbsolutePath()));

    Throwable cause1 = exception1;
    while (cause1.getCause() != null) {
      cause1 = cause1.getCause();
    }
    assertThat(cause1).hasMessageThat().doesNotContain("'null'");
    assertThat(cause1).hasMessageThat().contains("must specify either 'configPath' or 'code'");

    File mainAgentFile2 = tempFolder.newFile("main_agent_invalid_code.yaml");
    Files.writeString(
        mainAgentFile2.toPath(),
        """
        agent_class: LlmAgent
        name: main_agent
        description: Main agent with unnamed subagent
        instruction: You are a main agent
        sub_agents:
          - code: nonexistent.agent
        """);

    ConfigurationException exception2 =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(mainAgentFile2.getAbsolutePath()));

    Throwable cause2 = exception2;
    while (cause2.getCause() != null) {
      cause2 = cause2.getCause();
    }
    assertThat(cause2).hasMessageThat().doesNotContain("'null'");
    assertThat(cause2).hasMessageThat().contains("from registry with code key: nonexistent.agent");
  }

  @Test
  public void fromConfig_withConfiguredCallbacks_resolvesCallbacks()
      throws IOException, ConfigurationException {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    String pfx = "test.callbacks.";
    registry.register(
        pfx + "before_agent_1", (Callbacks.BeforeAgentCallback) (unusedCtx) -> Maybe.empty());
    registry.register(
        pfx + "before_agent_2", (Callbacks.BeforeAgentCallback) (unusedCtx) -> Maybe.empty());
    registry.register(
        pfx + "after_agent_1", (Callbacks.AfterAgentCallback) (unusedCtx) -> Maybe.empty());
    registry.register(
        pfx + "before_model_1",
        (Callbacks.BeforeModelCallback) (unusedCtx, unusedReq) -> Maybe.empty());
    registry.register(
        pfx + "after_model_1",
        (Callbacks.AfterModelCallback) (unusedCtx, unusedResp) -> Maybe.empty());
    registry.register(
        pfx + "before_tool_1",
        (Callbacks.BeforeToolCallback)
            (unusedInv, unusedTool, unusedArgs, unusedToolCtx) -> Maybe.empty());
    registry.register(
        pfx + "after_tool_1",
        (Callbacks.AfterToolCallback)
            (unusedInv, unusedTool, unusedArgs, unusedToolCtx, unusedResp) -> Maybe.empty());

    File configFile = tempFolder.newFile("with_callbacks.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: callback_agent
        description: Agent with configured callbacks
        instruction: test instruction
        agent_class: LlmAgent
        before_agent_callbacks:
          - name: test.callbacks.before_agent_1
          - name: test.callbacks.before_agent_2
        after_agent_callbacks:
          - name: test.callbacks.after_agent_1
        before_model_callbacks:
          - name: test.callbacks.before_model_1
        after_model_callbacks:
          - name: test.callbacks.after_model_1
        before_tool_callbacks:
          - name: test.callbacks.before_tool_1
        after_tool_callbacks:
          - name: test.callbacks.after_tool_1
        """);

    BaseAgent agent = ConfigAgentUtils.fromConfig(configFile.getAbsolutePath());

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llm = (LlmAgent) agent;

    assertThat(agent.beforeAgentCallback()).isPresent();
    assertThat(agent.beforeAgentCallback().get()).hasSize(2);
    assertThat(agent.afterAgentCallback()).isPresent();
    assertThat(agent.afterAgentCallback().get()).hasSize(1);

    assertThat(llm.beforeModelCallback()).hasSize(1);
    assertThat(llm.afterModelCallback()).hasSize(1);

    assertThat(llm.beforeToolCallback()).hasSize(1);
    assertThat(llm.afterToolCallback()).hasSize(1);
  }

  @Test
  public void fromConfig_withInvalidBeforeAgentCallback_throwsConfigurationException()
      throws IOException {
    File configFile = tempFolder.newFile("invalid_before_agent_callback.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: invalid_callback_agent
        description: Agent with invalid before_agent_callback
        instruction: test instruction
        agent_class: LlmAgent
        before_agent_callbacks:
          - name: non.existent.callback
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(configFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
    assertThat(exception.getCause())
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Invalid before_agent_callback: non.existent.callback");
  }

  @Test
  public void fromConfig_withInvalidAfterAgentCallback_throwsConfigurationException()
      throws IOException {
    File configFile = tempFolder.newFile("invalid_after_agent_callback.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: invalid_callback_agent
        description: Agent with invalid after_agent_callback
        instruction: test instruction
        agent_class: LlmAgent
        after_agent_callbacks:
          - name: non.existent.after.callback
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(configFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
    assertThat(exception.getCause())
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("Invalid after_agent_callback: non.existent.after.callback");
  }

  @Test
  public void fromConfig_withInvalidBeforeModelCallback_throwsConfigurationException()
      throws IOException {
    File configFile = tempFolder.newFile("invalid_before_model_callback.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: invalid_callback_agent
        description: Agent with invalid before_model_callback
        instruction: test instruction
        agent_class: LlmAgent
        before_model_callbacks:
          - name: non.existent.model.callback
        """);

    ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () -> ConfigAgentUtils.fromConfig(configFile.getAbsolutePath()));

    assertThat(exception).hasMessageThat().contains("Failed to create agent from config");
    assertThat(exception.getCause().getCause())
        .hasMessageThat()
        .isEqualTo("Invalid before_model_callback: non.existent.model.callback");
  }

  @Test
  public void testLlmAgentConfigAccessors() {
    LlmAgentConfig config = new LlmAgentConfig();

    assertThat(config.agentClass()).isEqualTo("LlmAgent");

    config.setModel("test-model");
    assertThat(config.model()).isEqualTo("test-model");

    config.setInstruction("test instruction");
    assertThat(config.instruction()).isEqualTo("test instruction");

    config.setDisallowTransferToParent(true);
    assertThat(config.disallowTransferToParent()).isTrue();

    config.setDisallowTransferToPeers(false);
    assertThat(config.disallowTransferToPeers()).isFalse();

    config.setOutputKey("test-output-key");
    assertThat(config.outputKey()).isEqualTo("test-output-key");

    config.setIncludeContents(LlmAgent.IncludeContents.NONE);
    assertThat(config.includeContents()).isEqualTo(LlmAgent.IncludeContents.NONE);

    GenerateContentConfig contentConfig = GenerateContentConfig.builder().temperature(0.8f).build();
    config.setGenerateContentConfig(contentConfig);
    assertThat(config.generateContentConfig()).isEqualTo(contentConfig);

    List<LlmAgentConfig.CallbackRef> beforeAgentCallbacks = new ArrayList<>();
    beforeAgentCallbacks.add(new LlmAgentConfig.CallbackRef("callback1"));
    config.setBeforeAgentCallbacks(beforeAgentCallbacks);
    assertThat(config.beforeAgentCallbacks()).hasSize(1);
    assertThat(config.beforeAgentCallbacks().get(0).name()).isEqualTo("callback1");

    List<LlmAgentConfig.CallbackRef> afterAgentCallbacks = new ArrayList<>();
    afterAgentCallbacks.add(new LlmAgentConfig.CallbackRef("callback2"));
    config.setAfterAgentCallbacks(afterAgentCallbacks);
    assertThat(config.afterAgentCallbacks()).hasSize(1);
    assertThat(config.afterAgentCallbacks().get(0).name()).isEqualTo("callback2");

    List<LlmAgentConfig.CallbackRef> beforeModelCallbacks = new ArrayList<>();
    beforeModelCallbacks.add(new LlmAgentConfig.CallbackRef("callback3"));
    config.setBeforeModelCallbacks(beforeModelCallbacks);
    assertThat(config.beforeModelCallbacks()).hasSize(1);
    assertThat(config.beforeModelCallbacks().get(0).name()).isEqualTo("callback3");

    List<LlmAgentConfig.CallbackRef> afterModelCallbacks = new ArrayList<>();
    afterModelCallbacks.add(new LlmAgentConfig.CallbackRef("callback4"));
    config.setAfterModelCallbacks(afterModelCallbacks);
    assertThat(config.afterModelCallbacks()).hasSize(1);
    assertThat(config.afterModelCallbacks().get(0).name()).isEqualTo("callback4");

    List<LlmAgentConfig.CallbackRef> beforeToolCallbacks = new ArrayList<>();
    beforeToolCallbacks.add(new LlmAgentConfig.CallbackRef("callback5"));
    config.setBeforeToolCallbacks(beforeToolCallbacks);
    assertThat(config.beforeToolCallbacks()).hasSize(1);
    assertThat(config.beforeToolCallbacks().get(0).name()).isEqualTo("callback5");

    List<LlmAgentConfig.CallbackRef> afterToolCallbacks = new ArrayList<>();
    afterToolCallbacks.add(new LlmAgentConfig.CallbackRef("callback6"));
    config.setAfterToolCallbacks(afterToolCallbacks);
    assertThat(config.afterToolCallbacks()).hasSize(1);
    assertThat(config.afterToolCallbacks().get(0).name()).isEqualTo("callback6");

    List<BaseTool.ToolConfig> tools = new ArrayList<>();
    BaseTool.ToolConfig toolConfig = new BaseTool.ToolConfig();
    toolConfig.setName("test-tool");
    tools.add(toolConfig);
    config.setTools(tools);
    assertThat(config.tools()).hasSize(1);
    assertThat(config.tools().get(0).name()).isEqualTo("test-tool");
  }

  @Test
  public void testCallbackRefAccessors() {
    LlmAgentConfig.CallbackRef callbackRef = new LlmAgentConfig.CallbackRef("initial-name");
    assertThat(callbackRef.name()).isEqualTo("initial-name");

    callbackRef.setName("updated-name");
    assertThat(callbackRef.name()).isEqualTo("updated-name");
  }

  @Test
  public void fromConfig_validYamlLoopAgent_createsLoopAgent()
      throws IOException, ConfigurationException {
    File subAgentFile = tempFolder.newFile("sub_agent.yaml");
    Files.writeString(
        subAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: sub_agent
        description: A test subagent
        instruction: You are a helpful subagent
        """);

    File configFile = tempFolder.newFile("loop_agent.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: testLoopAgent
        description: A test loop agent
        agent_class: LoopAgent
        max_iterations: 5
        sub_agents:
          - config_path: sub_agent.yaml
        """);
    String configPath = configFile.getAbsolutePath();
    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);
    assertThat(agent).isNotNull();
    assertThat(agent).isInstanceOf(LoopAgent.class);
  }
}
