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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for loading agent configurations from YAML files.
 *
 * <p>TODO: Config agent features are not yet ready for public use.
 */
public final class ConfigAgentUtils {

  private static final Logger logger = LoggerFactory.getLogger(ConfigAgentUtils.class);

  private ConfigAgentUtils() {}

  /**
   * Convert snake_case or kebab-case tool names to PascalCase Java class names.
   *
   * @param toolName the tool name to convert
   * @return the converted tool name in PascalCase
   */
  public static String convertToPascalCase(String toolName) {
    if (isNullOrEmpty(toolName)) {
      return toolName;
    }

    StringBuilder result = new StringBuilder();
    boolean capitalizeNext = true;

    for (int i = 0; i < toolName.length(); i++) {
      char c = toolName.charAt(i);
      if (c == '_' || c == '-') {
        capitalizeNext = true;
      } else if (capitalizeNext) {
        result.append(Character.toUpperCase(c));
        capitalizeNext = false;
      } else {
        result.append(Character.toLowerCase(c));
      }
    }

    return result.toString();
  }

  /**
   * Load agent from a YAML config file path.
   *
   * @param configPath the path to a YAML config file
   * @return the created agent instance as a {@link BaseAgent}
   * @throws ConfigurationException if loading fails
   */
  public static BaseAgent fromConfig(String configPath) throws ConfigurationException {

    File configFile = new File(configPath);
    if (!configFile.exists()) {
      logger.error("Config file not found: {}", configPath);
      throw new ConfigurationException("Config file not found: " + configPath);
    }

    String absolutePath = configFile.getAbsolutePath();

    try {
      // Load the base config to determine the agent class
      BaseAgentConfig baseConfig = loadConfigAsType(absolutePath, BaseAgentConfig.class);
      Class<? extends BaseAgent> agentClass = resolveAgentClass(baseConfig.agentClass());

      // Load the config file with the specific config class
      Class<? extends BaseAgentConfig> configClass = getConfigClassForAgent(agentClass);
      BaseAgentConfig config = loadConfigAsType(absolutePath, configClass);
      logger.info("agentClass value = '{}'", config.agentClass());

      // Use reflection to call the fromConfig method with the correct types
      java.lang.reflect.Method fromConfigMethod =
          agentClass.getDeclaredMethod("fromConfig", configClass, String.class);
      return (BaseAgent) fromConfigMethod.invoke(null, config, absolutePath);

    } catch (ConfigurationException e) {
      throw e;
    } catch (Exception e) {
      throw new ConfigurationException("Failed to create agent from config: " + configPath, e);
    }
  }

  /**
   * Load configuration from a YAML file path as a specific type.
   *
   * @param configPath the absolute path to the config file
   * @param configClass the class to deserialize the config into
   * @return the loaded configuration
   * @throws ConfigurationException if loading fails
   */
  private static <T extends BaseAgentConfig> T loadConfigAsType(
      String configPath, Class<T> configClass) throws ConfigurationException {
    try (InputStream inputStream = new FileInputStream(configPath)) {
      ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
      return mapper.readValue(inputStream, configClass);
    } catch (IOException e) {
      throw new ConfigurationException("Failed to load or parse config file: " + configPath, e);
    }
  }

  /**
   * Resolves the agent class based on the agent class name from the configuration.
   *
   * @param agentClassName the name of the agent class from the config
   * @return the corresponding agent class
   * @throws ConfigurationException if the agent class is not supported
   */
  private static Class<? extends BaseAgent> resolveAgentClass(String agentClassName)
      throws ConfigurationException {
    // If no agent_class is specified in the yaml file, it will default to LlmAgent.
    if (isNullOrEmpty(agentClassName) || agentClassName.equals("LlmAgent")) {
      return LlmAgent.class;
    }

    // TODO: Support more agent classes
    // Example for future extensions:
    // if (agentClassName.equals("CustomAgent")) {
    //   return CustomAgent.class;
    // }

    throw new ConfigurationException(
        "agentClass '"
            + agentClassName
            + "' is not supported. It must be a subclass of BaseAgent.");
  }

  /**
   * Maps agent classes to their corresponding config classes.
   *
   * @param agentClass the agent class
   * @return the corresponding config class
   */
  private static Class<? extends BaseAgentConfig> getConfigClassForAgent(
      Class<? extends BaseAgent> agentClass) {

    if (agentClass == LlmAgent.class) {
      return LlmAgentConfig.class;
    }

    // TODO: Add more agent class to config class mappings as needed
    // Example:
    // if (agentClass == CustomAgent.class) {
    //   return CustomAgentConfig.class;
    // }

    // Default fallback to BaseAgentConfig
    return BaseAgentConfig.class;
  }

  /** Exception thrown when configuration is invalid. */
  public static class ConfigurationException extends Exception {
    public ConfigurationException(String message) {
      super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
