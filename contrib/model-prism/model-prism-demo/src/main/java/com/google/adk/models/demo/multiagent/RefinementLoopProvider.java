package com.google.adk.models.demo.multiagent;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.LoopAgent;
import com.google.adk.agents.ReadonlyContext;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import jakarta.inject.Provider;

public final class RefinementLoopProvider implements Provider<LoopAgent> {

  private static final String CRITIQUE_INSTRUCTION =
      """
      Yor are an editor. Review the article
      draft below and provide 2-3 specific,
      actionable improvements (clarity, structure, word choice).
      Be concise - bullet points only.\n\n
      Draft:\n + %s.
      """;

  private static final String WRITER_INSTRUCTION =
      """
      Yor are a writer. Based on the research
      notes below, write a polished 3-paragraph
      article for a general audience. Stick to
      the provided facts only.\n\n
      Research notes:\n %s.
      """;

  private static final String WRITER_INSTRUCTION_CRITIQUE =
      """
      You are a writer revising your article.
      Apply the critic's feedback and produce
      an improved version.\n\n
      Research notes:\n %s \n\n
      Draft article:\n %s \n\n
      Critic feedback:\n %s
      """;

  public RefinementLoopProvider(String model) {
    this.model = checkNotNull(model, "model");
  }

  private final String model;

  @Override
  public LoopAgent get() {
    return LoopAgent.builder() //
        .name("refinement-loop") //
        .description("Writer and critic iterate to refine the article") //
        .subAgents(writer(), critic()) //
        .maxIterations(2) //
        .afterAgentCallback(publishFinalFromState("draft_article"))
        .build();
  }

  private Callbacks.AfterAgentCallback publishFinalFromState(String srcStateKey) {
    return callbackCtx -> {
      Object stateVal = callbackCtx.invocationContext().session().state().get(srcStateKey);
      if (stateVal instanceof String refinedArticle) {
        String trimmed = refinedArticle.trim();
        if (!trimmed.isEmpty()) {
          callbackCtx.invocationContext().session().state().put("final_article", trimmed);
        }
      }
      return Maybe.empty();
    };
  }

  private LlmAgent writer() {
    return LlmAgent.builder()
        .name("writer") //
        .description("Writes or revises a short article") //
        .model(model) //
        .instruction(new Instruction.Provider(ctx -> writerFunction(ctx))) //
        .outputKey("draft_article") //
        .build();
  }

  private Single<String> writerFunction(ReadonlyContext ctx) {
    String research = (String) ctx.state().getOrDefault("research_notes", "");
    String critique = (String) ctx.state().getOrDefault("critique_feedback", "");
    String draft = (String) ctx.state().getOrDefault("draft_article", "");
    if (isBlank(critique)) {
      return Single.just(WRITER_INSTRUCTION.formatted(research));
    } else {
      return Single.just(WRITER_INSTRUCTION_CRITIQUE.formatted(research, draft, critique));
    }
  }

  private LlmAgent critic() {
    return LlmAgent.builder()
        .name("critic") //
        .description("Reviews the draft and provides actionable feedback") //
        .model(model) //
        .instruction(new Instruction.Provider(ctx -> critiqueFunction(ctx))) //
        .outputKey("critique_feedback") //
        .build();
  }

  private Single<String> critiqueFunction(ReadonlyContext ctx) {
    String draft = (String) ctx.state().getOrDefault("draft_article", "");
    return Single.just(CRITIQUE_INSTRUCTION.formatted(draft));
  }
}
