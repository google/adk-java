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
import com.google.adk.runner.Runner;
import com.google.adk.workflow.JoinNode;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Workflow;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code fan_out_fan_in} workflow sample: three nodes transform the user's
 * text concurrently, and a {@link JoinNode} waits for all of them before one node combines their
 * outputs.
 */
public final class FanOutFanInWorkflow {

  private static final String USER_ID = "user";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("fan_out_fan_in")
        .edges(
            g ->
                g.fromStart()
                    .then(
                        new TextStep("make_uppercase", text -> text.toUpperCase(Locale.ROOT)),
                        new TextStep("count_characters", String::length),
                        new TextStep(
                            "reverse_string", text -> new StringBuilder(text).reverse().toString()))
                    .joinTo(JoinNode.create("join_for_results"))
                    .then(new Aggregate()))
        .build();
  }

  /** Runs the workflow on one message and prints the combined result. */
  public static void main(String[] args) {
    App app = App.builder().name("fan_out_fan_in").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("Hello World"));
    runner
        .runAsync(USER_ID, sessionId, message)
        .blockingForEach(
            event -> {
              String text = event.stringifyContent();
              if (!text.isBlank()) {
                System.out.println(text);
              }
            });
  }

  /** A step that outputs what {@code transform} makes of the text of the user's message. */
  private record TextStep(String name, Function<String, Object> transform) implements Node {
    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands each branch the user's message as Content.
      return Flowable.just(transform.apply(((Content) nodeInput).text()));
    }
  }

  /** Combines the three outputs, which the join passes on keyed by node name. */
  private static final class Aggregate implements Node {
    @Override
    public String name() {
      return "aggregate";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Map<?, ?> results = (Map<?, ?>) nodeInput;
      String message =
          "Uppercase: "
              + results.get("make_uppercase")
              + "\n\nCharacter Count: "
              + results.get("count_characters")
              + "\n\nReversed: "
              + results.get("reverse_string")
              + "\n\n";
      return Flowable.just(
          Event.builder()
              .id(context.invocationContext().newUuid())
              .content(Content.builder().role("model").parts(Part.fromText(message)).build())
              .build());
    }
  }

  private FanOutFanInWorkflow() {}
}
