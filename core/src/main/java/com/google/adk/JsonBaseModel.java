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

package com.google.adk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.cfg.MutableConfigOverride;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Optional;

/** The base class for the types that needs JSON serialization/deserialization capability. */
public abstract class JsonBaseModel {

  private static final ObjectMapper objectMapper = createObjectMapper();

  /** Creates the ObjectMapper. */
  private static ObjectMapper createObjectMapper() {
    ObjectMapper objectMapper =
        new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE)
            // Register support for java.util.Optional
            .registerModule(new Jdk8Module())
            // Register support for java.util.Date
            .registerModule(new JavaTimeModule()) // TODO: echo sec module replace, locale
            // Ignore unknown properties during deserialization
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // If a field in a model is of type Optional and its value is null or Optional.empty(), then
    // that fieldwill be omitted entirely from the serialized JSON output. Fields that contain a
    // present Optional (e.g., Optional.of("someValue")) will be included as normal.
    MutableConfigOverride configOverride = objectMapper.configOverride(Optional.class);
    configOverride.setInclude(
        JsonInclude.Value.construct(
            JsonInclude.Include.NON_ABSENT, JsonInclude.Include.NON_ABSENT));
    return objectMapper;
  }

  /** Serializes an object to a Json string. */
  public static String toJsonString(Object object) {
    try {
      return objectMapper.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Returns the mutable ObjectMapper instance used by ADK. */
  public static ObjectMapper getMapper() {
    return objectMapper;
  }

  /** Serializes this object (i.e., the ObjectMappper instance used by ADK) to a Json string. */
  public String toJson() {
    return toJsonString(this);
  }

  /** Serializes an object to a JsonNode. */
  protected static JsonNode toJsonNode(Object object) {
    return objectMapper.valueToTree(object);
  }

  /** Deserializes a Json string to an object of the given type. */
  public static <T extends JsonBaseModel> T fromJsonString(String jsonString, Class<T> clazz) {
    try {
      return objectMapper.readValue(jsonString, clazz);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Deserializes a JsonNode to an object of the given type. */
  public static <T extends JsonBaseModel> T fromJsonNode(JsonNode jsonNode, Class<T> clazz) {
    try {
      return objectMapper.treeToValue(jsonNode, clazz);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
