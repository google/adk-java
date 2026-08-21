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

package com.google.adk.agents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.google.common.base.CaseFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for preprocessing YAML content to convert snake_case keys to camelCase.
 *
 * <p>This preprocessor bridges the gap between YAML naming conventions (snake_case) and Java naming
 * conventions (camelCase), allowing YAML configuration files to be more readable and idiomatic
 * while still mapping correctly to Java objects.
 *
 * <p><b>Key Features:</b>
 *
 * <ul>
 *   <li>Converts all map keys from snake_case to camelCase at any nesting level
 *   <li>Handles complex nested structures (maps, lists, and combinations)
 *   <li>Preserves values unchanged (strings, numbers, booleans, lists, etc.)
 *   <li>Fails gracefully - returns original YAML if preprocessing fails
 * </ul>
 *
 * <p><b>Common Use Cases:</b>
 *
 * <ul>
 *   <li>Agent configuration: {@code agent_class → agentClass}
 *   <li>Tool configuration: {@code tool_filter → toolFilter}
 *   <li>Generation config: {@code max_output_tokens → maxOutputTokens}
 *   <li>Server parameters: {@code stdio_server_params → stdioServerParams}
 * </ul>
 */
final class YamlPreprocessor {

  private static final Logger logger = LoggerFactory.getLogger(YamlPreprocessor.class);

  private YamlPreprocessor() {}

  /**
   * Preprocesses YAML content to convert all snake_case keys to camelCase.
   *
   * <p>This method performs a complete transformation of the YAML structure:
   *
   * <ol>
   *   <li>Parses the YAML string into Java objects using Jackson YAML:
   *       <ul>
   *         <li>YAML mappings become Java {@code Map<String, Object>}
   *         <li>YAML sequences (arrays) become Java {@code List<Object>}
   *         <li>YAML scalars become Java primitives/Strings
   *       </ul>
   *   <li>Recursively traverses all nested maps and lists
   *   <li>Converts every map key from snake_case to camelCase using Guava's CaseFormat
   *   <li>Serializes the transformed structure back to YAML string using Jackson
   * </ol>
   *
   * <p><b>Example transformation:</b>
   *
   * <pre>
   * Input YAML:
   * agent_class: LlmAgent
   * max_tokens: 100
   * tool_names:              # YAML array/sequence notation
   *   - search_tool
   *   - code_tool
   * server_configs:          # Array of maps
   *   - server_name: prod
   *     max_connections: 100
   *   - server_name: dev
   *     max_connections: 50
   *
   * Output YAML:
   * agentClass: LlmAgent
   * maxTokens: 100
   * toolNames:               # Still a list, values unchanged
   *   - search_tool
   *   - code_tool
   * serverConfigs:           # List of maps with converted keys
   *   - serverName: prod
   *     maxConnections: 100
   *   - serverName: dev
   *     maxConnections: 50
   * </pre>
   *
   * <p><b>Error handling:</b> If preprocessing fails for any reason (invalid YAML, parsing errors,
   * etc.), the original content is returned unchanged to allow normal processing to continue.
   * Errors are logged as warnings.
   *
   * @param yamlContent the original YAML content as a string
   * @return the processed YAML content with camelCase keys, or original content if processing fails
   */
  static String preprocessYaml(String yamlContent) {
    if (yamlContent == null || yamlContent.trim().isEmpty()) {
      return yamlContent;
    }

    try {
      // Create Jackson ObjectMapper for YAML
      ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

      // Parse YAML into a Map structure
      Map<String, Object> root =
          yamlMapper.readValue(yamlContent, new TypeReference<Map<String, Object>>() {});

      if (root == null) {
        return yamlContent;
      }

      // Recursively convert all keys from snake_case to camelCase
      Map<String, Object> converted = convertKeysRecursively(root);

      // Convert back to YAML string with proper formatting
      YAMLFactory yamlFactory =
          YAMLFactory.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build();
      ObjectMapper outputMapper = new ObjectMapper(yamlFactory);

      String result = outputMapper.writerWithDefaultPrettyPrinter().writeValueAsString(converted);
      logger.debug("Successfully preprocessed YAML content");
      return result;

    } catch (Exception e) {
      logger.warn("Failed to preprocess YAML content, returning original: {}", e.getMessage());
      // If preprocessing fails, return original content to allow normal parsing to proceed
      return yamlContent;
    }
  }

  /**
   * Recursively converts all map keys from snake_case to camelCase.
   *
   * <p><b>Example transformation:</b>
   *
   * <pre>
   * Input map:
   * {
   *   "agent_class": "LlmAgent",
   *   "server_config": {
   *     "max_connections": 100,
   *     "timeout_ms": 5000,
   *     "retry_config": {
   *       "max_retries": 3,
   *       "backoff_ms": 1000
   *     }
   *   },
   *   "tool_names": ["tool_one", "tool_two"]
   * }
   *
   * Output map:
   * {
   *   "agentClass": "LlmAgent",
   *   "serverConfig": {
   *     "maxConnections": 100,
   *     "timeoutMs": 5000,
   *     "retryConfig": {
   *       "maxRetries": 3,
   *       "backoffMs": 1000
   *     }
   *   },
   *   "toolNames": ["tool_one", "tool_two"]
   * }
   * </pre>
   *
   * <p>Note: Only map keys are converted. Values (strings, numbers, lists) remain unchanged.
   *
   * @param map the map to process
   * @return a new map with converted keys
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> convertKeysRecursively(Map<String, Object> map) {
    Map<String, Object> result = new LinkedHashMap<>();

    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      // Convert snake_case key to camelCase
      String camelKey = convertToCamelCase(key);

      // Recursively process nested structures
      if (value instanceof Map) {
        result.put(camelKey, convertKeysRecursively((Map<String, Object>) value));
      }
      // Handle lists that might contain maps
      else if (value instanceof List) {
        result.put(camelKey, convertListRecursively((List<?>) value));
      }
      // Keep primitive values and other types as-is
      else {
        result.put(camelKey, value);
      }
    }

    return result;
  }

  /**
   * Recursively processes a list, converting any maps it contains.
   *
   * <p>Example: A YAML list of tools with snake_case fields: tools: - name: my_tool tool_config:
   * max_retries: 3 - name: another_tool connection_params: timeout_ms: 5000
   *
   * <p>Each map in the list needs its keys converted to camelCase: tools: - name: my_tool
   * toolConfig: maxRetries: 3 - name: another_tool connectionParams: timeoutMs: 5000
   *
   * @param list the list to process
   * @return a new list with converted maps
   */
  @SuppressWarnings("unchecked")
  private static List<Object> convertListRecursively(List<?> list) {
    List<Object> result = new ArrayList<>();

    for (Object item : list) {
      if (item instanceof Map) {
        result.add(convertKeysRecursively((Map<String, Object>) item));
      } else if (item instanceof List) {
        result.add(convertListRecursively((List<?>) item));
      } else {
        result.add(item);
      }
    }

    return result;
  }

  /**
   * Converts a string from snake_case to camelCase using Guava's CaseFormat.
   *
   * <p><b>Conversion examples:</b>
   *
   * <ul>
   *   <li>{@code "agent_class"} → {@code "agentClass"}
   *   <li>{@code "max_output_tokens"} → {@code "maxOutputTokens"}
   *   <li>{@code "stdio_server_params"} → {@code "stdioServerParams"}
   *   <li>{@code "simple"} → {@code "simple"} (no change needed)
   *   <li>{@code "UPPER_CASE"} → {@code "upperCase"}
   * </ul>
   *
   * <p><b>Special handling:</b>
   *
   * <ul>
   *   <li>Already camelCase strings are detected and returned unchanged
   *   <li>Single words without underscores are returned as-is
   *   <li>Null or empty strings are returned unchanged
   *   <li>Mixed formats (e.g., "some_mixedCase") are converted correctly
   * </ul>
   *
   * @param key the key to convert (may be snake_case, camelCase, or other format)
   * @return the converted key in camelCase
   */
  private static String convertToCamelCase(String key) {
    if (key == null || key.isEmpty()) {
      return key;
    }

    // If the key doesn't contain underscores, return as-is
    if (!key.contains("_")) {
      return key;
    }

    try {
      return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, key);
    } catch (RuntimeException e) {
      logger.debug(
          "Could not convert key '{}' to camelCase, keeping original: {}", key, e.getMessage());
      return key;
    }
  }
}
