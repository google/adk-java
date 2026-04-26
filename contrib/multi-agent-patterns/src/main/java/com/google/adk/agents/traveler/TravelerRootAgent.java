package com.google.adk.agents.traveler;

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.copyOf;
import static java.util.Arrays.asList;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentConfigsProvider;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Provider;
import java.util.Map;

public class TravelerRootAgent implements Provider<LlmAgent> {

  private static final String TRA_KEY = "traveler.root.agent";
  private static final String TIA_KEY = "traveler.itinerary.agent";

  public TravelerRootAgent(AgentConfigsProvider provider) {
    checkNotNull(provider);
    this.configs = provider.get();
  }

  public TravelerRootAgent(Map<String, AgentConfig> configs) {
    this.configs = copyOf(configs);
  }

  private final ImmutableMap<String, AgentConfig> configs;

  @Override
  public LlmAgent get() {
    SequentialAgent subAgent = tripAssistant(configs);
    AgentConfig rootConfig = configs.get(TRA_KEY);
    AgentContext rootCtx =
        AgentContext.builder() //
            .withConfig(rootConfig) //
            .withSubAgents(asList(subAgent)) //
            .build(); //
    return new LlmAgentProvider(rootCtx).get();
  }

  private SequentialAgent tripAssistant(Map<String, AgentConfig> configs) {
    ParallelAgent team = new TripSearchTeam(configs).get();
    LlmAgent itinerary = new LlmAgentProvider(configs.get(TIA_KEY)).get();
    return SequentialAgent.builder() //
        .name("TripAssistantWorkflow") //
        .description("Travel Planning Agent with parallel search") //
        .subAgents(team, itinerary) //
        .build();
  }
}
