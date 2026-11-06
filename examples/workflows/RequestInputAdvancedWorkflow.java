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
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code request_input_advanced} workflow sample: an agent extracts a
 * time-off request from the user's message, a node approves one day on its own and otherwise asks a
 * manager, sending the request as the payload and asking for an answer that matches a schema, and
 * the last node reports the decision.
 */
public final class RequestInputAdvancedWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";
  private static final String REQUEST_KEY = "request";
  private static final String MANAGER_APPROVAL_ID = "manager_approval";

  /** Builds the workflow, with the agent on {@code model}. */
  public static Workflow create(String model) {
    return Workflow.builder()
        .name("request_input_advanced")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        processRequestAgent(model), new EvaluateRequest(), new ProcessDecision()))
        .build();
  }

  /**
   * Runs the workflow on a one-day request, then on a five-day request that a manager approves for
   * four days, and prints each decision. It calls Gemini, so it needs the credentials the README
   * describes; the first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("request_input_advanced").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    // A request of one day is approved without asking anyone.
    String sickDay = runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    send(
        runner,
        sickDay,
        Content.fromParts(
            Part.fromText("I'm feeling under the weather and need to take today off.")));
    // A longer request waits for the manager, whose answer must match the response schema.
    String vacation = runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    send(
        runner,
        vacation,
        Content.fromParts(
            Part.fromText("Taking my family to Disney World, I'll be out for 5 days next week.")));
    send(
        runner,
        vacation,
        managerAnswer(MANAGER_APPROVAL_ID, ImmutableMap.of("approved", true, "approved_days", 4)));
  }

  /** Sends {@code message} in a session and prints the messages and questions it leads to. */
  private static void send(Runner runner, String sessionId, Content message) {
    runner
        .runAsync(USER_ID, sessionId, message)
        .blockingForEach(
            event -> {
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
                  Map<String, Object> request = call.args().orElseThrow();
                  System.out.println(
                      "["
                          + event.author()
                          + "] asks: "
                          + request.get("message")
                          + " "
                          + request.get("payload"));
                }
              }
            });
  }

  /** The manager's {@code decision} on the request {@code interruptId}. */
  private static Content managerAnswer(String interruptId, Map<String, Object> decision) {
    FunctionResponse response =
        FunctionResponse.builder()
            .id(interruptId)
            .name(RequestInput.FUNCTION_CALL_NAME)
            .response(ImmutableMap.of("result", decision))
            .build();
    return Content.fromParts(Part.builder().functionResponse(response).build());
  }

  /** The decision, with the fields of adk-python's {@code TimeOffDecision}. */
  private static final Schema TIME_OFF_DECISION_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .description("The structured response we expect back from the human manager.")
          .properties(
              ImmutableMap.of(
                  "approved",
                  Schema.builder()
                      .type(Type.Known.BOOLEAN)
                      .description("Whether the time off is approved.")
                      .build(),
                  "approved_days",
                  Schema.builder()
                      .type(Type.Known.INTEGER)
                      .nullable(true)
                      .description("Number of days approved.")
                      .build()))
          .required("approved")
          .build();

  /**
   * Approves a request of at most one day, and asks a manager to decide on anything longer. As in
   * adk-python's sample, it reads the request from the state key that the agent's output key sets.
   */
  private static final class EvaluateRequest implements Node {
    @Override
    public String name() {
      return "evaluate_request";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Map<?, ?> request = (Map<?, ?>) context.state().get(REQUEST_KEY);
      if (((Number) request.get("days")).intValue() <= 1) {
        return Flowable.just(ImmutableMap.of("approved", true));
      }
      // The manager's answer, checked against the schema, becomes this node's output.
      return Flowable.just(
          RequestInput.builder()
              .interruptId(MANAGER_APPROVAL_ID)
              .message("Please review this time off request.")
              .payload(request)
              .responseSchema(TIME_OFF_DECISION_SCHEMA)
              .build());
    }
  }

  /** Reports the decision, reading the requested day count from the request in state. */
  private static final class ProcessDecision implements Node {
    @Override
    public String name() {
      return "process_decision";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Map<?, ?> decision = (Map<?, ?>) nodeInput;
      int requested =
          ((Number) ((Map<?, ?>) context.state().get(REQUEST_KEY)).get("days")).intValue();
      String message;
      if (Boolean.TRUE.equals(decision.get("approved"))) {
        Object approvedDays = decision.get("approved_days");
        int approved = approvedDays == null ? requested : ((Number) approvedDays).intValue();
        message = "Time Off Approved! " + approved + " out of " + requested + " days granted.";
      } else {
        message = "Time Off Denied.";
      }
      return Flowable.just(
          Event.builder()
              .id(context.invocationContext().newUuid())
              .content(Content.builder().role("model").parts(Part.fromText(message)).build())
              .build());
    }
  }

  /** The agent's structured request, with the fields of adk-python's {@code TimeOffRequest}. */
  private static final Schema TIME_OFF_REQUEST_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "days",
                  Schema.builder()
                      .type(Type.Known.INTEGER)
                      .description("Number of days requested.")
                      .build(),
                  "reason",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("Reason for the time off.")
                      .build()))
          .required("days", "reason")
          .build();

  private static LlmAgent processRequestAgent(String model) {
    return LlmAgent.builder()
        .name("process_request")
        .model(model)
        .instruction(
            "Extract the number of days and the reason from the user's natural language time off"
                + " request.")
        .outputSchema(TIME_OFF_REQUEST_SCHEMA)
        .outputKey(REQUEST_KEY)
        .build();
  }

  private RequestInputAdvancedWorkflow() {}
}
