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

import com.google.adk.agents.LlmAgent.IncludeContents;
import com.google.adk.tools.BaseTool.ToolConfig;
import com.google.genai.types.GenerateContentConfig;
import java.util.List;

/**
 * Configuration for LlmAgent.
 *
 * <p>TODO: Config agent features are not yet ready for public use.
 */
public class LlmAgentConfig extends BaseAgentConfig {
  private String model;
  private String instruction;
  private Boolean disallowTransferToParent;
  private Boolean disallowTransferToPeers;
  private String outputKey;
  private List<ToolConfig> tools;
  private IncludeContents includeContents;
  private GenerateContentConfig generateContentConfig;

  public LlmAgentConfig() {
    super();
    setAgentClass("LlmAgent");
  }

  // Accessors
  public String model() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public String instruction() {
    return instruction;
  }

  public void setInstruction(String instruction) {
    this.instruction = instruction;
  }

  public Boolean disallowTransferToParent() {
    return disallowTransferToParent;
  }

  public void setDisallowTransferToParent(Boolean disallowTransferToParent) {
    this.disallowTransferToParent = disallowTransferToParent;
  }

  public Boolean disallowTransferToPeers() {
    return disallowTransferToPeers;
  }

  public void setDisallowTransferToPeers(Boolean disallowTransferToPeers) {
    this.disallowTransferToPeers = disallowTransferToPeers;
  }

  public String outputKey() {
    return outputKey;
  }

  public void setOutputKey(String outputKey) {
    this.outputKey = outputKey;
  }

  public List<ToolConfig> tools() {
    return tools;
  }

  public void setTools(List<ToolConfig> tools) {
    this.tools = tools;
  }

  public IncludeContents includeContents() {
    return includeContents;
  }

  public void setIncludeContents(IncludeContents includeContents) {
    this.includeContents = includeContents;
  }

  public GenerateContentConfig generateContentConfig() {
    return generateContentConfig;
  }

  public void setGenerateContentConfig(GenerateContentConfig generateContentConfig) {
    this.generateContentConfig = generateContentConfig;
  }
}
