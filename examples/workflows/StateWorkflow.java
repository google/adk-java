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
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Locale;

/**
 * A port of adk-python's {@code state} workflow sample: nodes write session state through the
 * context and through an event, and read it back through the context. A function node's body gets
 * only the context and its input, so {@code read_state_via_param} reads its key through the context
 * too.
 */
public final class StateWorkflow {

  private static final String USER_ID = "user";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("state_sample")
        .edges(g ->
            g.fromStart()
                .chain(
                    FunctionNodes.of(
                        "process_initial_input",
                        String.class,
                        String.class,
                        StateWorkflow::processInitialInput),
                    FunctionNodes.of(
                        "update_state_via_event",
                        String.class,
                        Event.class,
                        StateWorkflow::updateStateViaEvent),
                    FunctionNodes.of(
                        "read_state_via_ctx",
                        Void.class,
                        String.class,
                        StateWorkflow::readStateViaContext),
                    FunctionNodes.of(
                        "read_state_via_param",
                        Void.class,
                        String.class,
                        StateWorkflow::readStateViaParam)))
        .build();
  }

  /** Runs the workflow on one message and prints each node's state changes and output. */
  public static void main(String[] args) {
    App app = App.builder().name("state_sample").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("Hello ADK!"));
    runner
        .runAsync(USER_ID, sessionId, message)
        .blockingForEach(
            event -> {
              String path = event.nodeInfo().map(NodeInfo::path).orElse(null);
              if (!event.actions().stateDelta().isEmpty()) {
                System.out.println("[" + path + "] state: " + event.actions().stateDelta());
              }
              event
                  .output()
                  .ifPresent(output -> System.out.println("[" + path + "] output: " + output));
            });
  }

  /** Stores the user's text in state through the context, and outputs it. */
  private static String processInitialInput(Context context, String text) {
    context.state().put("original_text", text);
    return text;
  }

  /** Stores the uppercased text by returning an event whose state delta the session applies. */
  private static Event updateStateViaEvent(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .actions(
            EventActions.builder()
                .stateDelta(ImmutableMap.of("uppercased_text", text.toUpperCase(Locale.ROOT)))
                .build())
        .build();
  }

  /** Reads both earlier values from state, then stores and outputs a sentence combining them. */
  private static String readStateViaContext(Context context, Void unused) {
    String result =
        context.state().get("uppercased_text")
            + " (Original was: "
            + context.state().get("original_text")
            + ")";
    context.state().put("appended_text", result);
    return result;
  }

  /** Outputs the final result built from the sentence the previous node stored. */
  private static String readStateViaParam(Context context, Void unused) {
    return "Final Result: " + context.state().get("appended_text") + "!";
  }

  private StateWorkflow() {}
}
