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
import com.google.adk.plugins.Plugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
 * asks for confirmation does not pause the workflow: the node outputs the tool's placeholder error.
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

    private final BaseTool tool;

    ToolNode(BaseTool tool, String name, NodeConfig config) {
      super(
          name,
          nullToEmpty(tool.description()),
          /* rerunOnResume= */ false,
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
          .<Object>map(
              result ->
                  result.isEmpty() && declaresNoResult(tool) ? eventWithoutOutput(context) : result)
          .toFlowable();
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

    private Single<Map<String, Object>> runTool(Context context, @Nullable Object nodeInput) {
      Parameters parameters = Parameters.of(tool);
      Map<String, Object> args = argumentsFrom(nodeInput, parameters);
      for (String param : parameters.required()) {
        if (!args.containsKey(param) && context.state().containsKey(param)) {
          args.put(param, context.state().get(param));
        }
      }
      ToolContext toolContext =
          ToolContext.builder(context.invocationContext())
              .functionCallId(context.invocationContext().newUuid())
              .build();
      return callTool(context.invocationContext().pluginManager(), args, toolContext)
          // Kept on failure too, so the recorded deltas match the live session state.
          .doOnEvent((unusedResult, unusedError) -> keepDeltas(context, toolContext));
    }

    /** Calls the tool between the plugins' tool callbacks, as a model's tool call does. */
    private Single<Map<String, Object>> callTool(
        Plugin plugins, Map<String, Object> args, ToolContext toolContext) {
      Single<Map<String, Object>> toolCall =
          Single.defer(() -> tool.runAsync(args, toolContext))
              .onErrorResumeNext(
                  error ->
                      plugins
                          .onToolErrorCallback(tool, args, toolContext, error)
                          .switchIfEmpty(Single.error(error)));
      return plugins
          .beforeToolCallback(tool, args, toolContext)
          .switchIfEmpty(toolCall)
          .flatMap(
              result ->
                  plugins
                      .afterToolCallback(tool, args, toolContext, result)
                      .defaultIfEmpty(result));
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
