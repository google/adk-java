package com.google.adk.agents.code;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentContext;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.LlmAgentProvider;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ExitLoopTool;
import jakarta.inject.Provider;

public class CodeRefinementLoop implements Provider<LoopAgent> {

  private static final int MAX_ITER = 3;
  private static final String CRA_KEY = "code.review";
  private static final String CFA_KEY = "code.refiner";

  private static final String DESC =
      """
      Improves code performance based on review feedback or signals completion.
      """;

  public CodeRefinementLoop(AppConfig appConfig, AgentTool execTool) {
    this.appConfig = checkNotNull(appConfig, "appConfig");
    this.execTool = checkNotNull(execTool, "execTool");
  }

  private final AppConfig appConfig;
  private final AgentTool execTool;

  @Override
  public LoopAgent get() {
    LlmAgent review = llmAgent(CRA_KEY);
    LlmAgent refiner = llmAgent(CFA_KEY, execTool, ExitLoopTool.INSTANCE);
    return LoopAgent.builder() //
        .name("CodeRefinementLoop") //
        .description(DESC) //
        .subAgents(review, refiner) //
        .maxIterations(MAX_ITER) //
        .build();
  }

  private LlmAgent llmAgent(String key, BaseTool... tools) {
    AgentContext ctx =
        AgentContext.builder() //
            .withConfig(agentConfig(key)) //
            .withTools(tools) //
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
