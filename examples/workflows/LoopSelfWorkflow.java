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
import com.google.adk.workflow.Route;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code loop_self} workflow sample: the user picks a number from 0 to 10,
 * and a node keeps routing back to itself until it guesses it.
 */
public final class LoopSelfWorkflow {

  private static final String USER_ID = "user";
  private static final String TARGET_NUMBER_KEY = "target_number";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    Node guessNumber = new GuessNumber();
    return Workflow.builder()
        .name("loop_self")
        .edges(
            g ->
                g.fromStart()
                    .chain(new ValidateInput(), guessNumber)
                    .route(r -> r.on("guessed_wrong").then(guessNumber)))
        .build();
  }

  /** Runs the workflow on one number and prints every guess. */
  public static void main(String[] args) {
    App app = App.builder().name("loop_self").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("3"));
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

  /** Stores the user's number in state, and fails the run if it is out of range. */
  private static final class ValidateInput implements Node {
    @Override
    public String name() {
      return "validate_input";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      // START hands the first node the user's message as Content.
      int number = Integer.parseInt(((Content) nodeInput).text().trim());
      if (number < 0 || number > 10) {
        return Flowable.just(message(context, "Please provide a number between 0 and 10."))
            .concatWith(Flowable.error(new IllegalArgumentException("Invalid input.")));
      }
      context.state().put(TARGET_NUMBER_KEY, number);
      return Flowable.empty();
    }
  }

  /** Guesses a number, and routes back to itself when the guess is wrong. */
  private static final class GuessNumber implements Node {
    @Override
    public String name() {
      return "guess_number";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      int guess = ThreadLocalRandom.current().nextInt(0, 11);
      Event guessing = message(context, "Guessing " + guess + "...");
      if (guess == ((Number) context.state().get(TARGET_NUMBER_KEY)).intValue()) {
        return Flowable.just(guessing, message(context, "Correct!"));
      }
      context.routes(ImmutableList.of(new Route.Tag("guessed_wrong")));
      return Flowable.just(guessing);
    }
  }

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private LoopSelfWorkflow() {}
}
