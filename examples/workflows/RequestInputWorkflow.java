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

import static java.util.stream.Collectors.joining;

import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.RequestInput;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code request_input} workflow sample: an agent drafts a reply to a
 * customer complaint, a node pauses the workflow to ask a human to review the draft, and the next
 * node receives the answer and sends the draft, drops it, or has the agent revise it with the
 * feedback.
 */
public final class RequestInputWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final String DRAFT_KEY = "draft";

  /** Builds the workflow, with the drafting agent on {@code model}. */
  public static Workflow create(String model) {
    LlmAgent draftEmail = draftEmailAgent(model);
    return Workflow.builder()
        .name("request_input")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        new ProcessInput(),
                        draftEmail,
                        new RequestHumanReview(),
                        new HandleHumanReview())
                    .route(
                        r -> {
                          r.on("revise").then(draftEmail);
                          r.on("approved")
                              .then(
                                  new Reply("send_email", "Draft approved and sent successfully."));
                          r.on("rejected").then(new Reply("reject_email", "Draft rejected."));
                        }))
        .build();
  }

  /**
   * Runs the workflow on one complaint, asks for a revision, and approves the revised draft,
   * printing each draft and question. It calls Gemini, so it needs the credentials the README
   * describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("request_input").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    String interruptId =
        send(
            runner,
            sessionId,
            Content.fromParts(Part.fromText("My phone battery drains too fast")));
    // Each answer resumes the workflow, which asks again after a revision.
    for (String answer :
        ImmutableList.of("Make it shorter and offer a free battery check.", "approve")) {
      if (interruptId == null) {
        break;
      }
      interruptId = send(runner, sessionId, reviewAnswer(interruptId, answer));
    }
  }

  /**
   * Sends {@code message}, prints the messages and the question the workflow sends back, and
   * returns the ID of the review request the turn ends with, or null if it ends without one.
   */
  private static @Nullable String send(Runner runner, String sessionId, Content message) {
    String interruptId = null;
    for (Event event : runner.runAsync(USER_ID, sessionId, message).blockingIterable()) {
      String text =
          event.content().flatMap(Content::parts).stream()
              .flatMap(List::stream)
              .flatMap(part -> part.text().stream())
              .collect(joining());
      if (!text.isBlank()) {
        System.out.println("[" + event.author() + "] " + text);
      }
      for (FunctionCall call : event.functionCalls()) {
        if (call.name().orElse("").equals(RequestInput.FUNCTION_CALL_NAME)) {
          Object question = call.args().orElseThrow().get("message");
          System.out.println("[" + event.author() + "] asks: " + question);
          interruptId = call.id().orElseThrow();
        }
      }
    }
    return interruptId;
  }

  /** The reviewer's {@code answer} to the review request {@code interruptId}. */
  private static Content reviewAnswer(String interruptId, String answer) {
    FunctionResponse response =
        FunctionResponse.builder()
            .id(interruptId)
            .name(RequestInput.FUNCTION_CALL_NAME)
            .response(ImmutableMap.of("result", answer))
            .build();
    return Content.fromParts(Part.builder().functionResponse(response).build());
  }

  /**
   * Stores the complaint in state, where the agent's instruction reads it, with no feedback yet.
   */
  private static final class ProcessInput implements Node {
    @Override
    public String name() {
      return "process_input";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands the first node the user's message as Content.
      context.state().put("complaint", ((Content) nodeInput).text());
      context.state().put("feedback", "");
      return Flowable.empty();
    }
  }

  /**
   * Asks a human to review the draft that the agent stored in state; the answer becomes this node's
   * output.
   */
  private static final class RequestHumanReview implements Node {
    @Override
    public String name() {
      return "request_human_review";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(
          RequestInput.builder()
              .message(
                  "Please review the following draft email and provide 'approve', 'reject', or"
                      + " feedback to revise.\n\n---\n"
                      + context.state().get(DRAFT_KEY)
                      + "\n---")
              .build());
    }
  }

  /** Routes on the reviewer's answer, and stores any feedback for the agent's next draft. */
  private static final class HandleHumanReview implements Node {
    @Override
    public String name() {
      return "handle_human_review";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      String answer = String.valueOf(nodeInput);
      String route =
          switch (answer) {
            case "reject" -> "rejected";
            case "approve" -> "approved";
            default -> "revise";
          };
      if (route.equals("revise")) {
        context.state().put("feedback", answer);
      }
      context.routes(ImmutableList.of(new Route.Tag(route)));
      return Flowable.empty();
    }
  }

  /** A node that tells the user what happened to the draft. */
  private record Reply(String name, String text) implements Node {
    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(
          Event.builder()
              .id(context.invocationContext().newUuid())
              .content(Content.builder().role("model").parts(Part.fromText(text)).build())
              .build());
    }
  }

  private static LlmAgent draftEmailAgent(String model) {
    return LlmAgent.builder()
        .name("draft_email")
        .model(model)
        .instruction(
            """
            Please write a polite, helpful response email to the following customer complaint:
            "{complaint}"

            If there is any feedback from the manager to revise the draft, please incorporate it:
            "{feedback?}"
            """)
        .outputKey(DRAFT_KEY)
        .build();
  }

  private RequestInputWorkflow() {}
}
