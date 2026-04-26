package com.google.adk.agents.report;

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.copyOf;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentConfigsProvider;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.agents.base.tools.SearchAgentToolProvider;
import com.google.adk.tools.AgentTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Provider;
import java.util.Map;

public class ReportRootAgent implements Provider<LlmAgent> {

  private static final String RRW_KEY = "report.root.writer";
  private static final String RTR_KEY = "report.topic.researcher";
  private static final String RCA_KEY = "report.content.analyst";
  private static final String RAA_KEY = "report.research.coordinator";

  public ReportRootAgent(AgentConfigsProvider provider) {
    checkNotNull(provider, "provider");
    this.configs = provider.get();
  }

  public ReportRootAgent(Map<String, AgentConfig> configs) {
    this.configs = copyOf(configs);
  }

  private final ImmutableMap<String, AgentConfig> configs;

  @Override
  public LlmAgent get() {
    AgentTool assistant = AgentTool.create(researchAssistant(configs));
    AgentContext ctx = AgentContext.build(configs.get(RRW_KEY), assistant);
    return new LlmAgentProvider(ctx).get();
  }

  private LlmAgent researchAssistant(Map<String, AgentConfig> configs) {
    LlmAgent search = webSearch(configs);
    LlmAgent analyst = summarizer(configs);
    AgentConfig config = configs.get(RAA_KEY);
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(config) //
            .withSubAgents(ImmutableList.of(search, analyst)) //
            .build();
    return new LlmAgentProvider(ctx).get();
  }

  private LlmAgent summarizer(Map<String, AgentConfig> configs) {
    AgentContext ctx = AgentContext.build(configs.get(RCA_KEY));
    return new LlmAgentProvider(ctx).get();
  }

  private LlmAgent webSearch(Map<String, AgentConfig> configs) {
    AgentTool search = new SearchAgentToolProvider(configs).get();
    AgentContext ctx = AgentContext.build(configs.get(RTR_KEY), search);
    return new LlmAgentProvider(ctx).get();
  }
}
