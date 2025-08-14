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
import com.google.adk.tools.mcp.McpToolset;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "agentClass '"
                + customAgentClass
                + "' is not supported. It must be a subclass of BaseAgent.");
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
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "agentClass '"
                + customAgentClass
                + "' is not supported. It must be a subclass of BaseAgent.");
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
    assertThat(exception.getCause()).isNotNull();
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
          - name: GoogleSearchTool
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.tools()).hasSize(1);
    assertThat(llmAgent.tools().get(0).name()).isEqualTo("google_search");
  }

  @Test
  public void fromConfig_withBuiltInTool_loadsToolWithUnderscore()
      throws IOException, ConfigurationException {
    File configFile = tempFolder.newFile("with_tool_underscore.yaml");
    Files.writeString(
        configFile.toPath(),
        """
        name: search_agent
        model: gemini-1.5-flash
        description: 'an agent whose job it is to perform Google search queries and answer questions about the results.'
        instruction: You are an agent whose job is to perform Google search queries and answer questions about the results.
        agent_class: LlmAgent
        tools:
          - name: google_search_tool
        """);
    String configPath = configFile.getAbsolutePath();

    BaseAgent agent = ConfigAgentUtils.fromConfig(configPath);

    assertThat(agent).isInstanceOf(LlmAgent.class);
    LlmAgent llmAgent = (LlmAgent) agent;
    assertThat(llmAgent.tools()).hasSize(1);
    assertThat(llmAgent.tools().get(0).name()).isEqualTo("google_search");
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
}
