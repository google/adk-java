package com.google.adk.agents.base.tools;

import static com.google.api.client.util.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.GoogleMapsTool;
import jakarta.inject.Provider;

public class MapsAgentToolProvider implements Provider<AgentTool> {

  private static final String KEY = "tool.maps";

  public MapsAgentToolProvider(AppConfig appConfig) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
  }

  private final AppConfig appConfig;

  @Override
  public AgentTool get() {
    GoogleMapsTool tool = new GoogleMapsTool();
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
    String agentKey = appConfig.getProperties().get(key);
    return appConfig.getAgentConfigs().get(agentKey);
  }
}
