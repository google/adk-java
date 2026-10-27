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
package com.google.adk.models.springai;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

/**
 * Maps between ADK {@link GenerateContentConfig} and Spring AI {@link ChatOptions} in both
 * directions.
 */
public class ConfigMapper {

  private static final TypeReference<Map<String, Object>> MAP_TYPE_REFERENCE =
      new TypeReference<>() {};
  private static final String JSON_MIME_TYPE = "application/json";
  private final ToolConverter toolConverter = new ToolConverter();

  /**
   * Converts ADK GenerateContentConfig to Spring AI ChatOptions.
   *
   * <p>When {@link GenerateContentConfig#responseJsonSchema()} or {@link
   * GenerateContentConfig#responseSchema()} is present, the returned options implement {@link
   * StructuredOutputChatOptions} carrying the serialized JSON schema.
   *
   * @param config The ADK configuration to convert
   * @return Spring AI ChatOptions or null if no config provided
   */
  public @Nullable ChatOptions toSpringAiChatOptions(Optional<GenerateContentConfig> config) {
    Objects.requireNonNull(config, "config cannot be null");
    if (config.isEmpty()) {
      return null;
    }
    GenerateContentConfig contentConfig = config.get();

    // responseJsonSchema may hold a genai Schema, whose JSON uses upper-case types like "STRING".
    Optional<Object> outputSchema =
        contentConfig
            .responseJsonSchema()
            .or(contentConfig::responseSchema)
            .map(
                schema ->
                    schema instanceof Schema adkSchema
                        ? toolConverter.convertSchemaToSpringAi(adkSchema)
                        : schema);
    ChatOptions.Builder<?> optionsBuilder;
    if (outputSchema.isPresent()) {
      try {
        String schemaJson = JsonBaseModel.getMapper().writeValueAsString(outputSchema.get());
        optionsBuilder = StructuredOutputChatOptions.builder().outputSchema(schemaJson);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Failed to serialize response schema to JSON", e);
      }
    } else {
      optionsBuilder = ChatOptions.builder();
    }

    contentConfig.temperature().ifPresent(temp -> optionsBuilder.temperature(temp.doubleValue()));
    contentConfig.maxOutputTokens().ifPresent(optionsBuilder::maxTokens);
    contentConfig.topP().ifPresent(topP -> optionsBuilder.topP(topP.doubleValue()));
    contentConfig.topK().ifPresent(topK -> optionsBuilder.topK(topK.intValue()));
    contentConfig
        .stopSequences()
        .filter(sequences -> !sequences.isEmpty())
        .ifPresent(optionsBuilder::stopSequences);
    contentConfig
        .presencePenalty()
        .ifPresent(penalty -> optionsBuilder.presencePenalty(penalty.doubleValue()));
    contentConfig
        .frequencyPenalty()
        .ifPresent(penalty -> optionsBuilder.frequencyPenalty(penalty.doubleValue()));

    return optionsBuilder.build();
  }

  /**
   * Converts Spring AI {@link ChatOptions} to an ADK {@link GenerateContentConfig}.
   *
   * <p>When {@code chatOptions} implements {@link StructuredOutputChatOptions} with a non-blank
   * output schema, the parsed schema is set as {@link GenerateContentConfig#responseJsonSchema()}
   * with a JSON response MIME type.
   *
   * @param chatOptions The Spring AI chat options to convert, or null
   * @return ADK {@link GenerateContentConfig}, or null if {@code chatOptions} is null
   */
  public @Nullable GenerateContentConfig toGenerateContentConfig(
      @Nullable ChatOptions chatOptions) {
    if (chatOptions == null) {
      return null;
    }

    GenerateContentConfig.Builder builder = GenerateContentConfig.builder();

    if (chatOptions.getTemperature() != null) {
      builder.temperature(chatOptions.getTemperature().floatValue());
    }
    if (chatOptions.getMaxTokens() != null) {
      builder.maxOutputTokens(chatOptions.getMaxTokens());
    }
    if (chatOptions.getTopP() != null) {
      builder.topP(chatOptions.getTopP().floatValue());
    }
    if (chatOptions.getTopK() != null) {
      builder.topK(chatOptions.getTopK().floatValue());
    }
    if (chatOptions.getStopSequences() != null && !chatOptions.getStopSequences().isEmpty()) {
      builder.stopSequences(ImmutableList.copyOf(chatOptions.getStopSequences()));
    }
    if (chatOptions.getPresencePenalty() != null) {
      builder.presencePenalty(chatOptions.getPresencePenalty().floatValue());
    }
    if (chatOptions.getFrequencyPenalty() != null) {
      builder.frequencyPenalty(chatOptions.getFrequencyPenalty().floatValue());
    }
    if (chatOptions instanceof StructuredOutputChatOptions structuredOptions) {
      String outputSchema = structuredOptions.getOutputSchema();
      if (outputSchema != null && !outputSchema.isBlank()) {
        // Passed through as JSON schema so $defs, $ref and anyOf survive; Schema would drop them.
        builder
            .responseJsonSchema(parseOutputSchema(outputSchema))
            .responseMimeType(JSON_MIME_TYPE);
      }
    }

    return builder.build();
  }

  /**
   * Creates default ChatOptions for cases where no ADK config is provided.
   *
   * @return Basic ChatOptions with reasonable defaults
   */
  public ChatOptions createDefaultChatOptions() {
    return ChatOptions.builder().temperature(0.7).maxTokens(1000).build();
  }

  /**
   * Validates that the configuration is compatible with Spring AI.
   *
   * @param config The ADK configuration to validate
   * @return true if configuration is valid and supported
   */
  public boolean isConfigurationValid(Optional<GenerateContentConfig> config) {
    Objects.requireNonNull(config, "config cannot be null");
    if (config.isEmpty()) {
      return true;
    }
    GenerateContentConfig contentConfig = config.get();

    if (contentConfig.responseSchema().isPresent()) {
      return false;
    }

    if (contentConfig.responseMimeType().isPresent()) {
      return false;
    }

    if (contentConfig.temperature().isPresent()
        && !isValidTemperature(contentConfig.temperature().get())) {
      return false;
    }

    if (contentConfig.topP().isPresent() && !isValidTopP(contentConfig.topP().get())) {
      return false;
    }

    if (contentConfig.topK().isPresent() && !isValidTopK(contentConfig.topK().get())) {
      return false;
    }

    return true;
  }

  /**
   * Validates that Spring AI {@link ChatOptions} values fall within valid ADK generation bounds.
   *
   * @param chatOptions The Spring AI chat options to validate, or null
   * @return true if the options are valid
   */
  public boolean isChatOptionsValid(@Nullable ChatOptions chatOptions) {
    if (chatOptions == null) {
      return true;
    }

    if (chatOptions.getTemperature() != null && !isValidTemperature(chatOptions.getTemperature())) {
      return false;
    }

    if (chatOptions.getTopP() != null && !isValidTopP(chatOptions.getTopP())) {
      return false;
    }

    if (chatOptions.getTopK() != null && !isValidTopK(chatOptions.getTopK())) {
      return false;
    }

    return true;
  }

  private static Map<String, Object> parseOutputSchema(String outputSchema) {
    try {
      Map<String, Object> schema =
          JsonBaseModel.getMapper()
              .readerFor(MAP_TYPE_REFERENCE)
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .readValue(outputSchema);
      if (schema == null) {
        throw new IllegalArgumentException("Spring AI output schema must be a JSON object");
      }
      return schema;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to parse Spring AI output schema", e);
    }
  }

  /**
   * Rewrites a JSON output schema for OpenAI strict structured outputs, as Python ADK's LiteLLM
   * model does: every object schema gets {@code additionalProperties: false} and lists all its
   * properties as required, and {@code $ref} nodes lose their sibling keywords.
   */
  static String toOpenAiStrictSchema(String outputSchema) {
    Map<String, Object> schema = parseOutputSchema(outputSchema);
    enforceOpenAiStrictSchema(schema);
    try {
      return JsonBaseModel.getMapper().writeValueAsString(schema);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize response schema to JSON", e);
    }
  }

  private static void enforceOpenAiStrictSchema(Map<String, Object> schema) {
    if (schema.containsKey("$ref")) {
      schema.keySet().retainAll(Set.of("$ref"));
      return;
    }
    // A genai Schema serializes types in upper case ("OBJECT"); JSON Schema and OpenAI expect
    // lower case, so lowercase them first, as Python ADK's lowercase_schema_types does.
    if (schema.get("type") instanceof String typeName) {
      schema.put("type", typeName.toLowerCase(Locale.ROOT));
    } else if (schema.get("type") instanceof List<?> typeNames) {
      schema.put(
          "type",
          typeNames.stream()
              .map(t -> t instanceof String name ? name.toLowerCase(Locale.ROOT) : t)
              .toList());
    }
    if ("object".equals(schema.get("type"))
        && schema.get("properties") instanceof Map<?, ?> properties) {
      schema.put("additionalProperties", false);
      schema.put(
          "required",
          properties.keySet().stream().map(String::valueOf).sorted().collect(toImmutableList()));
    }
    List<Object> subschemas = new ArrayList<>();
    for (String mapKey : List.of("$defs", "definitions", "properties")) {
      if (schema.get(mapKey) instanceof Map<?, ?> map) {
        subschemas.addAll(map.values());
      }
    }
    for (String listKey : List.of("anyOf", "oneOf", "allOf")) {
      if (schema.get(listKey) instanceof List<?> list) {
        subschemas.addAll(list);
      }
    }
    subschemas.add(schema.get("items"));
    for (Object subschema : subschemas) {
      if (subschema instanceof Map<?, ?> map) {
        enforceOpenAiStrictSchema(ToolConverter.castToMap(map));
      }
    }
  }

  private static boolean isValidTemperature(double temp) {
    return temp >= 0.0 && temp <= 2.0;
  }

  private static boolean isValidTopP(double topP) {
    return topP >= 0.0 && topP <= 1.0;
  }

  private static boolean isValidTopK(double topK) {
    return topK >= 1.0 && topK <= 64.0;
  }
}
