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

package com.google.adk.maven;

import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ConfigAgentUtils;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration-based AgentLoader that loads agents from YAML configuration files.
 *
 * <p>This loader monitors a configured source directory for folders containing `root_agent.yaml`
 * files and automatically reloads agents when the files change (if hot-reloading is enabled).
 *
 * <p>The loader treats each subdirectory with a `root_agent.yaml` file as an agent, using the
 * folder name as the agent identifier. Agents are loaded lazily when first requested.
 *
 * <p>Directory structure expected:
 *
 * <pre>
 * source-dir/
 *   ├── agent1/
 *   │   └── root_agent.yaml
 *   ├── agent2/
 *   │   └── root_agent.yaml
 *   └── ...
 * </pre>
 *
 * <p>Hot-reloading can be disabled by setting hotReloadingEnabled to false.
 *
 * <p>TODO: Config agent features are not yet ready for public use.
 */
@ThreadSafe
class ConfigAgentLoader implements AgentLoader {
  private static final Logger logger = LoggerFactory.getLogger(ConfigAgentLoader.class);
  private static final String YAML_CONFIG_FILENAME = "root_agent.yaml";

  private final boolean hotReloadingEnabled;
  private final String sourceDir;
  private final Map<String, Supplier<BaseAgent>> agentSuppliers = new ConcurrentHashMap<>();
  private final ConfigAgentWatcher watcher;
  private volatile boolean started = false;

  /**
   * Creates a new ConfigAgentLoader.
   *
   * @param sourceDir The directory to scan for agent configuration files
   * @param hotReloadingEnabled Controls whether hot-reloading is enabled
   */
  public ConfigAgentLoader(String sourceDir, boolean hotReloadingEnabled) {
    this.sourceDir = sourceDir;
    this.hotReloadingEnabled = hotReloadingEnabled;
    this.watcher = hotReloadingEnabled ? new ConfigAgentWatcher() : null;

    try {
      autoDiscoverTools();
      discoverAgents();
      if (hotReloadingEnabled) {
        start();
      }
    } catch (IOException e) {
      logger.error("Failed to initialize ConfigAgentLoader", e);
    }
  }

  /**
   * Registers a specific static field or method as a tool.
   *
   * @return true if successfully registered, false otherwise
   */
  private boolean registerSpecificMember(
      String toolRef, ClassLoader classLoader, ComponentRegistry registry) {
    try {
      int lastDotIndex = toolRef.lastIndexOf('.');
      String className = toolRef.substring(0, lastDotIndex);
      String memberName = toolRef.substring(lastDotIndex + 1);

      Class<?> toolClass = classLoader.loadClass(className);

      try {
        Field field = toolClass.getDeclaredField(memberName);
        field.setAccessible(true);
        Object toolInstance = field.get(null);
        if (toolInstance instanceof FunctionTool) {
          registry.register(toolRef, (FunctionTool) toolInstance);
          logger.info("Registered static field tool: {}", toolRef);
          return true;
        }
      } catch (NoSuchFieldException e) {
        // Field not found, continue to method check
      }

      Method[] methods = toolClass.getDeclaredMethods();
      for (Method method : methods) {
        if (method.getName().equals(memberName)
            && method.isAnnotationPresent(com.google.adk.tools.Annotations.Schema.class)) {
          FunctionTool functionTool = FunctionTool.create(toolClass, memberName);
          registry.register(toolRef, functionTool);
          logger.info("Registered @Schema method tool: {} -> {}", toolRef, memberName);
          return true;
        }
      }

      return false;

    } catch (ClassNotFoundException e) {
      logger.warn("Failed to load class for tool reference: {}", toolRef);
    } catch (Exception e) {
      logger.warn("Failed to register specific member tool: {}", toolRef);
    }
    return false;
  }

  /**
   * Registers all @Schema annotated methods in a class as FunctionTools.
   *
   * @return true if any methods were registered, false otherwise
   */
  private boolean registerAllMethodsInClass(
      String className, ClassLoader classLoader, ComponentRegistry registry) {
    try {
      Class<?> toolClass = classLoader.loadClass(className);

      Method[] methods = toolClass.getDeclaredMethods();
      int methodsRegistered = 0;

      for (Method method : methods) {
        if (method.isAnnotationPresent(com.google.adk.tools.Annotations.Schema.class)) {
          FunctionTool functionTool = FunctionTool.create(toolClass, method.getName());
          String toolName = className + "." + method.getName();
          registry.register(toolName, functionTool);
          logger.info("Registered method tool: {} -> {}", toolName, method.getName());
          methodsRegistered++;
        }
      }

      if (methodsRegistered > 0) {
        logger.info(
            "Registered class tool (all methods): {} with {} methods",
            className,
            methodsRegistered);
        return true;
      } else {
        return false;
      }
    } catch (Exception e) {
      logger.warn("Failed to register class tool: {}", className);
    }
    return false;
  }

  /**
   * Attempts to register a tool using multiple strategies.
   *
   * @param toolRef The tool reference from YAML
   * @param classLoader The class loader to use
   * @param registry The component registry to register with
   * @return true if successfully registered, false otherwise
   */
  private boolean tryRegisterTool(
      String toolRef, ClassLoader classLoader, ComponentRegistry registry) {
    // Try as member (field or method) reference first if it looks like one
    if (toolRef.contains(".") && !toolRef.endsWith(".")) {
      try {
        if (registerSpecificMember(toolRef, classLoader, registry)) {
          logger.info("Successfully registered as member tool: {}", toolRef);
          return true;
        }
      } catch (Exception e) {
        logger.debug("Member registration failed for {}: {}", toolRef, e.getMessage());
      }
    }

    // Try as class reference
    try {
      if (registerAllMethodsInClass(toolRef, classLoader, registry)) {
        logger.info("Successfully registered as class tool: {}", toolRef);
        return true;
      }
    } catch (Exception e) {
      logger.debug("Class registration failed for {}: {}", toolRef, e.getMessage());
    }

    return false;
  }

  /**
   * Discovers tools automatically from the current classpath.
   *
   * <p>This method scans all agent YAML files to extract tool references and attempts to register
   * them as tools.
   */
  private void autoDiscoverTools() {
    logger.info("Starting tool auto-discovery");

    try {
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      ComponentRegistry registry = ComponentRegistry.getInstance();

      Set<String> toolReferences = extractToolReferencesForAllAgentsFromYaml();
      logger.info("Found tool references: {}", toolReferences);

      int toolsRegistered = 0;
      for (String toolRef : toolReferences) {
        logger.info("Processing tool reference: {}", toolRef);

        if (tryRegisterTool(toolRef, classLoader, registry)) {
          toolsRegistered++;
        } else {
          logger.warn("Failed to register tool using any pattern: {}", toolRef);
        }
      }

      logger.info(
          "Tool auto-discovery completed: {} tool references processed successfully",
          toolsRegistered);
    } catch (Exception e) {
      logger.error("Tool auto-discovery failed", e);
    }
  }

  /** Scans all agent YAML files to extract tool references. */
  private Set<String> extractToolReferencesForAllAgentsFromYaml() throws IOException {
    Set<String> toolReferences = new HashSet<>();

    if (sourceDir == null || sourceDir.isEmpty()) {
      return toolReferences;
    }

    Path sourcePath = Paths.get(sourceDir);
    if (!Files.isDirectory(sourcePath)) {
      return toolReferences;
    }

    try (Stream<Path> entries = Files.list(sourcePath)) {
      for (Path agentDir : entries.collect(toList())) {
        if (Files.isDirectory(agentDir)) {
          Path yamlConfigPath = agentDir.resolve(YAML_CONFIG_FILENAME);
          if (Files.exists(yamlConfigPath) && Files.isRegularFile(yamlConfigPath)) {
            extractToolReferencesFromYaml(yamlConfigPath, toolReferences);
          }
        }
      }
    }

    return toolReferences;
  }

  /** Extracts tool references from a YAML file using Jackson YAML parser. */
  private void extractToolReferencesFromYaml(Path yamlPath, Set<String> toolReferences) {
    try {
      ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
      JsonNode rootNode = yamlMapper.readTree(yamlPath.toFile());

      if (rootNode == null) {
        return;
      }

      JsonNode toolsNode = rootNode.get("tools");
      if (toolsNode != null && toolsNode.isArray()) {
        for (JsonNode toolNode : toolsNode) {
          if (toolNode != null) {
            JsonNode nameNode = toolNode.get("name");
            if (nameNode != null && nameNode.isTextual()) {
              String toolName = nameNode.asText().trim();
              if (!toolName.isEmpty()) {
                toolReferences.add(toolName);
              }
            }
          }
        }
      }
    } catch (IOException e) {
      logger.warn("Failed to parse YAML file: {} - {}", yamlPath, e.getMessage());
    } catch (Exception e) {
      logger.warn("Unexpected error parsing YAML file: {} - {}", yamlPath, e.getMessage());
    }
  }

  /**
   * Creates a new ConfigAgentLoader with hot-reloading enabled.
   *
   * @param sourceDir The directory to scan for agent configuration files
   */
  public ConfigAgentLoader(String sourceDir) {
    this(sourceDir, true);
  }

  @Override
  @Nonnull
  public ImmutableList<String> listAgents() {
    return ImmutableList.copyOf(agentSuppliers.keySet());
  }

  @Override
  public BaseAgent loadAgent(String name) {
    Supplier<BaseAgent> supplier = agentSuppliers.get(name);
    if (supplier == null) {
      throw new NoSuchElementException("Agent not found: " + name);
    }
    return supplier.get();
  }

  /**
   * Discovers available agents from the configured source directory and creates suppliers for them.
   *
   * @throws IOException if there's an error accessing the source directory
   */
  private void discoverAgents() throws IOException {
    if (sourceDir == null || sourceDir.isEmpty()) {
      logger.info(
          "Agent source directory not configured. ConfigAgentLoader will not discover any agents.");
      return;
    }

    Path sourcePath = Paths.get(sourceDir);
    if (!Files.isDirectory(sourcePath)) {
      logger.warn(
          "Agent source directory does not exist: {}. ConfigAgentLoader will not discover any"
              + " agents.",
          sourcePath);
      return;
    }

    logger.info("Initial scan for YAML agents in: {}", sourcePath);

    try (Stream<Path> entries = Files.list(sourcePath)) {
      for (Path agentDir : entries.collect(toList())) {
        if (Files.isDirectory(agentDir)) {
          Path yamlConfigPath = agentDir.resolve(YAML_CONFIG_FILENAME);
          if (Files.exists(yamlConfigPath) && Files.isRegularFile(yamlConfigPath)) {
            // Use the folder name as the agent identifier
            String agentName = agentDir.getFileName().toString();
            logger.debug("Discovering YAML agent config: {}", yamlConfigPath);

            if (agentSuppliers.containsKey(agentName)) {
              logger.warn(
                  "Duplicate agent name '{}' found in {}. Overwriting.", agentName, yamlConfigPath);
            }
            // Create a memoized supplier that will load the agent only when requested
            agentSuppliers.put(
                agentName, Suppliers.memoize(() -> loadAgentFromPath(yamlConfigPath)));

            // Register with watcher if hot-reloading is enabled
            if (hotReloadingEnabled && watcher != null) {
              watcher.watch(agentDir, agentDirPath -> updateAgentSupplier(agentDirPath));
            }

            logger.info("Discovered YAML agent '{}' from: {}", agentName, yamlConfigPath);
          }
        }
      }
    }

    logger.info("Initial YAML agent discovery complete. Found {} agents.", agentSuppliers.size());
  }

  /**
   * Updates the agent supplier when a configuration changes.
   *
   * @param agentDirPath The path to the agent configuration directory
   */
  private void updateAgentSupplier(Path agentDirPath) {
    String agentName = agentDirPath.getFileName().toString();
    Path yamlConfigPath = agentDirPath.resolve(YAML_CONFIG_FILENAME);

    if (Files.exists(yamlConfigPath)) {
      // File exists - create/update supplier
      agentSuppliers.put(agentName, Suppliers.memoize(() -> loadAgentFromPath(yamlConfigPath)));
      logger.info("Updated YAML agent supplier '{}' from: {}", agentName, yamlConfigPath);
    } else {
      // File deleted - remove supplier
      agentSuppliers.remove(agentName);
      logger.info("Removed YAML agent '{}' due to deleted config file", agentName);
    }
  }

  /**
   * Loads an agent from the specified config path.
   *
   * @param yamlConfigPath The path to the YAML configuration file
   * @return The loaded BaseAgent
   * @throws RuntimeException if loading fails
   */
  private BaseAgent loadAgentFromPath(Path yamlConfigPath) {
    try {
      logger.debug("Loading YAML agent from: {}", yamlConfigPath);
      BaseAgent agent = ConfigAgentUtils.fromConfig(yamlConfigPath.toString());
      logger.info("Successfully loaded YAML agent '{}' from: {}", agent.name(), yamlConfigPath);
      return agent;
    } catch (Exception e) {
      logger.error("Failed to load YAML agent from: {}", yamlConfigPath, e);
      throw new RuntimeException("Failed to load agent from: " + yamlConfigPath, e);
    }
  }

  /**
   * Starts the hot-loading service. Sets up file watching.
   *
   * @throws IOException if there's an error accessing the source directory
   */
  private synchronized void start() throws IOException {
    if (!hotReloadingEnabled || watcher == null) {
      logger.info(
          "Hot-reloading is disabled. YAML agents will be loaded once at startup and will not be"
              + " monitored for changes.");
      return;
    }

    if (started) {
      logger.warn("ConfigAgentLoader is already started");
      return;
    }

    logger.info("Starting ConfigAgentLoader with file watching");
    watcher.start();
    started = true;
    logger.info("ConfigAgentLoader started successfully with {} agents.", agentSuppliers.size());
  }

  /** Stops the hot-loading service. */
  public synchronized void stop() {
    if (!started) {
      return;
    }

    logger.info("Stopping ConfigAgentLoader...");
    if (watcher != null) {
      watcher.stop();
    }
    started = false;
    logger.info("ConfigAgentLoader stopped.");
  }
}
