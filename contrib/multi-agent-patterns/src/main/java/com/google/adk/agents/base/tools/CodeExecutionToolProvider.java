package com.google.adk.agents.base.tools;

import static com.google.common.collect.ImmutableMap.copyOf;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BuiltInCodeExecutionTool;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Provider;
import java.util.Map;

public class CodeExecutionToolProvider implements Provider<AgentTool> {

  private static final String KEY = "tool.code.execution";

  public CodeExecutionToolProvider(Map<String, AgentConfig> configs) {
    this.configs = copyOf(configs);
  }

  private final ImmutableMap<String, AgentConfig> configs;

  @Override
  public AgentTool get() {
    BuiltInCodeExecutionTool tool = new BuiltInCodeExecutionTool();
    AgentContext ctx = AgentContext.build(configs.get(KEY), tool);
    LlmAgent agent = new LlmAgentProvider(ctx).get();
    return AgentTool.create(agent);
  }
}
