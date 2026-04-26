package com.google.adk.agents.report;

import static com.google.api.client.util.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.agents.base.tools.SearchAgentToolProvider;
import com.google.adk.tools.AgentTool;
import jakarta.inject.Provider;
import java.util.List;

public class ReportRootAgent implements Provider<LlmAgent> {

  private static final String RRW_KEY = "report.root";
  private static final String RTR_KEY = "report.topic";
  private static final String RCA_KEY = "report.content";
  private static final String RAA_KEY = "report.research";

  public ReportRootAgent(AppConfig appConfig) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
  }

  private final AppConfig appConfig;

  @Override
  public LlmAgent get() {
    AgentTool assistant = AgentTool.create(researchAssistant());
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(agentConfig(RRW_KEY)) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .withTools(assistant) //
            .build();
    return new LlmAgentProvider(ctx).get();
  }

  private LlmAgent researchAssistant() {
    LlmAgent search = webSearch();
    LlmAgent analyst = summarizer();
    AgentConfig config = agentConfig(RAA_KEY);
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(config) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .withSubAgents(List.of(search, analyst)) //
            .build();
    return new LlmAgentProvider(ctx).get();
  }

  private LlmAgent summarizer() {
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(agentConfig(RCA_KEY)) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .build();
    return new LlmAgentProvider(ctx).get();
  }

  private LlmAgent webSearch() {
    AgentTool search = new SearchAgentToolProvider(appConfig).get();
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(agentConfig(RTR_KEY)) //
            .withTools(search) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .build();
    return new LlmAgentProvider(ctx).get();
  }

  private AgentConfig agentConfig(String key) {
    String agentKey = appConfig.getProperties().get(key).trim();
    return appConfig.getAgentConfigs().get(agentKey);
  }
}
