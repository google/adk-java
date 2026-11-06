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
import com.google.adk.workflow.Node;
import com.google.adk.workflow.NodeInfo;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code state} workflow sample: nodes write session state through the
 * context and through an event, and read it back through the context. A {@link Node} implementation
 * gets only the context and its input, so {@code read_state_via_param} reads its key through the
 * context too.
 */
public final class StateWorkflow {

  private static final String USER_ID = "user";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("state_sample")
        .edges(
            g ->
                g.fromStart()
                    .chain(
                        new ProcessInitialInput(),
                        new UpdateStateViaEvent(),
                        new ReadStateViaContext(),
                        new ReadStateViaParam()))
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
  private static final class ProcessInitialInput implements Node {
    @Override
    public String name() {
      return "process_initial_input";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands the first node the user's message as Content.
      String text = ((Content) nodeInput).text();
      context.state().put("original_text", text);
      return Flowable.just(text);
    }
  }

  /** Stores the uppercased text by emitting an event whose state delta the session applies. */
  private static final class UpdateStateViaEvent implements Node {
    @Override
    public String name() {
      return "update_state_via_event";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      String uppercased = ((String) nodeInput).toUpperCase(Locale.ROOT);
      return Flowable.just(
          Event.builder()
              .id(context.invocationContext().newUuid())
              .actions(
                  EventActions.builder()
                      .stateDelta(ImmutableMap.of("uppercased_text", uppercased))
                      .build())
              .build());
    }
  }

  /** Reads both earlier values from state, then stores and outputs a sentence combining them. */
  private static final class ReadStateViaContext implements Node {
    @Override
    public String name() {
      return "read_state_via_ctx";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      String result =
          context.state().get("uppercased_text")
              + " (Original was: "
              + context.state().get("original_text")
              + ")";
      context.state().put("appended_text", result);
      return Flowable.just(result);
    }
  }

  /** Outputs the final result built from the sentence the previous node stored. */
  private static final class ReadStateViaParam implements Node {
    @Override
    public String name() {
      return "read_state_via_param";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just("Final Result: " + context.state().get("appended_text") + "!");
    }
  }

  private StateWorkflow() {}
}
