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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.agents.AgentMode;
import com.google.adk.agents.Context;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.Functions;
import com.google.adk.runner.Runner;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A port of ADK Kotlin's agent-in-workflow sample, which ports adk-python's {@code
 * agent_in_workflow} workflow sample, a medical lab intake. A task agent chats with the patient
 * until it has their name and phone number, and a function node checks them and sends the patient
 * back to the agent when it finds no records. An agent then looks up the patient's orders with a
 * tool that waits for the user to approve the call.
 */
public final class AgentInWorkflow {

  private static final String DEFAULT_MODEL = "gemini-3.8-flash";
  private static final String USER_ID = "user";

  /** Builds the workflow, with both agents on {@code model}. */
  public static Workflow create(String model) {
    LlmAgent intakeAgent = intakeAgent(model);
    Node checkIdentity =
        FunctionNodes.of(
            "check_identity", PatientIdentity.class, Event.class, AgentInWorkflow::checkIdentity);
    return Workflow.builder()
        .name("task_in_workflow")
        .edges(g ->
            g.fromStart()
                .chain(intakeAgent, checkIdentity)
                .route(r -> {
                  r.on("retry").then(intakeAgent);
                  r.otherwise(generateInstructionAgent(model));
                }))
        .build();
  }

  /**
   * Runs the workflow in two sessions and prints each message and tool call: in one, a patient
   * gives an unknown name, then Jane Doe's details, and approves the orders lookup; in the other,
   * Jane Doe declines it. It calls Gemini, so it needs the credentials the README describes; the
   * first argument, if any, overrides the model.
   */
  public static void main(String[] args) {
    String model = args.length > 0 ? args[0] : DEFAULT_MODEL;
    App app = App.builder().name("task_in_workflow").rootNode(create(model)).build();
    Runner runner = Runner.builder().app(app).build();
    // A retried intake agent starts a new conversation, so Jane Doe gives both details at once.
    System.out.println("=== approve");
    converse(
        runner,
        ImmutableList.of("My name is John Doe", "555-1234", "Jane Doe, 555-1234"),
        /* approve= */ true);
    System.out.println("\n=== decline");
    converse(runner, ImmutableList.of("Jane Doe, 555-1234"), /* approve= */ false);
  }

  /**
   * Holds a conversation in a new session, sending {@code replies} in turn. It approves each
   * confirmation request if {@code approve} is true; otherwise it rejects the first one and stops.
   */
  private static void converse(Runner runner, List<String> replies, boolean approve) {
    String sessionId =
        runner.sessionService().createSession(runner.appName(), USER_ID).blockingGet().id();
    Deque<String> pending = new ArrayDeque<>(replies);
    Content message = Content.fromParts(Part.fromText(pending.removeFirst()));
    boolean rejected = false;
    while (message != null) {
      String confirmationId = send(runner, sessionId, message);
      // Stops after one rejection, so a model that keeps retrying the call cannot loop forever.
      if (rejected) {
        return;
      }
      // Answers a pending confirmation request, or else sends the next reply.
      if (confirmationId != null) {
        rejected = !approve;
        message = confirmation(confirmationId, approve);
      } else {
        String reply = pending.poll();
        message = reply == null ? null : Content.fromParts(Part.fromText(reply));
      }
    }
  }

  /**
   * Sends {@code message}, prints the messages and tool calls that follow, and returns the ID of
   * the confirmation request the turn ends with, or null if it ends without one.
   */
  private static @Nullable String send(Runner runner, String sessionId, Content message) {
    String confirmationId = null;
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
        System.out.println(
            "["
                + event.author()
                + "] calls "
                + call.name().orElse("")
                + call.args().orElse(ImmutableMap.of()));
        if (call.name().orElse("").equals(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)) {
          confirmationId = call.id().orElseThrow();
        }
      }
    }
    return confirmationId;
  }

  /** The user's answer to confirmation request {@code confirmationId}. */
  private static Content confirmation(String confirmationId, boolean confirmed) {
    FunctionResponse response =
        FunctionResponse.builder()
            .id(confirmationId)
            .name(Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
            .response(ImmutableMap.of("confirmed", confirmed))
            .build();
    return Content.fromParts(Part.builder().functionResponse(response).build());
  }

  /** The patient's identity, which the intake agent's task outputs. */
  private record PatientIdentity(String name, @JsonProperty("phone_number") String phoneNumber) {}

  /** Mocks a records check, and routes back to the intake agent unless the patient is Jane Doe. */
  private static Event checkIdentity(Context context, PatientIdentity patient) {
    String reply;
    if (patient.name().equalsIgnoreCase("Jane Doe")) {
      // No route matches, so otherwise takes the patient on to their orders.
      reply = "Hello " + patient.name() + "! Let me look up your orders.";
    } else {
      reply = "Could not find matching records for " + patient.name() + ". Let's try again.";
      context.routes(ImmutableList.of(new Route.Tag("retry")));
    }
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(reply)).build())
        .build();
  }

  private static LlmAgent intakeAgent(String model) {
    return LlmAgent.builder()
        .name("intake_agent")
        .model(model)
        // Holds a conversation until its finish_task call matches the output schema.
        .mode(AgentMode.TASK)
        .outputSchema(PATIENT_IDENTITY_SCHEMA)
        .instruction(
            """
            You are a medical lab intake assistant. Your job is to chat with
            the user to get their full name and phone number. Do not make up
            information. Once you have both, finish your task.
            If identity check failed, ask for another name.
            """)
        .build();
  }

  private static LlmAgent generateInstructionAgent(String model) {
    return LlmAgent.builder()
        .name("generate_instruction")
        .model(model)
        .tools(FunctionTool.create(new LabOrders(), "findOrders", /* requireConfirmation= */ true))
        .instruction(
            """
            Use the find_orders tool to get the patient's orders.
            List the orders found, and then generate a concise instruction about how to prepare
            based on those orders.
            """)
        .build();
  }

  /** The schema of the patient's identity, with the fields of adk-python's PatientIdentity. */
  private static final Schema PATIENT_IDENTITY_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(
              ImmutableMap.of(
                  "name",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The patient's full name.")
                      .build(),
                  "phone_number",
                  Schema.builder()
                      .type(Type.Known.STRING)
                      .description("The patient's phone number.")
                      .build()))
          .required("name", "phone_number")
          .build();

  /** A mock lab system whose orders lookup waits for the user to approve each call. */
  public static final class LabOrders {

    /** Finds orders for the patient. */
    @Annotations.Schema(name = "find_orders", description = "Finds orders for the patient.")
    public ImmutableList<String> findOrders() {
      return ImmutableList.of("CBC (Complete Blood Count)", "Lipid Panel");
    }
  }

  private AgentInWorkflow() {}
}
