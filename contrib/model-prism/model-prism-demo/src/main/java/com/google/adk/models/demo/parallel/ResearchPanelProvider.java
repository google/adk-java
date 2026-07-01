package com.google.adk.models.demo.parallel;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.tools.GoogleSearchTool;
import jakarta.inject.Provider;

public final class ResearchPanelProvider implements Provider<ParallelAgent> {

  public ResearchPanelProvider(String model) {
    this.model = checkNotNull(model, "model");
  }

  private final String model;

  @Override
  public ParallelAgent get() {
    return ParallelAgent.builder() //
        .name("research-panel") //
        .description("Parallel research: historian + scientist + economist") //
        .subAgents(historian(), scientist(), economist()) //
        .build();
  }

  private LlmAgent historian() {
    return LlmAgent.builder()
        .name("historian") //
        .description("Historical context") //
        .model(model)
        .instruction(
            "You are a historian. In 4-5 sentences give the historical context and the key milestones for the topic provided.") //
        .outputKey("history_notes")
        .build();
  }

  private LlmAgent scientist() {
    return LlmAgent.builder()
        .name("scientist") //
        .description("Scientific principles") //
        .model("gemini-2.5-flash")
        .instruction(
            "You are a scientiest. In 4-5 sentences explain the key scientific principles and current research on the topic.") //
        .outputKey("science_notes")
        .tools(new GoogleSearchTool())
        .build();
  }

  private LlmAgent economist() {
    return LlmAgent.builder()
        .name("economist") //
        .description("Economic impact") //
        .model(model)
        .instruction(
            "You are an economist. In 4-5 sentences describe the economic impact and near-term market outlook for the topic.") //
        .outputKey("economics_notes")
        .build();
  }
}
