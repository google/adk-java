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
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.Node;
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
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code route} workflow sample: an agent classifies the user's input as a
 * question, a statement, or something else, and a node routes it to the agent or node that handles
 * that category.
 */
public final class RouteWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final String CATEGORY_KEY = "category";

  /** Builds the workflow, with every agent on {@code model}. */
  public static Workflow create(String model) {
    return Workflow.builder()
        .name("route")
        .edges(
            g ->
                g.fromStart()
                    .chain(new ProcessInput(), classifyInputAgent(model), new RouteOnCategory())
                    .route(
                        r -> {
                          r.on("question").then(answerQuestionAgent(model));
                          r.on("statement").then(commentOnStatementAgent(model));
                          r.on("other").then(new HandleOther());
                        }))
        .build();
  }

  /**
   * Runs the workflow on a question, a statement, and a request, each in its own session, and
   * prints each reply. It calls Gemini, so it needs the credentials the README describes; the first
   * argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("route").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    ImmutableList<String> inputs =
        ImmutableList.of(
            "What is the capital of France?",
            "The weather is very nice today.",
            "Translate bonjour to english");
    for (String input : inputs) {
      System.out.println("\n=== " + input);
      String sessionId =
          runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
      runner
          .runAsync(USER_ID, sessionId, Content.fromParts(Part.fromText(input)))
          .blockingForEach(
              event -> {
                String text = event.stringifyContent();
                if (!text.isBlank()) {
                  System.out.println("[" + event.author() + "] " + text);
                }
              });
    }
  }

  /** Stores the user's input in state, where the agents' instructions read it. */
  private static final class ProcessInput implements Node {
    @Override
    public String name() {
      return "process_input";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands the first node the user's message as Content.
      context.state().put("input", ((Content) nodeInput).text());
      return Flowable.empty();
    }
  }

  /** Routes on the category that the classifier stored in state, as adk-python's sample does. */
  private static final class RouteOnCategory implements Node {
    @Override
    public String name() {
      return "route_on_category";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Map<?, ?> answer = (Map<?, ?>) context.state().get(CATEGORY_KEY);
      context.routes(ImmutableList.of(new Route.Tag((String) answer.get("category"))));
      return Flowable.empty();
    }
  }

  private static final class HandleOther implements Node {
    @Override
    public String name() {
      return "handle_other";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      String reply = "Sorry I can only answer questions or comment on statements.";
      return Flowable.just(
          Event.builder()
              .id(context.invocationContext().newUuid())
              .content(Content.builder().role("model").parts(Part.fromText(reply)).build())
              .build());
    }
  }

  /** The classifier's structured answer, with the field of adk-python's {@code InputCategory}. */
  private static final Schema INPUT_CATEGORY_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "category",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .enum_("question", "statement", "other")
                      .build()))
          .required("category")
          .build();

  private static LlmAgent classifyInputAgent(String model) {
    return LlmAgent.builder()
        .name("classify_input")
        .model(model)
        .instruction("Based on this input, decide which category it belongs to: {input}")
        .outputSchema(INPUT_CATEGORY_SCHEMA)
        .outputKey(CATEGORY_KEY)
        .build();
  }

  private static LlmAgent answerQuestionAgent(String model) {
    return LlmAgent.builder()
        .name("answer_question")
        .model(model)
        .instruction("Answer the question: {input}")
        .build();
  }

  private static LlmAgent commentOnStatementAgent(String model) {
    return LlmAgent.builder()
        .name("comment_on_statement")
        .model(model)
        .instruction("Comment on the statement: {input}")
        .build();
  }

  private RouteWorkflow() {}
}
