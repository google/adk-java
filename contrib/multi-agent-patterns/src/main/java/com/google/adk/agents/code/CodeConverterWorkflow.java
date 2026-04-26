package com.google.adk.agents.code;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.agents.base.tools.CodeExecutionToolProvider;
import com.google.adk.tools.AgentTool;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Provider;
import java.util.Map;

public class CodeConverterWorkflow implements Provider<SequentialAgent> {

  private static final String CTA_KEY = "code.converter.agent";
  private static final String CCA_KEY = "code.critic.agent";
  private static final String CFA_KEY = "code.refactor.agent";

  public CodeConverterWorkflow(Map<String, AgentConfig> configs) {
    this.configs = ImmutableMap.copyOf(configs);
  }

  private final ImmutableMap<String, AgentConfig> configs;

  @Override
  public SequentialAgent get() {
    AgentTool tool = new CodeExecutionToolProvider(configs).get();
    LlmAgent convert = new LlmAgentProvider(AgentContext.build(configs.get(CTA_KEY), tool)).get();
    LlmAgent review = new LlmAgentProvider(AgentContext.build(configs.get(CCA_KEY))).get();
    LlmAgent refactor = new LlmAgentProvider(AgentContext.build(configs.get(CFA_KEY), tool)).get();
    return SequentialAgent.builder() //
        .name("CodeConverterWorkflow") //
        .description("Runs code conversion with review-refactor") //
        .subAgents(convert, review, refactor) //
        .build();
  }
}
