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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;

class ConfigMapperTest {

  private ConfigMapper configMapper;

  @BeforeEach
  void setUp() {
    configMapper = new ConfigMapper();
  }

  @Test
  void testToSpringAiChatOptionsWithEmptyConfig() {
    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.empty());

    assertThat(chatOptions).isNull();
  }

  @Test
  void testToSpringAiChatOptionsWithBasicConfig() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.8f).maxOutputTokens(1000).topP(0.9f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isCloseTo(0.8, within(0.001));
    assertThat(chatOptions.getMaxTokens()).isEqualTo(1000);
    assertThat(chatOptions.getTopP()).isCloseTo(0.9, within(0.001));
  }

  @Test
  void testToSpringAiChatOptionsWithStopSequences() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().stopSequences(List.of("STOP", "END", "FINISH")).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getStopSequences()).containsExactly("STOP", "END", "FINISH");
  }

  @Test
  void testToSpringAiChatOptionsWithEmptyStopSequences() {
    GenerateContentConfig config = GenerateContentConfig.builder().stopSequences(List.of()).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getStopSequences()).isNull();
  }

  @Test
  void testToSpringAiChatOptionsWithInvalidTopK() {
    GenerateContentConfig config = GenerateContentConfig.builder().topK(100f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(isValid).isFalse();
  }

  @Test
  void testToSpringAiChatOptionsWithPenalties() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().presencePenalty(0.5f).frequencyPenalty(0.3f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getPresencePenalty()).isCloseTo(0.5, within(0.001));
    assertThat(chatOptions.getFrequencyPenalty()).isCloseTo(0.3, within(0.001));
  }

  @Test
  void testToSpringAiChatOptionsWithAllParameters() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .temperature(0.7f)
            .maxOutputTokens(2000)
            .topP(0.95f)
            .topK(50f)
            .stopSequences(List.of("STOP"))
            .presencePenalty(0.1f)
            .frequencyPenalty(0.2f)
            .build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isCloseTo(0.7, within(0.001));
    assertThat(chatOptions.getMaxTokens()).isEqualTo(2000);
    assertThat(chatOptions.getTopP()).isCloseTo(0.95, within(0.001));
    assertThat(chatOptions.getTopK()).isCloseTo(50, within(1));
    assertThat(chatOptions.getStopSequences()).containsExactly("STOP");
    assertThat(chatOptions.getPresencePenalty()).isCloseTo(0.1, within(0.001));
    assertThat(chatOptions.getFrequencyPenalty()).isCloseTo(0.2, within(0.001));
  }

  @Test
  void testCreateDefaultChatOptions() {
    ChatOptions defaultOptions = configMapper.createDefaultChatOptions();

    assertThat(defaultOptions).isNotNull();
    assertThat(defaultOptions.getTemperature()).isCloseTo(0.7, within(0.001));
    assertThat(defaultOptions.getMaxTokens()).isEqualTo(1000);
  }

  @Test
  void testIsConfigurationValidWithEmptyConfig() {
    boolean isValid = configMapper.isConfigurationValid(Optional.empty());

    assertThat(isValid).isTrue();
  }

  @Test
  void testIsConfigurationValidWithValidConfig() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.8f).topP(0.9f).maxOutputTokens(1000).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isTrue();
  }

  @Test
  void testIsConfigurationValidWithInvalidTemperature() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(-0.5f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithHighTemperature() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(3.0f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithInvalidTopP() {
    GenerateContentConfig config = GenerateContentConfig.builder().topP(-0.1f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithHighTopP() {
    GenerateContentConfig config = GenerateContentConfig.builder().topP(1.5f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithResponseSchema() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseSchema(com.google.genai.types.Schema.builder().type("OBJECT").build())
            .build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testIsConfigurationValidWithResponseMimeType() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().responseMimeType("application/json").build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isFalse();
  }

  @Test
  void testToSpringAiChatOptionsWithBoundaryValues() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.0f).topP(1.0f).maxOutputTokens(1).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isEqualTo(0.0);
    assertThat(chatOptions.getTopP()).isEqualTo(1.0);
    assertThat(chatOptions.getMaxTokens()).isEqualTo(1);
  }

  @Test
  void testIsConfigurationValidWithBoundaryValues() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.0f).topP(0.0f).build();

    boolean isValid = configMapper.isConfigurationValid(Optional.of(config));

    assertThat(isValid).isTrue();

    GenerateContentConfig config2 =
        GenerateContentConfig.builder().temperature(2.0f).topP(1.0f).build();

    boolean isValid2 = configMapper.isConfigurationValid(Optional.of(config2));

    assertThat(isValid2).isTrue();
  }

  @Test
  void testToSpringAiChatOptionsWithNullStopSequences() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(0.5f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getStopSequences()).isNull();
  }

  @Test
  void testTypeConversions() {
    // Test Float to Double conversions are handled properly
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.123456f).topP(0.987654f).build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isNotNull();
    assertThat(chatOptions.getTemperature()).isCloseTo(0.123456, within(0.000001));
    assertThat(chatOptions.getTopP()).isCloseTo(0.987654, within(0.000001));
  }

  @Test
  void toGenerateContentConfig_nullOptions_returnsNull() {
    GenerateContentConfig fromNull = configMapper.toGenerateContentConfig(null);

    assertThat(fromNull).isNull();
  }

  @Test
  void toGenerateContentConfig_allParameters_mapsAllFields() {
    ChatOptions chatOptions =
        ChatOptions.builder()
            .temperature(0.75)
            .maxTokens(1500)
            .topP(0.85)
            .topK(40)
            .stopSequences(List.of("STOP", "END"))
            .presencePenalty(0.25)
            .frequencyPenalty(0.5)
            .build();

    GenerateContentConfig config = configMapper.toGenerateContentConfig(chatOptions);

    assertThat(config).isNotNull();
    assertThat(config.temperature()).isPresent();
    assertThat(config.temperature().get()).isCloseTo(0.75f, within(0.001f));
    assertThat(config.maxOutputTokens()).contains(1500);
    assertThat(config.topP()).isPresent();
    assertThat(config.topP().get()).isCloseTo(0.85f, within(0.001f));
    assertThat(config.topK()).isPresent();
    assertThat(config.topK().get()).isCloseTo(40.0f, within(0.001f));
    assertThat(config.stopSequences()).contains(List.of("STOP", "END"));
    assertThat(config.presencePenalty()).isPresent();
    assertThat(config.presencePenalty().get()).isCloseTo(0.25f, within(0.001f));
    assertThat(config.frequencyPenalty()).isPresent();
    assertThat(config.frequencyPenalty().get()).isCloseTo(0.5f, within(0.001f));
  }

  @Test
  void toGenerateContentConfig_emptyStopSequences_omitsStopSequences() {
    ChatOptions chatOptions = ChatOptions.builder().stopSequences(List.of()).build();

    GenerateContentConfig config = configMapper.toGenerateContentConfig(chatOptions);

    assertThat(config).isNotNull();
    assertThat(config.stopSequences()).isEmpty();
  }

  @Test
  void toGenerateContentConfig_structuredOutputSchema_roundTripsResponseJsonSchema() {
    ChatOptions structuredOptions =
        StructuredOutputChatOptions.builder()
            .outputSchema(
                "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"]}")
            .temperature(0.25)
            .build();

    GenerateContentConfig config = configMapper.toGenerateContentConfig(structuredOptions);
    ChatOptions roundTrippedOptions = configMapper.toSpringAiChatOptions(Optional.of(config));
    GenerateContentConfig roundTrippedConfig =
        configMapper.toGenerateContentConfig(roundTrippedOptions);

    assertThat(config).isNotNull();
    assertThat(config.temperature()).contains(0.25f);
    assertThat(config.responseMimeType()).contains("application/json");
    assertThat(config.responseSchema()).isEmpty();
    assertThat(config.responseJsonSchema())
        .contains(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of("answer", Map.of("type", "string")),
                "required",
                List.of("answer")));
    assertThat(roundTrippedOptions).isInstanceOf(StructuredOutputChatOptions.class);
    assertThat(roundTrippedConfig).isEqualTo(config);
  }

  @Test
  void toGenerateContentConfig_nestedRefSchema_preservesDefsAndRefs() {
    String nestedSchema =
        """
        {
          "type": "object",
          "$defs": {
            "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
          },
          "properties": {
            "home": {"$ref": "#/$defs/Address"},
            "nickname": {"anyOf": [{"type": "string"}, {"type": "null"}]}
          },
          "required": ["home"]
        }
        """;
    ChatOptions structuredOptions =
        StructuredOutputChatOptions.builder().outputSchema(nestedSchema).build();

    GenerateContentConfig config = configMapper.toGenerateContentConfig(structuredOptions);

    assertThat(config).isNotNull();
    @SuppressWarnings("unchecked") // Test JSON object keys are strings.
    Map<String, Object> responseJsonSchema =
        (Map<String, Object>) config.responseJsonSchema().orElseThrow();
    assertThat(responseJsonSchema).containsKey("$defs");
    @SuppressWarnings("unchecked") // Test JSON object keys are strings.
    Map<String, Object> properties = (Map<String, Object>) responseJsonSchema.get("properties");
    assertThat(properties).containsEntry("home", Map.of("$ref", "#/$defs/Address"));
    assertThat(properties).containsKey("nickname");
  }

  @Test
  void toSpringAiChatOptions_responseSchema_mapsToStructuredOutputSchema() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseSchema(
                Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of("answer", Schema.builder().type(Type.Known.STRING).build()))
                    .build())
            .build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isInstanceOf(StructuredOutputChatOptions.class);
    assertThat(((StructuredOutputChatOptions) chatOptions).getOutputSchema())
        .contains("\"answer\"")
        .contains("\"type\":\"object\"");
  }

  @Test
  void toOpenAiStrictSchema_nestedSchema_requiresAllPropertiesAndStripsRefSiblings()
      throws Exception {
    String schema =
        """
        {
          "type": "object",
          "$defs": {
            "Address": {"type": "object", "properties": {"city": {"type": "string"}}}
          },
          "properties": {
            "name": {"type": "string"},
            "home": {"$ref": "#/$defs/Address", "description": "Home address"},
            "pets": {
              "type": "array",
              "items": {"type": "object", "properties": {"kind": {"type": "string"}}}
            },
            "nick": {"anyOf": [{"type": "object", "properties": {"v": {"type": "string"}}}]}
          },
          "required": ["name"]
        }
        """;

    Map<?, ?> strict =
        new ObjectMapper().readValue(ConfigMapper.toOpenAiStrictSchema(schema), Map.class);

    assertThat(strict.get("additionalProperties")).isEqualTo(false);
    assertThat(strict.get("required")).isEqualTo(List.of("home", "name", "nick", "pets"));
    Map<?, ?> properties = (Map<?, ?>) strict.get("properties");
    assertThat(properties.get("home")).isEqualTo(Map.of("$ref", "#/$defs/Address"));
    Map<?, ?> address = (Map<?, ?>) ((Map<?, ?>) strict.get("$defs")).get("Address");
    assertThat(address.get("additionalProperties")).isEqualTo(false);
    assertThat(address.get("required")).isEqualTo(List.of("city"));
    Map<?, ?> petItems = (Map<?, ?>) ((Map<?, ?>) properties.get("pets")).get("items");
    assertThat(petItems.get("required")).isEqualTo(List.of("kind"));
    Map<?, ?> nickOption =
        (Map<?, ?>) ((List<?>) ((Map<?, ?>) properties.get("nick")).get("anyOf")).get(0);
    assertThat(nickOption.get("additionalProperties")).isEqualTo(false);
  }

  @Test
  void toOpenAiStrictSchema_upperCaseTypes_lowercasesAndEnforcesStrictObjects() throws Exception {
    String schema =
        """
        {
          "type": "OBJECT",
          "properties": {
            "city": {"type": "STRING"},
            "nickname": {"type": ["STRING", "NULL"]}
          }
        }
        """;

    Map<?, ?> strict =
        new ObjectMapper().readValue(ConfigMapper.toOpenAiStrictSchema(schema), Map.class);

    assertThat(strict.get("type")).isEqualTo("object");
    assertThat(strict.get("additionalProperties")).isEqualTo(false);
    assertThat(strict.get("required")).isEqualTo(List.of("city", "nickname"));
    Map<?, ?> properties = (Map<?, ?>) strict.get("properties");
    assertThat(((Map<?, ?>) properties.get("city")).get("type")).isEqualTo("string");
    assertThat(((Map<?, ?>) properties.get("nickname")).get("type"))
        .isEqualTo(List.of("string", "null"));
  }

  @Test
  void toGenerateContentConfig_blankOrInvalidOutputSchema_handlesEdgeCases() {
    ChatOptions blankSchemaOptions =
        StructuredOutputChatOptions.builder().outputSchema("   ").build();
    ChatOptions invalidSchemaOptions =
        StructuredOutputChatOptions.builder().outputSchema("{invalid").build();
    ChatOptions nullSchemaOptions =
        StructuredOutputChatOptions.builder().outputSchema("null").build();

    GenerateContentConfig fromBlank = configMapper.toGenerateContentConfig(blankSchemaOptions);

    assertThat(fromBlank).isNotNull();
    assertThat(fromBlank.responseJsonSchema()).isEmpty();
    assertThat(fromBlank.responseMimeType()).isEmpty();
    assertThatThrownBy(() -> configMapper.toGenerateContentConfig(invalidSchemaOptions))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> configMapper.toGenerateContentConfig(nullSchemaOptions))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                configMapper.toGenerateContentConfig(
                    StructuredOutputChatOptions.builder().outputSchema("{} trailing").build()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toSpringAiChatOptions_schemaObjectAsResponseJsonSchema_convertsToJsonSchema() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseJsonSchema(Schema.builder().type(Type.Known.STRING).build())
            .build();

    ChatOptions chatOptions = configMapper.toSpringAiChatOptions(Optional.of(config));

    assertThat(chatOptions).isInstanceOf(StructuredOutputChatOptions.class);
    assertThat(((StructuredOutputChatOptions) chatOptions).getOutputSchema())
        .isEqualTo("{\"type\":\"string\"}");
  }

  @Test
  void isChatOptionsValid_validAndBoundaryOptions_returnsTrue() {
    ChatOptions validOptions = ChatOptions.builder().temperature(0.8).topP(0.9).topK(32).build();
    ChatOptions lowerBounds = ChatOptions.builder().temperature(0.0).topP(0.0).topK(1).build();
    ChatOptions upperBounds = ChatOptions.builder().temperature(2.0).topP(1.0).topK(64).build();

    boolean nullValid = configMapper.isChatOptionsValid(null);
    boolean validValid = configMapper.isChatOptionsValid(validOptions);
    boolean lowerValid = configMapper.isChatOptionsValid(lowerBounds);
    boolean upperValid = configMapper.isChatOptionsValid(upperBounds);

    assertThat(nullValid).isTrue();
    assertThat(validValid).isTrue();
    assertThat(lowerValid).isTrue();
    assertThat(upperValid).isTrue();
  }

  @Test
  void isChatOptionsValid_outOfBoundsOrNaN_returnsFalse() {
    ChatOptions lowTemp = ChatOptions.builder().temperature(-0.1).build();
    ChatOptions highTemp = ChatOptions.builder().temperature(2.5).build();
    ChatOptions nanTemp = ChatOptions.builder().temperature(Double.NaN).build();
    ChatOptions lowTopP = ChatOptions.builder().topP(-0.1).build();
    ChatOptions highTopP = ChatOptions.builder().topP(1.1).build();
    ChatOptions nanTopP = ChatOptions.builder().topP(Double.NaN).build();
    ChatOptions lowTopK = ChatOptions.builder().topK(0).build();
    ChatOptions highTopK = ChatOptions.builder().topK(65).build();
    GenerateContentConfig nanConfig =
        GenerateContentConfig.builder().temperature(Float.NaN).build();

    boolean lowTempValid = configMapper.isChatOptionsValid(lowTemp);
    boolean highTempValid = configMapper.isChatOptionsValid(highTemp);
    boolean nanTempValid = configMapper.isChatOptionsValid(nanTemp);
    boolean lowTopPValid = configMapper.isChatOptionsValid(lowTopP);
    boolean highTopPValid = configMapper.isChatOptionsValid(highTopP);
    boolean nanTopPValid = configMapper.isChatOptionsValid(nanTopP);
    boolean lowTopKValid = configMapper.isChatOptionsValid(lowTopK);
    boolean highTopKValid = configMapper.isChatOptionsValid(highTopK);
    boolean nanConfigValid = configMapper.isConfigurationValid(Optional.of(nanConfig));

    assertThat(lowTempValid).isFalse();
    assertThat(highTempValid).isFalse();
    assertThat(nanTempValid).isFalse();
    assertThat(lowTopPValid).isFalse();
    assertThat(highTopPValid).isFalse();
    assertThat(nanTopPValid).isFalse();
    assertThat(lowTopKValid).isFalse();
    assertThat(highTopKValid).isFalse();
    assertThat(nanConfigValid).isFalse();
  }

  @Test
  void configConversion_roundTrip_preservesAllFields() {
    GenerateContentConfig originalConfig =
        GenerateContentConfig.builder()
            .temperature(0.5f)
            .maxOutputTokens(1024)
            .topP(0.75f)
            .topK(32f)
            .stopSequences(List.of("DONE"))
            .presencePenalty(0.25f)
            .frequencyPenalty(0.5f)
            .build();

    ChatOptions springOptions = configMapper.toSpringAiChatOptions(Optional.of(originalConfig));
    GenerateContentConfig roundTrippedConfig = configMapper.toGenerateContentConfig(springOptions);
    ChatOptions roundTrippedOptions =
        configMapper.toSpringAiChatOptions(Optional.of(roundTrippedConfig));

    assertThat(roundTrippedConfig).isEqualTo(originalConfig);
    assertThat(roundTrippedOptions.getTemperature()).isEqualTo(springOptions.getTemperature());
    assertThat(roundTrippedOptions.getMaxTokens()).isEqualTo(springOptions.getMaxTokens());
    assertThat(roundTrippedOptions.getTopP()).isEqualTo(springOptions.getTopP());
    assertThat(roundTrippedOptions.getTopK()).isEqualTo(springOptions.getTopK());
    assertThat(roundTrippedOptions.getStopSequences()).isEqualTo(springOptions.getStopSequences());
    assertThat(roundTrippedOptions.getPresencePenalty())
        .isEqualTo(springOptions.getPresencePenalty());
    assertThat(roundTrippedOptions.getFrequencyPenalty())
        .isEqualTo(springOptions.getFrequencyPenalty());
  }
}
