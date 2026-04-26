package com.google.adk.agents.base.tools;

import static com.google.api.client.util.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.GoogleSearchTool;
import jakarta.inject.Provider;

public class SearchAgentToolProvider implements Provider<AgentTool> {

  private static final String KEY = "tool.search";

  public SearchAgentToolProvider(AppConfig appConfig) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
  }

  private final AppConfig appConfig;

  @Override
  public AgentTool get() {
    GoogleSearchTool tool = new GoogleSearchTool();
    AgentConfig config = agentConfig(KEY);
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(config) //
            .withTools(tool) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .build(); //
    LlmAgent agent = new LlmAgentProvider(ctx).get();
    return AgentTool.create(agent);
  }

  private AgentConfig agentConfig(String key) {
    String agentKey = appConfig.getProperties().get(key).trim();
    return appConfig.getAgentConfigs().get(agentKey);
  }
}
