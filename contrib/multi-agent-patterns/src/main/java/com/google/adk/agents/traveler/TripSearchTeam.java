package com.google.adk.agents.traveler;

import static com.google.api.client.util.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.agents.base.tools.MapsAgentToolProvider;
import com.google.adk.agents.base.tools.SearchAgentToolProvider;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import jakarta.inject.Provider;

public class TripSearchTeam implements Provider<ParallelAgent> {

  private static final String DESC =
      """
      The ParallelSearchTeam agent searches flights,
      accommodations, dining and activity options concurrently.
      """;

  private static final String TFA_KEY = "traveler.flight";
  private static final String TDA_KEY = "traveler.dining";
  private static final String TEA_KEY = "traveler.activity";
  private static final String TAA_KEY = "traveler.accommodation";

  public TripSearchTeam(AppConfig appConfig) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
  }

  private final AppConfig appConfig;

  @Override
  public ParallelAgent get() {
    AgentTool maps = new MapsAgentToolProvider(appConfig).get();
    AgentTool search = new SearchAgentToolProvider(appConfig).get();
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
    AgentTool maps = new MapsAgentToolProvider(appConfig).get();
    AgentTool search = new SearchAgentToolProvider(appConfig).get();
    LlmAgent activities = llmAgent(TEA_KEY, search);
    LlmAgent dining = llmAgent(TDA_KEY, maps);
    return SequentialAgent.builder() //
        .name("Activities and Dining") //
        .subAgents(activities, dining) //
        .build();
  }

  private LlmAgent llmAgent(String key, BaseTool... tools) {
    AgentConfig config = agentConfig(key);
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(config) //
            .withTools(tools) //
            .withPlugins(appConfig.getPlugins()) //
            .withContentConfig(appConfig.getContentConfig()) //
            .build(); //
    return new LlmAgentProvider(ctx).get();
  }

  private AgentConfig agentConfig(String key) {
    String agentKey = appConfig.getProperties().get(key);
    return appConfig.getAgentConfigs().get(agentKey);
  }
}
