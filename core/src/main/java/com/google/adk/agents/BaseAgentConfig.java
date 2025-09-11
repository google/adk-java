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

import java.util.List;

/**
 * Base configuration for all agents with subagent support.
 *
 * <p>TODO: Config agent features are not yet ready for public use.
 */
public class BaseAgentConfig {
  private String name;
  private String description = "";
  private String agentClass;
  private List<AgentRefConfig> subAgents;

  /**
   * Configuration for referencing other agents (subagents). Supports both config-based references
   * (YAML files) and programmatic references (via code registry).
   */
  public static class AgentRefConfig {
    private String configPath;
    private String code;

    public AgentRefConfig() {}

    /**
     * Constructor for config-based agent reference.
     *
     * @param configPath The path to the subagent's config file
     */
    public AgentRefConfig(String configPath) {
      this.configPath = configPath;
    }

    public String configPath() {
      return configPath;
    }

    public void setConfigPath(String configPath) {
      this.configPath = configPath;
    }

    public String code() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }
  }

  public BaseAgentConfig() {}

  /**
   * Constructor with basic fields.
   *
   * @param name The agent name
   * @param description The agent description
   * @param agentClass The agent class name
   */
  public BaseAgentConfig(String name, String description, String agentClass) {
    this.name = name;
    this.description = description;
    this.agentClass = agentClass;
  }

  public String name() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String description() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String agentClass() {
    return agentClass;
  }

  public void setAgentClass(String agentClass) {
    this.agentClass = agentClass;
  }

  public List<AgentRefConfig> subAgents() {
    return subAgents;
  }

  public void setSubAgents(List<AgentRefConfig> subAgents) {
    this.subAgents = subAgents;
  }
}
