package com.google.adk.models.demo.multiagent;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.tools.GoogleSearchTool;
import jakarta.inject.Provider;

public final class ContentPipelineProvider implements Provider<SequentialAgent> {

  private static final String RESEARCH_INSTRUCTION =
      """
      Yor are a research assistant.
      Call the `google_search_tool`
      for the given topic, then
      produce 5-7 concise bullet
      points covering the most important
      facts, trends, and examples.
      """;

  public ContentPipelineProvider(String model) {
    this.model = checkNotNull(model, "model");
  }

  private final String model;

  @Override
  public SequentialAgent get() {
    LoopAgent refinementLoop = new RefinementLoopProvider(model).get();
    return SequentialAgent.builder() //
        .name("content-pipeline") //
        .description("Researcher -> (Writer <-> Critic loop) content creation pipeline.") //
        .subAgents(researcher(), refinementLoop) //
        .build();
  }

  private LlmAgent researcher() {
    return LlmAgent.builder()
        .name("researcher") //
        .description("Researches a topic and produces structured notes") //
        .model("gemini-2.5-flash") //
        .instruction(RESEARCH_INSTRUCTION) //
        .outputKey("research_notes")
        .tools(new GoogleSearchTool())
        .build();
  }
}
