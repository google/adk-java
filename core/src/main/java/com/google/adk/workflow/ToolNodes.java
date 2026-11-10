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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.annotations.Experimental;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.plugins.Plugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Static factories for workflow nodes that run a {@link BaseTool}, such as an MCP tool or an agent
 * wrapped as a tool. A node calls its tool between the plugins' tool callbacks and keeps only the
 * tool's state and artifact changes; a tool declared to return nothing gives no output. A tool that
 * asks for confirmation pauses the workflow with a {@link RequestInput}, and once the user answers,
 * the node runs the tool again with the answer as its {@link Context#toolConfirmation()}.
 */
@Experimental
public final class ToolNodes {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  /**
   * Returns a node named after {@code tool}, with the default {@link NodeConfig}. Otherwise it
   * behaves as {@link #asNode(BaseTool, String, NodeConfig)} describes.
   */
  public static Node asNode(BaseTool tool) {
    return asNode(tool, tool.name());
  }

  /**
   * Returns a node named {@code name} that runs {@code tool}, with the default {@link NodeConfig}.
   * Otherwise it behaves as {@link #asNode(BaseTool, String, NodeConfig)} describes.
   */
  public static Node asNode(BaseTool tool, String name) {
    return asNode(tool, name, NodeConfig.builder().build());
  }

  /**
   * Returns a node named {@code name} that runs {@code tool} with {@code config}; session state
   * supplies any required parameter that the input lacks. The input may be a map of arguments; an
   * object the JSON mapper writes as a JSON object, such as a record; a JSON object as text or as a
   * {@link Content}; or null or blank text for none. A tool with one parameter, or one required
   * parameter, takes any other input as that parameter; other tools fail.
   */
  public static Node asNode(BaseTool tool, String name, NodeConfig config) {
    return new ToolNode(tool, name, config);
  }

  private static final class ToolNode extends BaseNode {

    private static final ObjectReader JSON_READER =
        JsonBaseModel.getMapper()
            .readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** The interrupt ID prefix of a run's confirmation request, followed by the node path. */
    private static final String CONFIRMATION_INTERRUPT_PREFIX = "wf_tool_confirmation:";

    private static final String TOOL_NAME_KEY = "tool_name";
    private static final String ARGS_KEY = "args";
    private static final String CONFIRMATION_PAYLOAD_KEY = "confirmation_payload";
    private static final String CONFIRMATION_MESSAGE_FORMAT =
        "Please approve or reject the tool call %s().";

    private static final String ANSWER_CONFIRMED_KEY = "confirmed";
    private static final String ANSWER_HINT_KEY = "hint";
    private static final String ANSWER_PAYLOAD_KEY = "payload";
    private static final ImmutableSet<String> ANSWER_KEYS =
        ImmutableSet.of(ANSWER_CONFIRMED_KEY, ANSWER_HINT_KEY, ANSWER_PAYLOAD_KEY);

    /** The schema of the user's answer to a confirmation request. */
    private static final Schema TOOL_CONFIRMATION_SCHEMA =
        Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                ImmutableMap.of(
                    ANSWER_CONFIRMED_KEY, Schema.builder().type(Type.Known.BOOLEAN).build(),
                    ANSWER_HINT_KEY,
                        Schema.builder().type(Type.Known.STRING).nullable(true).build(),
                    ANSWER_PAYLOAD_KEY, Schema.builder().build()))
            .build();

    private final BaseTool tool;

    ToolNode(BaseTool tool, String name, NodeConfig config) {
      super(
          name,
          nullToEmpty(tool.description()),
          // Reruns on resume so that a call paused for confirmation runs once the user answers.
          /* rerunOnResume= */ true,
          /* waitForOutput= */ false,
          config,
          /* inputSchema= */ null,
          /* outputSchema= */ null,
          /* stateSchema= */ null);
      this.tool = tool;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Single.defer(() -> runTool(context, nodeInput))
          .map(result -> returnedNothing(result) ? eventWithoutOutput(context) : result)
          .toFlowable();
    }

    /** Whether {@code result} is an empty map from a tool declared to return nothing. */
    private boolean returnedNothing(Object result) {
      return result instanceof Map<?, ?> map && map.isEmpty() && declaresNoResult(tool);
    }

    /**
     * Returns an event without output for a tool declared to return nothing, whose result is still
     * an empty map. The event records the run, so a resumed workflow does not run the tool again.
     */
    private static Event eventWithoutOutput(Context context) {
      InvocationContext invocationContext = context.invocationContext();
      return Event.builder()
          .id(invocationContext.newUuid())
          .timestamp(invocationContext.now().toEpochMilli())
          .author("")
          .build();
    }

    /** Returns the tool's result, or the {@link RequestInput} that asks to confirm the call. */
    private Single<Object> runTool(Context context, @Nullable Object nodeInput) {
      Parameters parameters = Parameters.of(tool);
      Map<String, Object> args = argumentsFrom(nodeInput, parameters);
      for (String param : parameters.required()) {
        if (!args.containsKey(param) && context.state().containsKey(param)) {
          args.put(param, context.state().get(param));
        }
      }
      String interruptId = CONFIRMATION_INTERRUPT_PREFIX + context.requireNodeState().nodePath();
      ToolContext toolContext =
          ToolContext.builder(context.invocationContext())
              .functionCallId(context.invocationContext().newUuid())
              .toolConfirmation(confirmationAnswer(context.resumeInputs().get(interruptId)))
              .build();
      return callTool(context.invocationContext().pluginManager(), args, toolContext, interruptId)
          // Kept on failure too, so the recorded deltas match the live session state.
          .doOnEvent((unusedResult, unusedError) -> keepDeltas(context, toolContext));
    }

    /**
     * Calls the tool between the plugins' tool callbacks, as a model's tool call does. A
     * confirmation request from the tool becomes a request for input and skips the after-tool
     * callbacks.
     */
    private Single<Object> callTool(
        Plugin plugins, Map<String, Object> args, ToolContext toolContext, String interruptId) {
      Single<Object> toolCall =
          Single.defer(() -> tool.runAsync(args, toolContext))
              .flatMap(
                  result -> {
                    ToolConfirmation requested = takeRequestedConfirmation(toolContext);
                    return requested != null
                        ? Single.just(confirmationRequest(args, requested, interruptId))
                        : afterToolCallbacks(plugins, args, toolContext, result);
                  },
                  error -> {
                    // The failure is the call's result, so a confirmation it asked for is dropped.
                    takeRequestedConfirmation(toolContext);
                    return plugins
                        .onToolErrorCallback(tool, args, toolContext, error)
                        .switchIfEmpty(Single.error(error))
                        .flatMap(answer -> afterToolCallbacks(plugins, args, toolContext, answer));
                  });
      return plugins
          .beforeToolCallback(tool, args, toolContext)
          .flatMapSingle(answer -> afterToolCallbacks(plugins, args, toolContext, answer))
          .switchIfEmpty(toolCall);
    }

    private Single<Object> afterToolCallbacks(
        Plugin plugins,
        Map<String, Object> args,
        ToolContext toolContext,
        Map<String, Object> result) {
      return plugins
          .afterToolCallback(tool, args, toolContext, result)
          .defaultIfEmpty(result)
          .cast(Object.class);
    }

    /**
     * Returns the user's answer to this run's confirmation request, or null if there is none yet.
     * An answer that leaves out {@code confirmed} rejects the call.
     *
     * @throws WorkflowDataException if the answer is not a {@link ToolConfirmation}
     */
    private @Nullable ToolConfirmation confirmationAnswer(@Nullable Object answer) {
      if (answer == null) {
        return null;
      }
      if (!(answer instanceof Map<?, ?> map)
          || !ANSWER_KEYS.containsAll(map.keySet())
          || (map.get(ANSWER_CONFIRMED_KEY) != null
              && !(map.get(ANSWER_CONFIRMED_KEY) instanceof Boolean))
          || (map.get(ANSWER_HINT_KEY) != null && !(map.get(ANSWER_HINT_KEY) instanceof String))) {
        throw new WorkflowDataException(
            String.format(
                "The answer to the confirmation request of tool node '%s' is not a"
                    + " ToolConfirmation.",
                name()));
      }
      return ToolConfirmation.builder()
          .confirmed(Boolean.TRUE.equals(map.get(ANSWER_CONFIRMED_KEY)))
          .hint((String) map.get(ANSWER_HINT_KEY))
          .payload(map.get(ANSWER_PAYLOAD_KEY))
          .build();
    }

    /** Removes and returns the confirmation the call asked for, or null if it asked for none. */
    @CanIgnoreReturnValue
    private static @Nullable ToolConfirmation takeRequestedConfirmation(ToolContext toolContext) {
      return toolContext
          .eventActions()
          .requestedToolConfirmations()
          .remove(toolContext.functionCallId().orElseThrow());
    }

    /**
     * Returns the request that asks the user to approve or reject this call, with the hint and
     * payload in {@code requested}.
     */
    private RequestInput confirmationRequest(
        Map<String, Object> args, ToolConfirmation requested, String interruptId) {
      ImmutableMap.Builder<String, Object> payload =
          ImmutableMap.<String, Object>builder()
              .put(TOOL_NAME_KEY, tool.name())
              .put(ARGS_KEY, args);
      if (requested.payload() != null) {
        payload.put(CONFIRMATION_PAYLOAD_KEY, requested.payload());
      }
      return RequestInput.builder()
          .interruptId(interruptId)
          .message(
              isNullOrEmpty(requested.hint())
                  ? String.format(CONFIRMATION_MESSAGE_FORMAT, tool.name())
                  : requested.hint())
          .payload(payload.buildOrThrow())
          .responseSchema(TOOL_CONFIRMATION_SCHEMA)
          .build();
    }

    private Map<String, Object> argumentsFrom(@Nullable Object nodeInput, Parameters parameters) {
      Object input = nodeInput instanceof Content content ? content.text() : nodeInput;
      if (input instanceof String text) {
        input = parseText(text.strip());
      }
      Map<String, Object> args = new LinkedHashMap<>();
      if (input == null) {
        return args;
      }
      if (input instanceof Map<?, ?> map) {
        map.forEach((key, value) -> args.put(String.valueOf(key), value));
        return args;
      }
      Map<String, Object> properties = propertiesOf(input);
      if (properties != null) {
        return properties;
      }
      String param = parameters.forOtherInput();
      if (param == null) {
        throw new IllegalArgumentException(
            String.format(
                "The input to tool node '%s' must be a dictionary of tool arguments, an object that"
                    + " maps to one, a JSON object as text, or null, but got %s.",
                name(),
                input instanceof String ? "text that is not a JSON object" : typeName(input)));
      }
      args.put(param, input);
      return args;
    }

    /**
     * Returns the properties of an object that the JSON mapper writes as a JSON object, such as a
     * record, as Python ADK reads a Pydantic model. Returns null for any other value.
     */
    private static @Nullable Map<String, Object> propertiesOf(Object input) {
      JsonNode tree;
      try {
        tree = JsonBaseModel.getMapper().valueToTree(input);
      } catch (IllegalArgumentException e) {
        return null;
      }
      return tree.isObject() ? JsonBaseModel.getMapper().convertValue(tree, MAP_TYPE) : null;
    }

    private static String typeName(Object value) {
      String name = value.getClass().getSimpleName();
      return name.isEmpty() ? value.getClass().getName() : name;
    }

    private static boolean declaresNoResult(BaseTool tool) {
      return tool.declaration()
          .flatMap(FunctionDeclaration::response)
          .flatMap(Schema::type)
          .map(type -> type.knownEnum() == Type.Known.NULL)
          .orElse(false);
    }

    private static @Nullable Object parseText(String text) {
      if (text.isEmpty()) {
        return null;
      }
      try {
        Object json = JSON_READER.readValue(text);
        return json instanceof Map ? json : text;
      } catch (JsonProcessingException e) {
        return text;
      }
    }

    private static void keepDeltas(Context context, ToolContext toolContext) {
      EventActions toolActions = toolContext.eventActions();
      context.eventActions().stateDelta().putAll(toolActions.stateDelta());
      context.eventActions().artifactDelta().putAll(toolActions.artifactDelta());
    }
  }

  /**
   * The declared parameters that a node fills itself.
   *
   * @param required the parameters read from state when the input lacks them
   * @param forOtherInput the parameter that any other input fills, as in Python ADK: the only
   *     parameter, or else the only required one
   */
  private record Parameters(ImmutableList<String> required, @Nullable String forOtherInput) {
    private static final Parameters NONE = new Parameters(ImmutableList.of(), null);

    /** Reads {@code tool}'s raw JSON schema, which MCP tools set, or else its typed schema. */
    static Parameters of(BaseTool tool) {
      FunctionDeclaration declaration = tool.declaration().orElse(null);
      if (declaration == null) {
        return NONE;
      }
      Map<String, Object> rawSchema =
          declaration
              .parametersJsonSchema()
              .map(schema -> JsonBaseModel.getMapper().convertValue(schema, MAP_TYPE))
              .orElse(ImmutableMap.of());
      ImmutableList<String> all;
      ImmutableList<String> required;
      if (!rawSchema.isEmpty()) {
        all =
            rawSchema.get("properties") instanceof Map<?, ?> properties
                ? names(properties.keySet())
                : ImmutableList.of();
        required =
            rawSchema.get("required") instanceof List<?> list ? names(list) : ImmutableList.of();
      } else {
        Schema schema = declaration.parameters().orElse(null);
        if (schema == null) {
          return NONE;
        }
        all =
            schema
                .properties()
                .map(properties -> ImmutableList.copyOf(properties.keySet()))
                .orElse(ImmutableList.of());
        required = schema.required().map(ImmutableList::copyOf).orElse(ImmutableList.of());
      }
      if (all.size() == 1) {
        return new Parameters(required, all.get(0));
      }
      return new Parameters(required, required.size() == 1 ? required.get(0) : null);
    }

    private static ImmutableList<String> names(Iterable<?> values) {
      ImmutableList.Builder<String> names = ImmutableList.builder();
      values.forEach(value -> names.add(String.valueOf(value)));
      return names.build();
    }
  }

  private ToolNodes() {}
}
