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
package com.google.adk.plugins.recordings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Utility class for loading recordings from YAML files. */
public final class RecordingsLoader {

  private static final ObjectMapper YAML_MAPPER = createYamlMapper();
  private static final PropertyNamingStrategies.SnakeCaseStrategy SNAKE_CASE =
      new PropertyNamingStrategies.SnakeCaseStrategy();

  /** Mix-in to override Content deserialization to use our custom deserializer. */
  @JsonDeserialize(using = ContentUnionDeserializer.class)
  private abstract static class ContentUnionMixin {}

  /**
   * Custom deserializer for ContentUnion fields.
   *
   * <p>In Python, GenerateContentConfig.system_instruction takes ContentUnion; In Java,
   * GenerateContentConfig.system_instruction takes only Content.
   */
  private static class ContentUnionDeserializer extends JsonDeserializer<Content> {
    @Override
    public Content deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JsonNode node = p.readValueAsTree();
      if (node.isTextual()) {
        // If it's a string, create Content with a single text Part
        return Content.fromParts(Part.fromText(node.asText()));
      } else {
        // For structured objects, manually construct Content from the JSON
        // We can't use treeToValue as it would cause recursion with the Builder pattern
        Content.Builder builder = Content.builder();

        if (node.has("parts")) {
          // Deserialize parts array
          JsonNode partsNode = node.get("parts");
          if (partsNode.isArray()) {
            List<Part> parts = new ArrayList<>();
            for (JsonNode partNode : partsNode) {
              // Use the ObjectMapper's codec to deserialize Part
              Part part = p.getCodec().treeToValue(partNode, Part.class);
              parts.add(part);
            }
            builder.parts(parts);
          }
        }

        if (node.has("role")) {
          builder.role(node.get("role").asText());
        }

        return builder.build();
      }
    }
  }

  /** Custom deserializer for byte[] that handles URL-safe Base64 with padding. */
  private static class UrlSafeBase64Deserializer extends JsonDeserializer<byte[]> {
    @Override
    public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      String text = p.getValueAsString();
      if (text == null || text.isEmpty()) {
        return null;
      }
      try {
        return Base64.getUrlDecoder().decode(text);
      } catch (IllegalArgumentException e) {
        throw ctxt.weirdStringException(
            text, byte[].class, "Invalid Base64 encoding: " + e.getMessage());
      }
    }
  }

  /**
   * Builds the YAML mapper used throughout this plugin.
   *
   * <p>The mapper reads snake_case keys and attaches a mix-in so `ContentUnion` values can be
   * either raw strings or regular `Content` objects when deserialized.
   */
  private static ObjectMapper createYamlMapper() {
    // Custom annotation introspector that converts @JsonProperty annotation values from camelCase
    // to snake_case for YAML deserialization
    JacksonAnnotationIntrospector snakeCaseAnnotationIntrospector =
        new JacksonAnnotationIntrospector() {
          @Override
          public PropertyName findNameForDeserialization(Annotated a) {
            PropertyName name = super.findNameForDeserialization(a);
            return convertToSnakeCase(name);
          }

          private PropertyName convertToSnakeCase(PropertyName name) {
            if (name != null && name.hasSimpleName()) {
              String simpleName = name.getSimpleName();
              String snakeCaseName = SNAKE_CASE.translate(simpleName);
              if (snakeCaseName != null && !snakeCaseName.equals(simpleName)) {
                return PropertyName.construct(snakeCaseName);
              }
            }
            return name;
          }
        };

    ObjectMapper mapper =
        JsonMapper.builder(new YAMLFactory())
            .addModule(new Jdk8Module())
            .addModule(
                new SimpleModule().addDeserializer(byte[].class, new UrlSafeBase64Deserializer()))
            .propertyNamingStrategy(new PropertyNamingStrategies.SnakeCaseStrategy())
            .annotationIntrospector(snakeCaseAnnotationIntrospector)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addMixIn(Content.class, ContentUnionMixin.class)
            .build();

    return mapper;
  }

  /**
   * Loads recordings from a YAML file.
   *
   * @param path the path to the YAML file
   * @return the parsed Recordings object
   * @throws IOException if an I/O error occurs
   */
  public static Recordings load(Path path) throws IOException {
    return YAML_MAPPER.readValue(path.toFile(), Recordings.class);
  }

  /**
   * Loads recordings from a YAML input stream.
   *
   * @param inputStream the YAML input stream
   * @return the parsed Recordings object
   * @throws IOException if an I/O error occurs
   */
  public static Recordings load(InputStream inputStream) throws IOException {
    return YAML_MAPPER.readValue(inputStream, Recordings.class);
  }

  /**
   * Loads recordings from a YAML string.
   *
   * @param yamlContent the YAML content as a string
   * @return the parsed Recordings object
   * @throws IOException if an I/O error occurs
   */
  public static Recordings load(String yamlContent) throws IOException {
    return YAML_MAPPER.readValue(yamlContent, Recordings.class);
  }

  private RecordingsLoader() {}
}
