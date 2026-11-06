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
import com.google.adk.workflow.NodeConfig;
import com.google.adk.workflow.RetryConfig;
import com.google.adk.workflow.Workflow;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;

/**
 * A port of adk-python's {@code retry} workflow sample: a weather lookup fails at random, and its
 * retry policy runs it again, up to five attempts in all, before the next node reports the weather.
 * Each failed attempt emits an error event; the run fails if every attempt does.
 */
public final class RetryWorkflow {

  private static final String USER_ID = "user";

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("retry")
        .edges(g -> g.fromStart().chain(new GetWeather(), new ReportWeather()))
        .build();
  }

  /** Runs the workflow once and prints each attempt, each failure, and the report. */
  public static void main(String[] args) {
    App app = App.builder().name("retry").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("go"));
    try {
      runner
          .runAsync(USER_ID, sessionId, message)
          .blockingForEach(
              event -> {
                String text = event.stringifyContent();
                if (!text.isBlank()) {
                  System.out.println(text);
                }
                event
                    .errorCode()
                    .ifPresent(
                        code -> System.out.println(code + ": " + event.errorMessage().orElse("")));
              });
    } catch (HttpException e) {
      // When the last attempt fails too, the run fails with that attempt's error.
      System.out.println("Every attempt failed.");
    }
  }

  /** A mock weather lookup that fails 70% of the time, retried with backoff from one second. */
  private static final class GetWeather implements Node {
    @Override
    public String name() {
      return "get_weather";
    }

    @Override
    public NodeConfig config() {
      return NodeConfig.builder()
          .retryConfig(
              RetryConfig.builder().maxAttempts(5).initialDelay(Duration.ofSeconds(1)).build())
          .build();
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      Event attempt = message(context, "Getting weather... attempt " + context.attemptCount());
      if (ThreadLocalRandom.current().nextDouble() < 0.7) {
        return Flowable.just(attempt)
            .concatWith(Flowable.error(new HttpException(500, "Internal Server Error")));
      }
      return Flowable.just(attempt, "sunny");
    }
  }

  /** Reports the weather the lookup returned. */
  private static final class ReportWeather implements Node {
    @Override
    public String name() {
      return "report_weather";
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(message(context, "The weather is " + nodeInput));
    }
  }

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  /** A mock API's error response. */
  private static final class HttpException extends RuntimeException {
    HttpException(int code, String reason) {
      super("HTTP Error " + code + ": " + reason);
    }
  }

  private RetryWorkflow() {}
}
