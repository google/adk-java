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

package com.google.adk.workflow;

import com.google.adk.agents.Context;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Helpers for the tests that pause a workflow and resume it. */
final class WorkflowResumeTesting {
  // The name of the long-running call that the nodes of these tests wait on.
  static final String LONG_RUNNING_CALL = "approve";

  /** An object schema that requires an integer {@code age}. */
  static final Schema AGE_SCHEMA =
      Schema.builder()
          .type(Type.Known.OBJECT)
          .properties(ImmutableMap.of("age", Schema.builder().type(Type.Known.INTEGER).build()))
          .required("age")
          .build();

  private WorkflowResumeTesting() {}

  static Content answer(String interruptId, Map<String, ?> response) {
    return answer(interruptId, response, LONG_RUNNING_CALL);
  }

  @SuppressWarnings("unchecked") // A function response's map holds JSON values.
  static Content answer(String interruptId, Map<String, ?> response, String name) {
    return Content.builder()
        .role("user")
        .parts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id(interruptId)
                        .name(name)
                        .response((Map<String, Object>) response)
                        .build())
                .build())
        .build();
  }

  static Content callContent(String name, String id) {
    return Content.builder()
        .role("model")
        .parts(
            Part.builder().functionCall(FunctionCall.builder().name(name).id(id).build()).build())
        .build();
  }

  static Content modelText(String text) {
    return Content.builder().role("model").parts(Part.fromText(text)).build();
  }

  static Event.Builder newEvent() {
    return Event.builder().id(Event.generateEventId()).author("");
  }

  /** A node that waits for an output and runs its {@code body}. */
  record WaitingNode(
      String name, BiFunction<Context, @Nullable Object, Flowable<?>> body, boolean rerunOnResume)
      implements Node {

    @Override
    public boolean waitForOutput() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return body.apply(context, nodeInput);
    }
  }

  /** A node whose model-style message is its output, as an agent node's can be. */
  record MessageOutputNode(String name, String text, @Nullable Schema schema) implements Node {

    @Override
    public Optional<Schema> outputSchema() {
      return Optional.ofNullable(schema);
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.just(
          newEvent()
              .content(modelText(text))
              .nodeInfo(NodeInfo.builder().messageAsOutput(true).build())
              .build());
    }
  }
}
