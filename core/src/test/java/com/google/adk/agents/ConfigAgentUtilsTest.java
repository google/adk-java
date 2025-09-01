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
          - name: google_search
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
  public void fromConfig_withSubAgents_createsHierarchy()
      throws IOException, ConfigurationException {
    // Create subagent config file
    File subAgentFile = tempFolder.newFile("sub_agent.yaml");
    Files.writeString(
        subAgentFile.toPath(),
        """
        agent_class: LlmAgent
        name: sub_agent
        description: A test subagent
        instruction: You are a helpful subagent
        """);

    // Create main agent config file with subagent reference
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
}
