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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class YamlPreprocessorTest {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  @Test
  public void testSimpleSnakeCaseToCamelCaseConversion() throws Exception {
    String input =
        """
        test_field: value1
        another_test_field: value2
        already_camelCase: value3
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("testField"));
    assertTrue(parsed.containsKey("anotherTestField"));
    assertEquals("value1", parsed.get("testField"));
    assertEquals("value2", parsed.get("anotherTestField"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testNestedObjectConversion() throws Exception {
    String input =
        """
        name: test_agent
        model: gemini-2.0-flash
        disallow_transfer_to_parent: false
        disallow_transfer_to_peers: true
        generate_content_config:
          temperature: 0.1
          max_output_tokens: 2000
          top_k: 40
          top_p: 0.95
          response_mime_type: text/plain
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("disallowTransferToParent"));
    assertTrue(parsed.containsKey("disallowTransferToPeers"));
    assertTrue(parsed.containsKey("generateContentConfig"));
    Map<String, Object> generateConfig = (Map<String, Object>) parsed.get("generateContentConfig");
    assertNotNull(generateConfig);
    assertTrue(generateConfig.containsKey("maxOutputTokens"));
    assertTrue(generateConfig.containsKey("topK"));
    assertTrue(generateConfig.containsKey("topP"));
    assertTrue(generateConfig.containsKey("responseMimeType"));
    assertEquals(2000, generateConfig.get("maxOutputTokens"));
    assertEquals(40, generateConfig.get("topK"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testListWithNestedMaps() throws Exception {
    String input =
        """
        safety_settings:
          - harm_category: DANGEROUS_CONTENT
            block_threshold: HIGH
          - harm_category: HATE_SPEECH
            block_threshold: MEDIUM
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("safetySettings"));
    Object safetySettings = parsed.get("safetySettings");
    assertTrue(safetySettings instanceof List);

    List<?> settingsList = (List<?>) safetySettings;
    assertEquals(2, settingsList.size());

    Map<String, Object> firstSetting = (Map<String, Object>) settingsList.get(0);
    assertTrue(firstSetting.containsKey("harmCategory"));
    assertTrue(firstSetting.containsKey("blockThreshold"));
    assertEquals("DANGEROUS_CONTENT", firstSetting.get("harmCategory"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testDeeplyNestedStructures() throws Exception {
    String input =
        """
        response_schema:
          type: object
          properties:
            field_name:
              type: string
              min_length: 1
              max_length: 100
            nested_object:
              type: object
              properties:
                inner_field:
                  type: integer
                  min_value: 0
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("responseSchema"));
    Map<String, Object> responseSchema = (Map<String, Object>) parsed.get("responseSchema");

    Map<String, Object> properties = (Map<String, Object>) responseSchema.get("properties");
    assertTrue(properties.containsKey("fieldName"));
    assertTrue(properties.containsKey("nestedObject"));

    Map<String, Object> fieldName = (Map<String, Object>) properties.get("fieldName");
    assertTrue(fieldName.containsKey("minLength"));
    assertTrue(fieldName.containsKey("maxLength"));

    Map<String, Object> nestedObject = (Map<String, Object>) properties.get("nestedObject");
    Map<String, Object> nestedProps = (Map<String, Object>) nestedObject.get("properties");
    assertTrue(nestedProps.containsKey("innerField"));

    Map<String, Object> innerField = (Map<String, Object>) nestedProps.get("innerField");
    assertTrue(innerField.containsKey("minValue"));
  }

  @Test
  public void testEmptyAndNullHandling() {
    String result = YamlPreprocessor.preprocessYaml(null);
    assertEquals(null, result);

    result = YamlPreprocessor.preprocessYaml("");
    assertEquals("", result);

    result = YamlPreprocessor.preprocessYaml("   \n   ");
    assertEquals("   \n   ", result);
  }

  @Test
  public void testWhitespaceOnlyStringsReturnAsIs() {
    // Test various whitespace-only strings
    String spacesOnly = "     ";
    String result = YamlPreprocessor.preprocessYaml(spacesOnly);
    assertEquals(spacesOnly, result);

    String tabsOnly = "\t\t\t";
    result = YamlPreprocessor.preprocessYaml(tabsOnly);
    assertEquals(tabsOnly, result);

    String newlinesOnly = "\n\n\n";
    result = YamlPreprocessor.preprocessYaml(newlinesOnly);
    assertEquals(newlinesOnly, result);

    String mixedWhitespace = "  \t \n  \r\n  ";
    result = YamlPreprocessor.preprocessYaml(mixedWhitespace);
    assertEquals(mixedWhitespace, result);

    // Test a string that becomes empty after trimming
    String whitespaceWrapped = "   \n\t  \r\n  ";
    result = YamlPreprocessor.preprocessYaml(whitespaceWrapped);
    assertEquals(whitespaceWrapped, result);
  }

  @Test
  public void testAlreadyCamelCasePreservation() throws Exception {
    String input =
        """
        alreadyCamelCase: value1
        mixedCase_with_underscore: value2
        normalCase: value3
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("alreadyCamelCase"));
    assertEquals("value1", parsed.get("alreadyCamelCase"));

    assertTrue(parsed.containsKey("mixedcaseWithUnderscore"));
    assertEquals("value2", parsed.get("mixedcaseWithUnderscore"));

    assertTrue(parsed.containsKey("normalCase"));
    assertEquals("value3", parsed.get("normalCase"));
  }

  @Test
  public void testUpperCaseConversion() throws Exception {
    // Test that UPPER_CASE is properly converted to camelCase
    String input =
        """
        UPPER_CASE_FIELD: value1
        ANOTHER_UPPER: value2
        MiXeD_CASE: value3
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("upperCaseField"));
    assertTrue(parsed.containsKey("anotherUpper"));
    assertTrue(parsed.containsKey("mixedCase"));
    assertEquals("value1", parsed.get("upperCaseField"));
    assertEquals("value2", parsed.get("anotherUpper"));
    assertEquals("value3", parsed.get("mixedCase"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testListOfMapsWithSnakeCaseFields() throws Exception {
    // Test the example from the documentation: list of server configs
    String input =
        """
        server_configs:
          - server_name: prod
            max_connections: 100
            retry_config:
              max_retries: 3
              backoff_ms: 1000
          - server_name: dev
            max_connections: 50
            retry_config:
              max_retries: 5
              backoff_ms: 500
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertTrue(parsed.containsKey("serverConfigs"));
    List<?> configs = (List<?>) parsed.get("serverConfigs");
    assertEquals(2, configs.size());

    Map<String, Object> prodConfig = (Map<String, Object>) configs.get(0);
    assertTrue(prodConfig.containsKey("serverName"));
    assertTrue(prodConfig.containsKey("maxConnections"));
    assertTrue(prodConfig.containsKey("retryConfig"));
    assertEquals("prod", prodConfig.get("serverName"));

    Map<String, Object> retryConfig = (Map<String, Object>) prodConfig.get("retryConfig");
    assertTrue(retryConfig.containsKey("maxRetries"));
    assertTrue(retryConfig.containsKey("backoffMs"));
    assertEquals(3, retryConfig.get("maxRetries"));
  }

  @Test
  public void testInvalidYamlHandling() {
    // Test that invalid YAML returns the original content
    String invalidYaml = "this is not: valid yaml: at all: : :";
    String result = YamlPreprocessor.preprocessYaml(invalidYaml);
    assertEquals(invalidYaml, result);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMcpToolsetConfigExample() throws Exception {
    // Test a real-world example: MCP toolset configuration
    String input =
        """
        tools:
          - name: McpToolset
            args:
              stdio_server_params:
                command: test-command
                args: ["--foo", "bar"]
              tool_filter: ["tool1", "tool2"]
          - name: AnotherTool
            args:
              sse_server_params:
                url: http://localhost:8080
                sse_endpoint: /events
                sse_read_timeout: 5000
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    List<?> tools = (List<?>) parsed.get("tools");
    Map<String, Object> firstTool = (Map<String, Object>) tools.get(0);
    Map<String, Object> args = (Map<String, Object>) firstTool.get("args");

    assertTrue(args.containsKey("stdioServerParams"));
    assertTrue(args.containsKey("toolFilter"));

    Map<String, Object> stdioParams = (Map<String, Object>) args.get("stdioServerParams");
    assertEquals("test-command", stdioParams.get("command"));

    Map<String, Object> secondTool = (Map<String, Object>) tools.get(1);
    Map<String, Object> args2 = (Map<String, Object>) secondTool.get("args");
    assertTrue(args2.containsKey("sseServerParams"));

    Map<String, Object> sseParams = (Map<String, Object>) args2.get("sseServerParams");
    assertTrue(sseParams.containsKey("sseEndpoint"));
    assertTrue(sseParams.containsKey("sseReadTimeout"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testCompleteAgentConfigExample() throws Exception {
    String input =
        """
        name: search_agent
        model: gemini-2.0-flash
        disallow_transfer_to_parent: false
        disallow_transfer_to_peers: true
        system_prompt: You are a helpful assistant
        generate_content_config:
          temperature: 0.1
          max_output_tokens: 2000
          top_k: 40
          top_p: 0.95
          candidate_count: 1
          stop_sequences:
            - END
            - STOP
          response_mime_type: application/json
          response_schema:
            type: object
            properties:
              answer_text:
                type: string
              confidence_score:
                type: number
        safety_settings:
          - harm_category: DANGEROUS_CONTENT
            block_threshold: HIGH
        """;

    String result = YamlPreprocessor.preprocessYaml(input);

    Map<String, Object> parsed =
        YAML_MAPPER.readValue(result, new TypeReference<Map<String, Object>>() {});

    assertEquals("search_agent", parsed.get("name"));
    assertEquals("gemini-2.0-flash", parsed.get("model"));
    assertTrue(parsed.containsKey("disallowTransferToParent"));
    assertTrue(parsed.containsKey("disallowTransferToPeers"));
    assertTrue(parsed.containsKey("systemPrompt"));
    assertTrue(parsed.containsKey("generateContentConfig"));
    assertTrue(parsed.containsKey("safetySettings"));
    Map<String, Object> genConfig = (Map<String, Object>) parsed.get("generateContentConfig");
    assertTrue(genConfig.containsKey("maxOutputTokens"));
    assertTrue(genConfig.containsKey("topK"));
    assertTrue(genConfig.containsKey("topP"));
    assertTrue(genConfig.containsKey("candidateCount"));
    assertTrue(genConfig.containsKey("stopSequences"));
    assertTrue(genConfig.containsKey("responseMimeType"));
    assertTrue(genConfig.containsKey("responseSchema"));
    Map<String, Object> responseSchema = (Map<String, Object>) genConfig.get("responseSchema");
    Map<String, Object> properties = (Map<String, Object>) responseSchema.get("properties");
    assertTrue(properties.containsKey("answerText"));
    assertTrue(properties.containsKey("confidenceScore"));
  }
}
