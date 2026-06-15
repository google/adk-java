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

import com.google.common.collect.ImmutableList;
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
  private ImmutableList<AgentRefConfig> subAgents = ImmutableList.of();

  // Callback configuration (names resolved via ComponentRegistry)
  private ImmutableList<CallbackRef> beforeAgentCallbacks = ImmutableList.of();
  private ImmutableList<CallbackRef> afterAgentCallbacks = ImmutableList.of();

  /** Reference to a callback stored in the ComponentRegistry. */
  public static class CallbackRef {
    private String name;

    public CallbackRef() {}

    public CallbackRef(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

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

  public BaseAgentConfig(String agentClass) {
    this.agentClass = agentClass;
  }

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

  public void setAgentClass(String agentClass) {
    this.agentClass = agentClass;
  }

  public String agentClass() {
    return agentClass;
  }

  public ImmutableList<AgentRefConfig> subAgents() {
    return subAgents;
  }

  public void setSubAgents(List<AgentRefConfig> subAgents) {
    this.subAgents = subAgents == null ? ImmutableList.of() : ImmutableList.copyOf(subAgents);
  }

  public ImmutableList<CallbackRef> beforeAgentCallbacks() {
    return beforeAgentCallbacks;
  }

  public void setBeforeAgentCallbacks(List<CallbackRef> beforeAgentCallbacks) {
    this.beforeAgentCallbacks =
        beforeAgentCallbacks == null
            ? ImmutableList.of()
            : ImmutableList.copyOf(beforeAgentCallbacks);
  }

  public ImmutableList<CallbackRef> afterAgentCallbacks() {
    return afterAgentCallbacks;
  }

  public void setAfterAgentCallbacks(List<CallbackRef> afterAgentCallbacks) {
    this.afterAgentCallbacks =
        afterAgentCallbacks == null
            ? ImmutableList.of()
            : ImmutableList.copyOf(afterAgentCallbacks);
  }
}
