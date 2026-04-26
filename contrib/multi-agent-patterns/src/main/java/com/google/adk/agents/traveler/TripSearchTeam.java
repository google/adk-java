package com.google.adk.agents.traveler;

import static com.google.common.collect.ImmutableMap.copyOf;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.agents.base.tools.MapsAgentToolProvider;
import com.google.adk.agents.base.tools.SearchAgentToolProvider;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableMap;
import jakarta.inject.Provider;
import java.util.Map;

public class TripSearchTeam implements Provider<ParallelAgent> {

  private static final String DESC =
      """
      The ParallelSearchTeam agent searches flights,
      accommodations, dining and activity options concurrently.
      """;

  private static final String TFA_KEY = "traveler.flight.agent";
  private static final String TAA_KEY = "traveler.accommodation.agent";
  private static final String TEA_KEY = "traveler.activity.agent";
  private static final String TDA_KEY = "traveler.dining.agent";

  public TripSearchTeam(Map<String, AgentConfig> configs) {
    this.configs = copyOf(configs);
  }

  private final ImmutableMap<String, AgentConfig> configs;

  @Override
  public ParallelAgent get() {
    AgentTool maps = new MapsAgentToolProvider(configs).get();
    AgentTool search = new SearchAgentToolProvider(configs).get();
    LlmAgent flights = llmAgent(TFA_KEY, search);
    SequentialAgent experiences = experiences();
    LlmAgent accommodation = llmAgent(TAA_KEY, maps);
    return ParallelAgent.builder() //
        .name("ParallelSearchTeam") //
        .description(DESC) //
        .subAgents(flights, experiences, accommodation) //
        .build();
  }

  private SequentialAgent experiences() {
    AgentTool maps = new MapsAgentToolProvider(configs).get();
    AgentTool search = new SearchAgentToolProvider(configs).get();
    LlmAgent activities = llmAgent(TEA_KEY, search);
    LlmAgent dining = llmAgent(TDA_KEY, maps);
    return SequentialAgent.builder() //
        .name("Activities and Dining") //
        .subAgents(activities, dining) //
        .build();
  }

  private LlmAgent llmAgent(String key, BaseTool... tools) {
    AgentConfig config = configs.get(key);
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(config) //
            .withTools(tools) //
            .build(); //
    return new LlmAgentProvider(ctx).get();
  }
}
