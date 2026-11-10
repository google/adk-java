/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.workflows;

import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.runner.Runner;
import com.google.adk.tools.GoogleSearchTool;
import com.google.adk.workflow.JoinNode;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A port of ADK Kotlin's research workflow sample. A planner fans out to two researchers that
 * search the web concurrently, a {@link JoinNode} waits for both before the writer drafts a brief,
 * and a reviewer sends the brief back for another round until it is complete or the round cap is
 * reached.
 */
public final class ResearchWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final String BRIEF_KEY = "brief";
  private static final String REVIEW_KEY = "review";
  private static final String WEB_NOTES_KEY = "web_notes";
  private static final String DOCS_NOTES_KEY = "docs_notes";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(String model) {
    LlmAgent plan = plannerAgent(model);
    return Workflow.builder()
        .name("research")
        .edges(
            g ->
                g.fromStart()
                    .chain(new SaveQuestion(), plan)
                    // Fans out; the writer and reviewer prompts read the notes from state.
                    .then(webResearcherAgent(model), docsResearcherAgent(model))
                    .joinTo(JoinNode.create("gather")) // Waits for both.
                    .chain(
                        writerAgent(model),
                        reviewerAgent(model),
                        new ReviewGate(/* maxRounds= */ 3))
                    .route(
                        r -> {
                          // Loops back while the reviewer asks for more.
                          r.on("needs-more").then(plan);
                          r.on("done").then(new BriefPublisher());
                        }))
        .build();
  }

  /**
   * Runs the workflow on one question and prints each step's text and the published brief. It calls
   * Gemini, so it needs the credentials the README describes; the first argument, if any, overrides
   * the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("research").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content question =
        Content.fromParts(Part.fromText("Should a small team adopt Kotlin Multiplatform?"));
    runner
        .runAsync(USER_ID, sessionId, question)
        .blockingForEach(
            event -> {
              String path = event.nodeInfo().map(NodeInfo::path).orElse(null);
              String text = event.stringifyContent();
              if (!text.isBlank()) {
                System.out.println("[" + path + "] " + text);
              }
              if (Objects.equals(path, "research@1/publisher@1")) {
                event.output().ifPresent(brief -> System.out.println("\nBrief: " + brief));
              }
            });
  }

  /** Stores the user's question in state, where the planner, writer and reviewer read it. */
  private static final class SaveQuestion implements Node {
    @Override
    public String name() {
      return "save_question";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands the first node the user's message as Content.
      context.state().put("question", ((Content) nodeInput).text());
      return Flowable.empty();
    }
  }

  /** Outputs the brief the writer stored, which makes it the workflow's output. */
  private static final class BriefPublisher implements Node {
    @Override
    public String name() {
      return "publisher";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // Consumes the brief, so a later question never publishes this one's answer.
      Object brief = context.state().remove(BRIEF_KEY);
      return brief == null ? Flowable.empty() : Flowable.just(brief);
    }
  }

  /**
   * Routes on the reviewer's verdict, since an {@link LlmAgent} node emits no route. It stops the
   * loop after {@code maxRounds} research rounds even if the reviewer still asks for more.
   */
  private record ReviewGate(int maxRounds) implements Node {
    @Override
    public String name() {
      return "review_gate";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Object verdict =
          context.state().get(REVIEW_KEY) instanceof Map<?, ?> review
              ? review.get("verdict")
              : null;
      // The run id counts this node's runs in this invocation, so it is the round number.
      int round = Integer.parseInt(context.requireNodeState().runId());
      String route =
          Objects.equals(verdict, "needs-more") && round < maxRounds ? "needs-more" : "done";
      // Clears the review and notes when the loop ends, so the next question starts fresh.
      if (route.equals("done")) {
        for (String key : ImmutableList.of(REVIEW_KEY, WEB_NOTES_KEY, DOCS_NOTES_KEY)) {
          context.state().remove(key);
        }
      }
      context.routes(ImmutableList.of(new Route.Tag(route)));
      return Flowable.empty();
    }
  }

  private static LlmAgent plannerAgent(String model) {
    return LlmAgent.builder()
        .name("planner")
        .model(model)
        .instruction(
            """
            Split this research question into two focused sub-questions: the first about
            real-world adoption and experience, the second about what the official documentation
            says.
            Question: {question}
            If this review of an earlier brief asks for more, target the gap it names:
            {review?}
            """)
        .build();
  }

  private static LlmAgent webResearcherAgent(String model) {
    return LlmAgent.builder()
        .name("web_researcher")
        .model(model)
        .instruction(
            "Research the first sub-question with Google Search. Reply with at most three"
                + " bullets, each ending with its source.")
        .tools(GoogleSearchTool.INSTANCE)
        .outputKey(WEB_NOTES_KEY)
        .build();
  }

  private static LlmAgent docsResearcherAgent(String model) {
    return LlmAgent.builder()
        .name("docs_researcher")
        .model(model)
        .instruction(
            "Research the second sub-question with Google Search, preferring official"
                + " documentation. Reply with at most three bullets, each ending with its source.")
        .tools(GoogleSearchTool.INSTANCE)
        .outputKey(DOCS_NOTES_KEY)
        .build();
  }

  private static LlmAgent writerAgent(String model) {
    return LlmAgent.builder()
        .name("writer")
        .model(model)
        .instruction(
            """
            Write a one-paragraph brief answering this question from these notes, and cite the
            sources you use.
            Question: {question}
            Notes: {web_notes?}
            {docs_notes?}
            """)
        .outputKey(BRIEF_KEY)
        .build();
  }

  /** The reviewer's structured verdict, which {@link ReviewGate} routes on. */
  private static final Schema REVIEW_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "verdict",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .enum_("done", "needs-more")
                      .description(
                          "needs-more if the brief leaves part of the question unanswered or makes"
                              + " a claim the notes do not support; otherwise done.")
                      .build(),
                  "feedback",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The gap to research next, or empty if done.")
                      .build()))
          .required("verdict", "feedback")
          .build();

  private static LlmAgent reviewerAgent(String model) {
    return LlmAgent.builder()
        .name("reviewer")
        .model(model)
        .instruction(
            """
            Review this brief against the question and the research notes.
            Question: {question}
            Brief: {brief?}
            Notes: {web_notes?}
            {docs_notes?}
            """)
        .outputSchema(REVIEW_SCHEMA)
        .outputKey(REVIEW_KEY)
        .build();
  }

  private ResearchWorkflow() {}
}
