package com.google.adk.models.demo.parallel;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.agents.SequentialAgent;
import io.reactivex.rxjava3.core.Single;
import jakarta.inject.Provider;

public class ReseachPipelineProvider implements Provider<SequentialAgent> {

  private static final String INSTRUCTION =
      """
      You are a senior analyst. Using only the expert notes below,
      write a concise, well-structured 3-paragraph briefing.
      Do not add information not present in the notes.
      ## Historical context\n %s \n\n
      ## Scientific principles\n %s \n\n
      ## Economic outlook\n %s.
      """;

  public ReseachPipelineProvider(String model) {
    this.model = checkNotNull(model, "model");
  }

  private final String model;

  @Override
  public SequentialAgent get() {
    ParallelAgent researchPanel = new ResearchPanelProvider(model).get();
    return SequentialAgent.builder() //
        .name("pipeline") //
        .description("Parallel research + synthesis") //
        .subAgents(researchPanel, synthesizer())
        .build();
  }

  private LlmAgent synthesizer() {
    return LlmAgent.builder()
        .name("synthesizer") //
        .description("Combines expert notes into a final report") //
        .model(model) //
        .instruction(new Instruction.Provider(ctx -> function(ctx)))
        .build();
  }

  private Single<String> function(ReadonlyContext ctx) {
    String history = (String) ctx.state().getOrDefault("history_notes", "");
    String science = (String) ctx.state().getOrDefault("science_notes", "");
    String economics = (String) ctx.state().getOrDefault("economics_notes", "");
    return Single.just(INSTRUCTION.formatted(history, science, economics));
  }
}
