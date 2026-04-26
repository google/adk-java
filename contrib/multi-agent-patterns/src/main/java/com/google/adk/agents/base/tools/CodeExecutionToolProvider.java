package com.google.adk.agents.base.tools;

import static com.google.api.client.util.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BuiltInCodeExecutionTool;
import jakarta.inject.Provider;

public class CodeExecutionToolProvider implements Provider<AgentTool> {

  private static final String KEY = "tool.code";

  public CodeExecutionToolProvider(AppConfig appConfig) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
  }

  private final AppConfig appConfig;

  @Override
  public AgentTool get() {
    AgentConfig config = agentConfig(KEY);
    LlmAgent.Builder builder = LlmAgent.builder();
    builder.name(config.getName());
    builder.description(config.getDescription());
    builder.model(config.getModel());
    builder.instruction(config.getInstruction());
    builder.tools(new BuiltInCodeExecutionTool());
    // builder.codeExecutor(new BuiltInCodeExecutor());
    builder.generateContentConfig(appConfig.getContentConfig());
    return AgentTool.create(builder.build());
  }

  private AgentConfig agentConfig(String key) {
    String agentKey = appConfig.getProperties().get(key).trim();
    return appConfig.getAgentConfigs().get(agentKey);
  }
}
