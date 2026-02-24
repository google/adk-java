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

package com.google.adk.models;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.adk.tools.BaseTool;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Represents a request to be sent to the LLM. */
@AutoValue
@JsonDeserialize(builder = LlmRequest.Builder.class)
public abstract class LlmRequest extends JsonBaseModel {

  /**
   * Returns the name of the LLM model to be used. If not set, the default model of the LLM class
   * will be used.
   *
   * @return An optional string representing the model name.
   */
  @JsonProperty("model")
  public abstract Optional<String> model();

  /**
   * Returns the list of content sent to the LLM.
   *
   * @return A list of {@link Content} objects.
   */
  @JsonProperty("contents")
  public abstract List<Content> contents();

  /**
   * Returns the configuration for content generation.
   *
   * @return An optional {@link GenerateContentConfig} object containing the generation settings.
   */
  @JsonProperty("config")
  public abstract Optional<GenerateContentConfig> config();

  /**
   * Returns the configuration for live connections. Populated using the RunConfig in the
   * InvocationContext.
   *
   * @return An optional {@link LiveConnectConfig} object containing the live connection settings.
   */
  @JsonProperty("liveConnectConfig")
  public abstract LiveConnectConfig liveConnectConfig();

  /**
   * Returns a map of tools available to the LLM.
   *
   * @return A map where keys are tool names and values are {@link BaseTool} instances.
   */
  @JsonIgnore
  public abstract Map<String, BaseTool> tools();

  /** returns the first system instruction text from the request if present. */
  @JsonIgnore
  public Optional<String> getFirstSystemInstruction() {
    return this.config()
        .flatMap(GenerateContentConfig::systemInstruction)
        .flatMap(content -> content.parts().flatMap(partList -> partList.stream().findFirst()))
        .flatMap(Part::text);
  }

  /** Returns all system instruction texts from the request as an immutable list. */
  @JsonIgnore
  public ImmutableList<String> getSystemInstructions() {
    return config()
        .flatMap(GenerateContentConfig::systemInstruction)
        .flatMap(Content::parts)
        .map(
            partList ->
                partList.stream()
                    .map(Part::text)
                    .flatMap(Optional::stream)
                    .collect(toImmutableList()))
        .orElseGet(ImmutableList::of);
  }

  public static Builder builder() {
    return new AutoValue_LlmRequest.Builder()
        .tools(ImmutableMap.of())
        .contents(ImmutableList.of())
        .liveConnectConfig(LiveConnectConfig.builder().build());
  }

  public abstract Builder toBuilder();

  /** Builder for constructing {@link LlmRequest} instances. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    private static Builder create() {
      return builder();
    }

    @CanIgnoreReturnValue
    @JsonProperty("model")
    public abstract Builder model(String model);

    @CanIgnoreReturnValue
    @JsonProperty("contents")
    public abstract Builder contents(List<Content> contents);

    @CanIgnoreReturnValue
    @JsonProperty("config")
    public abstract Builder config(GenerateContentConfig config);

    public abstract Optional<GenerateContentConfig> config();

    @CanIgnoreReturnValue
    @JsonProperty("liveConnectConfig")
    public abstract Builder liveConnectConfig(LiveConnectConfig liveConnectConfig);

    abstract LiveConnectConfig liveConnectConfig();

    @CanIgnoreReturnValue
    abstract Builder tools(Map<String, BaseTool> tools);

    abstract Map<String, BaseTool> tools();

    @CanIgnoreReturnValue
    public final Builder appendInstructions(List<String> instructions) {
      if (instructions.isEmpty()) {
        return this;
      }

      // Update GenerateContentConfig
      GenerateContentConfig cfg = config().orElseGet(() -> GenerateContentConfig.builder().build());
      Content newCfgSi = addInstructions(cfg.systemInstruction(), instructions);
      config(cfg.toBuilder().systemInstruction(newCfgSi).build());

      // Update LiveConnectConfig
      LiveConnectConfig liveCfg = liveConnectConfig();
      Content newLiveSi = addInstructions(liveCfg.systemInstruction(), instructions);
      return liveConnectConfig(liveCfg.toBuilder().systemInstruction(newLiveSi).build());
    }

    private Content addInstructions(
        Optional<Content> currentSystemInstruction, List<String> additionalInstructions) {
      checkArgument(
          currentSystemInstruction.isEmpty()
              || currentSystemInstruction.get().parts().map(parts -> parts.size()).orElse(0) <= 1,
          "At most one instruction is supported.");

      // Either append to the existing instruction, or create a new one.
      String instructions = String.join("\n\n", additionalInstructions);

      Optional<Part> part =
          currentSystemInstruction
              .flatMap(Content::parts)
              .flatMap(parts -> parts.stream().findFirst());
      if (part.isEmpty() || part.get().text().isEmpty()) {
        part = Optional.of(Part.fromText(instructions));
      } else {
        part = Optional.of(Part.fromText(part.get().text().get() + "\n\n" + instructions));
      }
      checkState(part.isPresent(), "Failed to create instruction.");

      String role = currentSystemInstruction.flatMap(Content::role).orElse("user");
      return Content.builder().parts(part.get()).role(role).build();
    }

    @CanIgnoreReturnValue
    public final Builder appendTools(List<BaseTool> tools) {
      if (tools.isEmpty()) {
        return this;
      }
      return tools(
          ImmutableMap.<String, BaseTool>builder()
              .putAll(
                  Stream.concat(tools.stream(), tools().values().stream())
                      .collect(
                          toImmutableMap(
                              BaseTool::name,
                              tool -> tool,
                              (tool1, tool2) -> {
                                throw new IllegalArgumentException(
                                    String.format("Duplicate tool name: %s", tool1.name()));
                              })))
              .buildOrThrow());
    }

    /**
     * Sets the output schema for the LLM response. If set, The output content will always be a JSON
     * string that conforms to the schema.
     */
    @CanIgnoreReturnValue
    public final Builder outputSchema(Schema schema) {
      GenerateContentConfig config =
          config().orElseGet(() -> GenerateContentConfig.builder().build());
      return config(
          config.toBuilder().responseSchema(schema).responseMimeType("application/json").build());
    }

    public abstract LlmRequest build();
  }
}
