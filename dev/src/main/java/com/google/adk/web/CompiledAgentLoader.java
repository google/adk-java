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

package com.google.adk.web;

import com.google.adk.agents.BaseAgent;
import com.google.adk.web.config.AgentLoadingProperties;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * CompiledAgentLoader implementation for the dev environment.
 *
 * <p>This loader scans the configured source directory and treats the source directory or a
 * subdirectory as an "agent unit" containing pre-compiled classes. It does not perform runtime
 * compilation - agents must be pre-compiled. Agents are identified by a public static field named
 * {@code ROOT_AGENT} assignable to {@link BaseAgent}.
 *
 * <p>Example directory structure for path {@code internal/samples}:
 *
 * <pre>
 * agent/samples/
 *   ├── my-chat-agent/
 *   │   ├── MyAgent.class (with ROOT_AGENT field)
 *   │   └── helper/
 *   │       └── HelperClass.class
 *   └── code-assistant/
 *       ├── CodeAssistant.class (with ROOT_AGENT field)
 *       └── tools/
 *           └── CodeTools.class
 * </pre>
 *
 * <p>This loader is thread-safe and uses memoized suppliers to ensure agents are created only once
 * when first requested, improving performance for repeated access.
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>To enable this loader: {@code adk.agents.loader=compiled}
 *   <li>To specify agent source directory: {@code adk.agents.source-dir=/path/to/agents}
 * </ul>
 */
@Service("agentLoader")
@Primary
@ConditionalOnProperty(name = "adk.agents.loader", havingValue = "compiled", matchIfMissing = true)
@ThreadSafe
public class CompiledAgentLoader implements AgentLoader {
  private static final Logger logger = LoggerFactory.getLogger(CompiledAgentLoader.class);

  private final AgentLoadingProperties properties;
  private final ImmutableMap<String, Supplier<BaseAgent>> agentSuppliers;

  public CompiledAgentLoader(AgentLoadingProperties properties) {
    this.properties = properties;
    logger.info("Initializing CompiledAgentLoader for path-based agents");

    Map<String, Supplier<BaseAgent>> allAgents = new HashMap<>();

    // Load path-based agents
    if (properties.getSourceDir() != null && !properties.getSourceDir().isEmpty()) {
      loadPathBasedAgents(allAgents);
    } else {
      logger.warn(
          "No source directory configured (adk.agents.source-dir). No agents will be available.");
    }

    this.agentSuppliers = ImmutableMap.copyOf(allAgents);

    logger.info(
        "CompiledAgentLoader initialized with {} agents: {}",
        agentSuppliers.size(),
        agentSuppliers.keySet());
  }

  @Override
  @Nonnull
  public ImmutableList<String> listAgents() {
    return ImmutableList.copyOf(agentSuppliers.keySet());
  }

  @Override
  public BaseAgent loadAgent(String name) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException("Agent name cannot be null or empty");
    }

    Supplier<BaseAgent> supplier = agentSuppliers.get(name);
    if (supplier == null) {
      logger.warn("Agent not found: {}. Available agents: {}", name, agentSuppliers.keySet());
      throw new NoSuchElementException("Agent not found: " + name);
    }

    try {
      BaseAgent agent = supplier.get();
      logger.debug("Successfully loaded agent: {}", name);
      return agent;
    } catch (Exception e) {
      logger.error("Failed to load agent: {}", name, e);
      throw new IllegalStateException("Agent exists but failed to load: " + name, e);
    }
  }

  /**
   * Loads path-based agents from the configured source directory. Similar to ConfigAgentLoader,
   * scans directories for agent units. Each subdirectory is treated as an agent unit containing
   * pre-compiled classes.
   *
   * <p>Directory structure expected:
   *
   * <pre>
   * source-dir/ (e.g., internal/samples)
   *   ├── agent1/
   *   │   └── compiled classes with ROOT_AGENT field
   *   ├── agent2/
   *   │   └── compiled classes with ROOT_AGENT field
   *   └── ...
   * </pre>
   */
  private void loadPathBasedAgents(Map<String, Supplier<BaseAgent>> agents) {
    Path sourceDir = Paths.get(properties.getSourceDir());
    if (!Files.isDirectory(sourceDir)) {
      logger.warn(
          "Agent source directory does not exist: {}. Skipping path-based agent loading.",
          sourceDir);
      return;
    }

    logger.info("Scanning for agent units in: {}", sourceDir);

    // First check if this directory directly contains a Maven/Gradle project structure
    Path targetClasses = sourceDir.resolve("target/classes");
    if (Files.exists(targetClasses) && Files.isDirectory(targetClasses)) {
      logger.info("Found Maven project structure, scanning target/classes: {}", targetClasses);
      loadAgentsFromDirectory(targetClasses, agents);
    }

    // Also scan for traditional agent unit subdirectories
    try (Stream<Path> entries = Files.list(sourceDir)) {
      entries.forEach(
          entry -> {
            if (Files.isDirectory(entry)) {
              String agentUnitName = entry.getFileName().toString();
              // Skip target directory as it's handled above
              if (!"target".equals(agentUnitName)) {
                loadAgentUnit(entry, agentUnitName, agents);
              }
            }
          });
    } catch (IOException e) {
      logger.error("IO error loading path-based agents from: {}", sourceDir, e);
    } catch (SecurityException e) {
      logger.error("Security error accessing path-based agents from: {}", sourceDir, e);
    } catch (InvalidPathException e) {
      logger.error("Invalid path error loading path-based agents from: {}", sourceDir, e);
    } catch (Exception e) {
      logger.error("Unexpected error loading path-based agents from: {}", sourceDir, e);
    }

    logger.info("Completed path-based agent discovery. Found {} total agents", agents.size());
  }

  /**
   * Loads an agent unit (directory containing pre-compiled agent classes). Similar to how
   * ConfigAgentLoader processes agent directories.
   */
  private void loadAgentUnit(
      Path agentUnitDir, String unitName, Map<String, Supplier<BaseAgent>> agents) {
    try {
      loadAgentsFromDirectory(agentUnitDir, agents);
    } catch (Exception e) {
      logger.error("Error processing agent unit: {}", unitName, e);
    }
  }

  /** Loads agents from a directory containing compiled class files. */
  private void loadAgentsFromDirectory(Path directory, Map<String, Supplier<BaseAgent>> agents) {
    try {
      String agentUnitName = directory.getFileName().toString();

      // If this is already a classes directory (e.g., target/classes), use it directly
      // Otherwise, look for configured build output directories
      final Path classesDir =
          (directory.toString().endsWith("classes") || hasClassFiles(directory))
              ? directory
              : findBuildOutputDir(directory);

      try (URLClassLoader classLoader =
              new URLClassLoader(
                  new URL[] {classesDir.toUri().toURL()},
                  CompiledAgentLoader.class.getClassLoader());
          Stream<Path> classFiles = Files.walk(classesDir)) {
        classFiles
            .filter(p -> p.toString().endsWith(".class"))
            .forEach(
                classFile -> {
                  try {
                    String relativePath = classesDir.relativize(classFile).toString();
                    String className =
                        relativePath
                            .substring(0, relativePath.length() - ".class".length())
                            .replace(File.separatorChar, '.');

                    loadAgentFromClass(className, classLoader, agentUnitName, agents);
                  } catch (Exception e) {
                    logger.warn("Error loading agent from class file: {}", classFile, e);
                  }
                });
      }
    } catch (IOException e) {
      logger.error("IO error loading agents from directory: {}", directory, e);
    } catch (SecurityException e) {
      logger.error("Security error accessing directory: {}", directory, e);
    } catch (Exception e) {
      logger.error("Unexpected error loading agents from directory: {}", directory, e);
    }
  }

  /** Checks if directory contains .class files directly. */
  private boolean hasClassFiles(Path directory) {
    try (Stream<Path> files = Files.list(directory)) {
      return files.anyMatch(p -> p.toString().endsWith(".class"));
    } catch (IOException e) {
      logger.debug("IO error checking for class files in directory: {}", directory, e);
      return false;
    } catch (SecurityException e) {
      logger.debug("Security error accessing directory: {}", directory, e);
      return false;
    }
  }

  /**
   * Finds the first existing build output directory from the configured list. Falls back to the
   * directory itself if none of the build output directories exist.
   */
  private Path findBuildOutputDir(Path directory) {
    for (String buildDir : properties.getBuildOutputDirs()) {
      Path candidateDir = directory.resolve(buildDir);
      if (Files.exists(candidateDir) && Files.isDirectory(candidateDir)) {
        logger.debug("Found build output directory: {} in {}", buildDir, directory);
        return candidateDir;
      }
    }
    logger.debug(
        "No configured build output directories found in {}, using directory itself", directory);
    return directory;
  }

  /** Attempts to load an agent from a specific class using reflection. */
  private void loadAgentFromClass(
      String className,
      ClassLoader classLoader,
      String unitName,
      Map<String, Supplier<BaseAgent>> agents) {
    try {
      Class<?> loadedClass = classLoader.loadClass(className);
      final Field rootAgentField;

      try {
        rootAgentField = loadedClass.getField("ROOT_AGENT");
      } catch (NoSuchFieldException e) {
        // This class doesn't have a ROOT_AGENT field, skip it
        return;
      }

      if (Modifier.isStatic(rootAgentField.getModifiers())
          && BaseAgent.class.isAssignableFrom(rootAgentField.getType())) {

        // Create a supplier that loads the agent when needed
        Supplier<BaseAgent> agentSupplier =
            Suppliers.memoize(
                () -> {
                  try {
                    BaseAgent agentInstance = (BaseAgent) rootAgentField.get(null);
                    if (agentInstance != null) {
                      logger.info(
                          "Loaded path-based agent '{}' from class {} in unit {}",
                          agentInstance.name(),
                          className,
                          unitName);
                      return agentInstance;
                    } else {
                      logger.warn("ROOT_AGENT field in class {} was null", className);
                      throw new IllegalStateException("ROOT_AGENT field was null");
                    }
                  } catch (IllegalAccessException e) {
                    logger.error("Cannot access ROOT_AGENT field in class {}", className, e);
                    throw new RuntimeException("Cannot access ROOT_AGENT field", e);
                  } catch (Exception e) {
                    logger.error("Error initializing agent from class {}", className, e);
                    throw new RuntimeException("Agent initialization failed", e);
                  }
                });

        // We need to get the agent name, so we'll need to load it once to get the name
        try {
          BaseAgent tempAgent = (BaseAgent) rootAgentField.get(null);
          if (tempAgent != null) {
            String agentName = tempAgent.name();
            if (agents.containsKey(agentName)) {
              logger.warn(
                  "Found duplicate agent name '{}'. Path-based agent from {} will overwrite"
                      + " existing agent.",
                  agentName,
                  unitName);
            }
            agents.put(agentName, agentSupplier);
            logger.debug("Registered path-based agent '{}' from class {}", agentName, className);
          }
        } catch (IllegalAccessException e) {
          logger.error(
              "Cannot access ROOT_AGENT field in class {} to get agent name", className, e);
        } catch (Exception e) {
          logger.error("Error getting agent name from class {}", className, e);
        }
      }
    } catch (ClassNotFoundException e) {
      logger.warn("Could not load class {} from unit {}", className, unitName);
    } catch (NoClassDefFoundError e) {
      logger.warn(
          "Class definition error loading {} from unit {}: {}",
          className,
          unitName,
          e.getMessage());
    } catch (LinkageError e) {
      logger.warn(
          "Linkage error loading class {} from unit {}: {}", className, unitName, e.getMessage());
    } catch (SecurityException e) {
      logger.warn(
          "Security error accessing class {} from unit {}: {}",
          className,
          unitName,
          e.getMessage());
    } catch (ClassCastException e) {
      logger.warn(
          "Class {} from unit {} is not a BaseAgent: {}", className, unitName, e.getMessage());
    } catch (Exception e) {
      logger.error(
          "Unexpected error loading agent from class {} in unit {}", className, unitName, e);
    }
  }
}
