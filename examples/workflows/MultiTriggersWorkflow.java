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
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Workflow;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code multi_triggers} workflow sample: three nodes transform the user's
 * text concurrently, and each one triggers the same node, which therefore runs three times.
 */
public final class MultiTriggersWorkflow {

  private static final String USER_ID = "user";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    Node makeUppercase = new TextStep("make_uppercase", text -> text.toUpperCase(Locale.ROOT));
    Node countCharacters = new TextStep("count_characters", String::length);
    Node reverseString =
        new TextStep("reverse_string", text -> new StringBuilder(text).reverse().toString());
    Node sendMessage = new SendMessage();
    return Workflow.builder()
        .name("multi_triggers")
        .edges(
            g -> {
              g.fromStart().then(makeUppercase, countCharacters, reverseString);
              // Without a JoinNode, each predecessor triggers its own run of send_message.
              g.from(makeUppercase).then(sendMessage);
              g.from(countCharacters).then(sendMessage);
              g.from(reverseString).then(sendMessage);
            })
        .build();
  }

  /** Runs the workflow on one message and prints the three reports. */
  public static void main(String[] args) {
    App app = App.builder().name("multi_triggers").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("Hello, workflows!"));
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
      // ADK Java applies no workflow input schema, so START hands each branch Content, not text.
      return Flowable.just(transform.apply(((Content) nodeInput).text()));
    }
  }

  /** Reports the output of whichever predecessor triggered this run. */
  private static final class SendMessage implements Node {
    @Override
    public String name() {
      return "send_message";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      String message = "Triggered for input: " + nodeInput;
      return Flowable.just(
          Event.builder()
              .id(context.invocationContext().newUuid())
              .content(Content.builder().role("model").parts(Part.fromText(message)).build())
              .build());
    }
  }

  private MultiTriggersWorkflow() {}
}
