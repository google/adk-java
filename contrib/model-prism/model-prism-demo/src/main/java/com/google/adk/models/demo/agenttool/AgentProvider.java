package com.google.adk.models.demo.agenttool;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmRegistry;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.GoogleSearchAgentTool;
import jakarta.inject.Provider;

public final class AgentProvider implements Provider<LlmAgent> {

  private static final String RESEARCH_INSTRUCTION =
      """
        You are a research analyst. When you need up-to-date
        information, call the google_search_agent tool with
        a precise search query. Synthesize the results into a
        concise, well-structured answer.
      """;

  public AgentProvider(String model) {
    this.model = checkNotNull(model, "model");
  }

  private final String model;

  @Override
  public LlmAgent get() {
    return LlmAgent.builder() //
        .name("research-analyst")
        .model(model) //
        .description("A research analyst that can search the web via a search sub-agent") //
        .instruction(RESEARCH_INSTRUCTION) //
        .tools(googleSearchTool()) //
        .build();
  }

  private AgentTool googleSearchTool() {
    BaseLlm llm = LlmRegistry.getLlm("gemini-2.5-flash");
    return GoogleSearchAgentTool.create(llm);
  }
}
