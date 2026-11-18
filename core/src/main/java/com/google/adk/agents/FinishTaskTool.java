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

package com.google.adk.agents;

import com.google.adk.SchemaUtils;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Ends a {@link AgentMode#TASK} agent's task. A call whose arguments match the agent's output
 * schema succeeds; any other call gets a validation error, so the model can retry.
 */
final class FinishTaskTool extends BaseTool {

  static final String NAME = "finish_task";
  private static final String SUCCESS_RESULT = "Task completed.";

  private static final String RESULT_KEY = "result";
  private static final String DESCRIPTION =
      "Signal that this agent has completed its delegated task. Call this when you have finished"
          + " your delegated task.";
  private static final String RETRY_HINT =
      "You could retry calling this tool, but it is IMPORTANT for you to provide all the"
          + " mandatory parameters with correct types.";
  private static final String INSTRUCTION =
      """
      Do NOT call `finish_task` prematurely. Use your available tools to
      fully complete every aspect of the delegated task first. If the
      task is unclear, ask the user for clarification before proceeding.
      Once the task is fully complete, call `finish_task` by itself with
      no accompanying text output.\
      """;
  private static final Schema DEFAULT_OUTPUT_SCHEMA =
      Schema.builder()
          .type("OBJECT")
          .properties(
              ImmutableMap.of(
                  RESULT_KEY,
                  Schema.builder()
                      .type("STRING")
                      .description("A brief summary of what the agent accomplished.")
                      .build()))
          .required(ImmutableList.of(RESULT_KEY))
          .build();

  /**
   * Returns whether {@code event}'s first {@code finish_task} response is a success, so it pairs
   * with the first {@code finish_task} call, as in Python and Kotlin.
   */
  static boolean isSuccessResponse(Event event) {
    return event.functionResponses().stream()
        .filter(response -> response.name().filter(NAME::equals).isPresent())
        .findFirst()
        .flatMap(FunctionResponse::response)
        .map(map -> Objects.equals(map.get(RESULT_KEY), SUCCESS_RESULT))
        .orElse(false);
  }

  private final Schema outputSchema;
  // Function arguments are an object, so a non-object output travels under this key.
  private final @Nullable String resultKey;

  /** Creates the tool for a task agent without an output schema. */
  FinishTaskTool() {
    this(DESCRIPTION, DEFAULT_OUTPUT_SCHEMA);
  }

  /** Creates the tool for a task agent whose output must match {@code outputSchema}. */
  FinishTaskTool(Schema outputSchema) {
    this(DESCRIPTION + " Pass the required output data in the parameters.", outputSchema);
  }

  private FinishTaskTool(String description, Schema outputSchema) {
    super(NAME, description);
    this.outputSchema = outputSchema;
    boolean isObject =
        outputSchema.type().map(type -> type.knownEnum() == Type.Known.OBJECT).orElse(false);
    this.resultKey = isObject ? null : RESULT_KEY;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    Schema parameters =
        resultKey == null
            ? outputSchema
            : Schema.builder()
                .type("OBJECT")
                .properties(ImmutableMap.of(resultKey, outputSchema))
                .required(ImmutableList.of(resultKey))
                .build();
    return Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(parameters)
            .build());
  }

  @Override
  public Completable processLlmRequest(LlmRequest.Builder builder, ToolContext toolContext) {
    InvocationContext context = toolContext.invocationContext();
    // Only a task node can end a task, so the agent run any other way is not offered the tool.
    if (!LlmAgent.runsAsTask(context.agent(), context)) {
      return Completable.complete();
    }
    return super.processLlmRequest(builder, toolContext)
        .doOnComplete(() -> builder.appendInstructions(ImmutableList.of(INSTRUCTION)));
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    if (resultKey != null
        && args.get(resultKey) == null
        && !outputSchema.nullable().orElse(false)) {
      return Single.just(validationError(NAME + " args does not contain required " + resultKey));
    }
    String label = resultKey == null ? NAME + " arguments" : NAME + " argument '" + resultKey + "'";
    return Single.just(
        SchemaUtils.findMismatch(taskOutput(args), outputSchema)
            .map(reason -> validationError(label + ": " + reason))
            .orElse(ImmutableMap.of(RESULT_KEY, SUCCESS_RESULT)));
  }

  /** Returns the task's output from a call's arguments. */
  @Nullable Object taskOutput(Map<String, Object> args) {
    return resultKey == null ? args : args.get(resultKey);
  }

  private static ImmutableMap<String, Object> validationError(String message) {
    return ImmutableMap.of(
        "error",
        "Invoking `"
            + NAME
            + "()` failed due to validation errors:\n"
            + message
            + "\n"
            + RETRY_HINT);
  }
}
