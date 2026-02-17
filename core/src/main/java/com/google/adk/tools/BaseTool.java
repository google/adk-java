/*
 * Copyright 2025 Google LLC
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

package com.google.adk.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.DoNotCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nonnull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The base class for all ADK tools. */
public abstract class BaseTool {
  private final String name;
  private final String description;
  private final boolean isLongRunning;
  private final HashMap<String, Object> customMetadata;

  protected BaseTool(@Nonnull String name, @Nonnull String description) {
    this(name, description, /* isLongRunning= */ false);
  }

  protected BaseTool(@Nonnull String name, @Nonnull String description, boolean isLongRunning) {
    this.name = name;
    this.description = description;
    this.isLongRunning = isLongRunning;
    customMetadata = new HashMap<>();
  }

  public String name() {
    return name;
  }

  public String description() {
    return description;
  }

  public boolean longRunning() {
    return isLongRunning;
  }

  /** Gets the {@link FunctionDeclaration} representation of this tool. */
  public Optional<FunctionDeclaration> declaration() {
    return Optional.empty();
  }

  /** Returns a read-only view of the tool metadata. */
  public ImmutableMap<String, Object> customMetadata() {
    return ImmutableMap.copyOf(customMetadata);
  }

  /** Sets custom metadata to the tool associated with a key. */
  public void setCustomMetadata(String key, Object value) {
    customMetadata.put(key, value);
  }

  /** Calls a tool. */
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    throw new UnsupportedOperationException("This method is not implemented.");
  }

  /**
   * Processes the outgoing {@link LlmRequest.Builder}.
   *
   * <p>This implementation adds the current tool's {@link #declaration()} to the {@link
   * GenerateContentConfig} within the builder. If a tool with function declarations already exists,
   * the current tool's declaration is merged into it. Otherwise, a new tool definition with the
   * current tool's declaration is created. The current tool itself is also added to the builder's
   * internal list of tools. Override this method for processing the outgoing request.
   */
  @CanIgnoreReturnValue
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    if (declaration().isEmpty()) {
      return Completable.complete();
    }

    llmRequestBuilder.appendTools(ImmutableList.of(this));

    LlmRequest llmRequest = llmRequestBuilder.build();
    ImmutableList<Tool> toolsWithoutFunctionDeclarations =
        findToolsWithoutFunctionDeclarations(llmRequest);
    Tool toolWithFunctionDeclarations = findToolWithFunctionDeclarations(llmRequest);
    // If LlmRequest GenerateContentConfig already has a function calling tool,
    // merge the function declarations.
    // Otherwise, add a new tool definition with function calling declaration..
    if (toolWithFunctionDeclarations == null) {
      toolWithFunctionDeclarations =
          Tool.builder().functionDeclarations(ImmutableList.of(declaration().get())).build();
    } else {
      toolWithFunctionDeclarations =
          toolWithFunctionDeclarations.toBuilder()
              .functionDeclarations(
                  ImmutableList.<FunctionDeclaration>builder()
                      .addAll(
                          toolWithFunctionDeclarations
                              .functionDeclarations()
                              .orElseGet(ImmutableList::of))
                      .add(declaration().get())
                      .build())
              .build();
    }
    ImmutableList<Tool> newTools =
        new ImmutableList.Builder<Tool>()
            .addAll(toolsWithoutFunctionDeclarations)
            .add(toolWithFunctionDeclarations)
            .build();
    // Patch the GenerateContentConfig with the new tool definition.
    GenerateContentConfig generateContentConfig =
        llmRequest
            .config()
            .map(GenerateContentConfig::toBuilder)
            .orElseGet(GenerateContentConfig::builder)
            .tools(newTools)
            .build();
    LiveConnectConfig liveConnectConfig =
        llmRequest.liveConnectConfig().toBuilder().tools(newTools).build();
    llmRequestBuilder.config(generateContentConfig);
    llmRequestBuilder.liveConnectConfig(liveConnectConfig);
    return Completable.complete();
  }

  /**
   * Finds a tool in GenerateContentConfig that has function calling declarations, or returns null
   * otherwise.
   */
  private static @Nullable Tool findToolWithFunctionDeclarations(LlmRequest llmRequest) {
    return llmRequest
        .config()
        .flatMap(config -> config.tools())
        .flatMap(
            tools -> tools.stream().filter(t -> t.functionDeclarations().isPresent()).findFirst())
        .orElse(null);
  }

  /** Finds all tools in GenerateContentConfig that do not have function calling declarations. */
  private static ImmutableList<Tool> findToolsWithoutFunctionDeclarations(LlmRequest llmRequest) {
    return llmRequest
        .config()
        .flatMap(config -> config.tools())
        .map(
            tools ->
                tools.stream()
                    .filter(t -> t.functionDeclarations().isEmpty())
                    .collect(toImmutableList()))
        .orElse(ImmutableList.of());
  }

  /**
   * Creates a tool instance from a config.
   *
   * <p>Subclasses should override and implement this method to do custom initialization from a
   * config.
   *
   * @param config The config for the tool.
   * @param configAbsPath The absolute path to the config file that contains the tool config.
   * @return The tool instance.
   * @throws ConfigurationException if the tool cannot be created from the config.
   */
  @DoNotCall("Always throws com.google.adk.agents.ConfigAgentUtils.ConfigurationException")
  public static BaseTool fromConfig(ToolConfig config, String configAbsPath)
      throws ConfigurationException {
    throw new ConfigurationException(
        "fromConfig not implemented for " + BaseTool.class.getSimpleName());
  }

  /** Configuration class for tool arguments that allows arbitrary key-value pairs. */
  // TODO implement this class
  public static class ToolArgsConfig extends JsonBaseModel {

    private static final Logger log = LoggerFactory.getLogger(ToolArgsConfig.class);

    @JsonIgnore private final Map<String, Object> additionalProperties = new HashMap<>();

    public boolean isEmpty() {
      return additionalProperties.isEmpty();
    }

    public int size() {
      return additionalProperties.size();
    }

    @CanIgnoreReturnValue
    public ToolArgsConfig put(String key, Object value) {
      additionalProperties.put(key, value);
      return this;
    }

    public <T> Optional<T> getOrEmpty(String key, TypeReference<T> typeReference) {
      if (!additionalProperties.containsKey(key)) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            JsonBaseModel.getMapper().convertValue(additionalProperties.get(key), typeReference));
      } catch (IllegalArgumentException e) {
        log.debug("Could not convert key {} into type: {}", key, e);
        return Optional.empty();
      }
    }

    public <T> T getOrDefault(String key, T defaultValue) {
      if (!additionalProperties.containsKey(key)) {
        return defaultValue;
      }
      return JsonBaseModel.getMapper()
          .convertValue(additionalProperties.get(key), new TypeReference<T>() {});
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
      return additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
      additionalProperties.put(key, value);
    }
  }

  /** Configuration class for a tool definition in YAML/JSON. */
  public static class ToolConfig extends JsonBaseModel {
    private String name;
    private ToolArgsConfig args;

    public ToolConfig() {}

    public ToolConfig(String name, ToolArgsConfig args) {
      this.name = name;
      this.args = args;
    }

    public String name() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public ToolArgsConfig args() {
      return args;
    }

    public void setArgs(ToolArgsConfig args) {
      this.args = args;
    }
  }
}
