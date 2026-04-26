package com.google.adk.agents.traveler;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.asList;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.LlmAgentProvider;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

public class TravelerRootAgent implements Provider<LlmAgent> {

  private static final String TRA_KEY = "traveler.root";
  private static final String TIA_KEY = "traveler.itinerary";

  @Inject
  public TravelerRootAgent(AppConfig appConfig) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
  }

  private final AppConfig appConfig;

  @Override
  public LlmAgent get() {
    SequentialAgent subAgent = tripAssistant();
    AgentConfig rootConfig = agentConfig(TRA_KEY);
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(rootConfig) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .withSubAgents(asList(subAgent)) //
            .build(); //
    return new LlmAgentProvider(ctx).get();
  }

  private SequentialAgent tripAssistant() {
    ParallelAgent team = new TripSearchTeam(appConfig).get();
    return SequentialAgent.builder() //
        .name("TripAssistantWorkflow") //
        .description("Travel Planning Agent with parallel search") //
        .subAgents(team, itinerary()) //
        .build();
  }

  private LlmAgent itinerary() {
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(agentConfig(TIA_KEY)) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .build();
    return new LlmAgentProvider(ctx).get();
  }

  private AgentConfig agentConfig(String key) {
    String agentKey = appConfig.getProperties().get(key);
    return appConfig.getAgentConfigs().get(agentKey);
  }
}
