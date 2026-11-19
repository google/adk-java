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
import com.google.adk.workflow.FunctionNodes;
import com.google.adk.workflow.Workflow;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Base64;

/**
 * A port of adk-python's {@code message} workflow sample: nodes send the user a text message, a
 * message with an inline image, several messages in a row, and a sentence streamed in chunks.
 */
public final class MessageWorkflow {

  private static final String USER_ID = "user";

  /** A 16x16 solid red PNG. */
  private static final String RED_SQUARE_PNG =
      "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAXElEQVR4nO2TSQ7AIAwD"
          + "7fz/z+ZQtapwmrJc8QklmjBIgZJgIZMiAIl9KYbhjx4fgwosbNxgMrF0+4uhgHnYDM6"
          + "AzQHJeg5HYtyHFfgy2AztN/5tZWfrBtVzkl4DzfQkEPd+cEkAAAAASUVORK5CYII=";

  private static final String SENTENCE =
      """
      This is a streaming message sent in chunks.

      You can stream in markdown as well. For example, the table below:

      | Header 1 | Header 2 |
      |----------|----------|
      | Cell 1   | Cell 2   |
      | Cell 3   | Cell 4   |\
      """;

  /** Builds the workflow. It calls no model. */
  public static Workflow create() {
    return Workflow.builder()
        .name("message")
        .edges(g ->
            g.fromStart()
                .chain(
                    FunctionNodes.of(
                        "send_string", Void.class, Event.class, MessageWorkflow::sendString),
                    FunctionNodes.of(
                        "send_multimodal",
                        Void.class,
                        Event.class,
                        MessageWorkflow::sendMultimodal),
                    FunctionNodes.of(
                        "multiple_messages",
                        Void.class,
                        Event.class,
                        MessageWorkflow::multipleMessages),
                    FunctionNodes.of(
                        "stream_sentence",
                        Void.class,
                        Event.class,
                        MessageWorkflow::streamSentence)))
        .build();
  }

  /** Runs the workflow once and prints each complete message. */
  public static void main(String[] args) {
    App app = App.builder().name("message").rootNode(create()).build();
    Runner runner = Runner.builder().app(app).build();
    String sessionId =
        runner.sessionService().createSession(app.name(), USER_ID).blockingGet().id();
    Content message = Content.fromParts(Part.fromText("go"));
    runner
        .runAsync(USER_ID, sessionId, message)
        .blockingForEach(
            event -> {
              String text = event.stringifyContent();
              if (!event.partial().orElse(false) && !text.isBlank()) {
                System.out.println(text);
              }
            });
  }

  /** Sends one message as an event; any other returned value would be the node's output instead. */
  private static Event sendString(Context context, Void unused) {
    return message(context, "#1 This is a simple string message.");
  }

  /** Sends one message that carries an inline image. */
  private static Event sendMultimodal(Context context, Void unused) {
    Part image = Part.fromBytes(Base64.getDecoder().decode(RED_SQUARE_PNG), "image/png");
    Content content =
        Content.builder()
            .role("model")
            .parts(
                Part.fromText(
                    "#2 Here is a multi-modal message with an inline image (red square):"),
                image)
            .build();
    return Event.builder().id(context.invocationContext().newUuid()).content(content).build();
  }

  /** Sends complete messages one after another from the same node. */
  private static Event multipleMessages(Context context, Void unused) throws InterruptedException {
    for (String text :
        ImmutableList.of("#3 Multiple messages", "Processing step 1...", "Processing step 2...")) {
      context.emit(message(context, text));
      Thread.sleep(1000);
    }
    return message(context, "Done processing.");
  }

  /**
   * Streams a sentence in partial chunks, which clients display but the session does not store, so
   * the node then sends the whole sentence once as a complete message.
   */
  private static Event streamSentence(Context context, Void unused) throws InterruptedException {
    context.emit(message(context, "#4 Starting to stream..."));
    for (String chunk : Splitter.fixedLength(5).split(SENTENCE)) {
      context.emit(message(context, chunk).toBuilder().partial(true).build());
      Thread.sleep(200);
    }
    return message(context, SENTENCE);
  }

  /** A user-facing message from a node, with {@code text} as its content. */
  private static Event message(Context context, String text) {
    return Event.builder()
        .id(context.invocationContext().newUuid())
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .build();
  }

  private MessageWorkflow() {}
}
