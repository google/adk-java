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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ConfigAgentUtils;
import com.google.adk.web.AgentLoader;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * <p>This class is designed to be extensible. Subclasses can override protected methods to
 * customize behavior such as:
 *
 * <ul>
 *   <li>{@link #discoverAgents()} - Custom agent discovery (e.g., flat directory structure)
 *   <li>{@link #loadAgentFromPath(Path)} - Custom loading logic with validation
 *   <li>{@link #updateAgentSupplier(Path)} - Cascading reload, validation before reload
 *   <li>{@link #createWatcher()} - Custom watcher implementation
 * </ul>
 *
 * <p>Inspired by Python ADK's AgentLoader patterns for extensibility and caching.
 */
@ThreadSafe
public class ConfigAgentLoader implements AgentLoader {
  private static final Logger logger = LoggerFactory.getLogger(ConfigAgentLoader.class);

  /** Default YAML config filename. Subclasses can override via constructor. */
  protected static final String DEFAULT_YAML_CONFIG_FILENAME = "root_agent.yaml";

  /** Whether hot-reloading is enabled. */
  protected final boolean hotReloadingEnabled;

  /** The source directory to scan for agents. */
  protected final String sourceDir;

  /** The YAML config filename to look for. */
  protected final String yamlConfigFilename;

  /** Map of agent names to their lazy-loading suppliers. */
  protected final Map<String, Supplier<BaseAgent>> agentSuppliers = new ConcurrentHashMap<>();

  /** Set of agents that failed to load. */
  protected final Set<String> failedAgents = ConcurrentHashMap.newKeySet();

  /** The file watcher for hot-reloading. */
  protected final ConfigAgentWatcher watcher;

  /** Whether the loader has been started. */
  protected volatile boolean started = false;

  /** Callback invoked when agents are reloaded. */
  @Nullable protected Consumer<Set<String>> onAgentsReloadedCallback;

  /**
   * Creates a new ConfigAgentLoader with hot-reloading enabled.
   *
   * @param sourceDir The directory to scan for agent configuration files
   */
  public ConfigAgentLoader(String sourceDir) {
    this(sourceDir, true);
  }

  /**
   * Creates a new ConfigAgentLoader.
   *
   * @param sourceDir The directory to scan for agent configuration files
   * @param hotReloadingEnabled Controls whether hot-reloading is enabled
   */
  public ConfigAgentLoader(String sourceDir, boolean hotReloadingEnabled) {
    this(sourceDir, hotReloadingEnabled, DEFAULT_YAML_CONFIG_FILENAME);
  }

  /**
   * Creates a new ConfigAgentLoader with custom YAML filename.
   *
   * @param sourceDir The directory to scan for agent configuration files
   * @param hotReloadingEnabled Controls whether hot-reloading is enabled
   * @param yamlConfigFilename The YAML config filename to look for
   */
  public ConfigAgentLoader(
      String sourceDir, boolean hotReloadingEnabled, String yamlConfigFilename) {
    this.sourceDir = sourceDir;
    this.hotReloadingEnabled = hotReloadingEnabled;
    this.yamlConfigFilename = yamlConfigFilename;
    this.watcher = hotReloadingEnabled ? createWatcher() : null;

    try {
      discoverAgents();
      if (hotReloadingEnabled && watcher != null) {
        start();
      }
    } catch (IOException e) {
      logger.error("Failed to initialize ConfigAgentLoader", e);
    }
  }

  /**
   * Creates a new ConfigAgentLoader with a custom watcher.
   *
   * <p>This constructor is designed for subclasses that need to inject a custom watcher
   * implementation (e.g., with cascading reload or validation).
   *
   * @param sourceDir The directory to scan for agent configuration files
   * @param hotReloadingEnabled Controls whether hot-reloading is enabled
   * @param customWatcher Custom watcher instance (can be null if hot-reloading disabled)
   */
  protected ConfigAgentLoader(
      String sourceDir, boolean hotReloadingEnabled, ConfigAgentWatcher customWatcher) {
    this.sourceDir = sourceDir;
    this.hotReloadingEnabled = hotReloadingEnabled;
    this.yamlConfigFilename = DEFAULT_YAML_CONFIG_FILENAME;
    this.watcher = customWatcher;

    try {
      discoverAgents();
      if (hotReloadingEnabled && watcher != null) {
        start();
      }
    } catch (IOException e) {
      logger.error("Failed to initialize ConfigAgentLoader", e);
    }
  }

  /**
   * Creates the ConfigAgentWatcher instance.
   *
   * <p>Subclasses can override this to provide a custom watcher implementation.
   *
   * @return A new ConfigAgentWatcher instance
   */
  protected ConfigAgentWatcher createWatcher() {
    return new ConfigAgentWatcher();
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
   * Checks if an agent exists.
   *
   * @param name The agent name
   * @return true if the agent exists
   */
  public boolean hasAgent(String name) {
    return agentSuppliers.containsKey(name);
  }

  /**
   * Checks if an agent failed to load.
   *
   * @param name The agent name
   * @return true if the agent failed to load
   */
  public boolean isAgentFailed(String name) {
    return failedAgents.contains(name);
  }

  /**
   * Gets the set of agents that failed to load.
   *
   * @return Unmodifiable set of failed agent names
   */
  public Set<String> getFailedAgents() {
    return Set.copyOf(failedAgents);
  }

  /**
   * Sets the callback invoked when agents are reloaded.
   *
   * <p>This is inspired by Python ADK's runners_to_clean pattern, allowing the caller to refresh
   * runners or other state when agents change.
   *
   * @param callback The callback to invoke with set of reloaded agent names
   */
  public void setOnAgentsReloadedCallback(@Nullable Consumer<Set<String>> callback) {
    this.onAgentsReloadedCallback = callback;
  }

  /**
   * Removes an agent from the cache, forcing it to be reloaded on next access.
   *
   * <p>This is inspired by Python ADK's remove_agent_from_cache pattern.
   *
   * @param agentName The name of the agent to remove from cache
   * @return true if the agent was in cache and removed
   */
  public boolean removeAgentFromCache(String agentName) {
    Path sourcePath = getSourcePath();
    if (sourcePath == null) return false;

    Path agentDir = sourcePath.resolve(agentName);
    Path yamlPath = agentDir.resolve(yamlConfigFilename);

    if (Files.exists(yamlPath)) {
      // Re-create supplier to force reload on next access
      agentSuppliers.put(agentName, Suppliers.memoize(() -> loadAgentFromPath(yamlPath)));
      failedAgents.remove(agentName);
      logger.info("Removed agent '{}' from cache - will reload on next access", agentName);
      return true;
    }
    return false;
  }

  /**
   * Forces reload of a specific agent.
   *
   * @param agentName The name of the agent to reload
   * @return true if the agent was successfully reloaded
   */
  public boolean forceReload(String agentName) {
    if (!agentSuppliers.containsKey(agentName)) {
      return false;
    }
    return removeAgentFromCache(agentName);
  }

  /** Forces reload of all agents. */
  public void forceReloadAll() {
    for (String agentName : listAgents()) {
      removeAgentFromCache(agentName);
    }
    logger.info("Force reloaded all {} agents", agentSuppliers.size());
  }

  /**
   * Gets the source directory path.
   *
   * @return The source directory as a Path, or null if not configured
   */
  @Nullable
  protected Path getSourcePath() {
    if (sourceDir == null || sourceDir.isEmpty()) {
      return null;
    }
    return Paths.get(sourceDir);
  }

  /**
   * Discovers available agents from the configured source directory and creates suppliers for them.
   *
   * <p>Subclasses can override this to implement different discovery patterns, such as:
   *
   * <ul>
   *   <li>Flat directory structure (all YAML files in one directory)
   *   <li>Different naming conventions
   *   <li>Filtering based on file content
   * </ul>
   *
   * @throws IOException if there's an error accessing the source directory
   */
  protected void discoverAgents() throws IOException {
    Path sourcePath = getSourcePath();
    if (sourcePath == null) {
      logger.info(
          "Agent source directory not configured. ConfigAgentLoader will not discover any agents.");
      return;
    }

    if (!Files.isDirectory(sourcePath)) {
      logger.warn(
          "Agent source directory does not exist: {}. ConfigAgentLoader will not discover any"
              + " agents.",
          sourcePath);
      return;
    }

    logger.info("Initial scan for YAML agents in: {}", sourcePath);

    // First, check if the current directory itself contains a root_agent.yaml
    Path currentDirYamlPath = sourcePath.resolve(yamlConfigFilename);
    if (Files.exists(currentDirYamlPath) && Files.isRegularFile(currentDirYamlPath)) {
      String agentName = sourcePath.getFileName().toString();
      registerAgent(agentName, currentDirYamlPath, sourcePath);
      logger.info("Discovered YAML agent '{}' from: {}", agentName, currentDirYamlPath);
      return;
    }

    // Otherwise, scan subdirectories for agents
    try (Stream<Path> entries = Files.list(sourcePath)) {
      for (Path agentDir : entries.collect(toList())) {
        if (Files.isDirectory(agentDir)) {
          Path yamlConfigPath = agentDir.resolve(yamlConfigFilename);
          if (Files.exists(yamlConfigPath) && Files.isRegularFile(yamlConfigPath)) {
            // Use the folder name as the agent identifier
            String agentName = agentDir.getFileName().toString();

            if (agentSuppliers.containsKey(agentName)) {
              logger.warn(
                  "Duplicate agent name '{}' found in {}. Overwriting.", agentName, yamlConfigPath);
            }

            registerAgent(agentName, yamlConfigPath, agentDir);
            logger.info("Discovered YAML agent '{}' from: {}", agentName, yamlConfigPath);
          }
        }
      }
    }

    logger.info("Initial YAML agent discovery complete. Found {} agents.", agentSuppliers.size());
  }

  /**
   * Registers an agent with its supplier and optionally sets up watching.
   *
   * <p>Subclasses can override to add additional registration logic.
   *
   * @param agentName The agent name
   * @param yamlConfigPath The path to the YAML config file
   * @param agentDir The agent directory to watch
   */
  protected void registerAgent(String agentName, Path yamlConfigPath, Path agentDir) {
    // Create a memoized supplier that will load the agent only when requested
    agentSuppliers.put(agentName, Suppliers.memoize(() -> loadAgentFromPath(yamlConfigPath)));

    // Register with watcher if hot-reloading is enabled
    if (hotReloadingEnabled && watcher != null) {
      watcher.watch(agentDir, agentDirPath -> updateAgentSupplier(agentDirPath));
    }
  }

  /**
   * Updates the agent supplier when a configuration changes.
   *
   * <p>Subclasses can override this to add:
   *
   * <ul>
   *   <li>Cascading reload (when child config changes, reload parents)
   *   <li>Validation before reload (keep old agent if new config is invalid)
   *   <li>Custom notification to external systems
   * </ul>
   *
   * <p>Inspired by Python ADK's agent reload and runners_to_clean pattern.
   *
   * @param agentDirPath The path to the agent configuration directory
   */
  protected void updateAgentSupplier(Path agentDirPath) {
    String agentName = agentDirPath.getFileName().toString();
    Path yamlConfigPath = agentDirPath.resolve(yamlConfigFilename);

    if (Files.exists(yamlConfigPath)) {
      // File exists - create/update supplier
      agentSuppliers.put(agentName, Suppliers.memoize(() -> loadAgentFromPath(yamlConfigPath)));
      failedAgents.remove(agentName);
      logger.info("Updated YAML agent supplier '{}' from: {}", agentName, yamlConfigPath);

      // Notify callback if set
      notifyAgentsReloaded(Set.of(agentName));
    } else {
      // File deleted - remove supplier
      agentSuppliers.remove(agentName);
      failedAgents.remove(agentName);
      logger.info("Removed YAML agent '{}' due to deleted config file", agentName);

      // Notify callback if set
      notifyAgentsReloaded(Set.of(agentName));
    }
  }

  /**
   * Notifies the callback that agents were reloaded.
   *
   * @param agentNames Set of agent names that were reloaded
   */
  protected void notifyAgentsReloaded(Set<String> agentNames) {
    if (onAgentsReloadedCallback != null && !agentNames.isEmpty()) {
      try {
        onAgentsReloadedCallback.accept(agentNames);
      } catch (Exception e) {
        logger.error("Error in onAgentsReloadedCallback", e);
      }
    }
  }

  /**
   * Loads an agent from the specified config path.
   *
   * <p>Subclasses can override this to add:
   *
   * <ul>
   *   <li>Custom validation logic
   *   <li>Registry setup (e.g., ComponentRegistry)
   *   <li>Post-processing of loaded agents
   * </ul>
   *
   * @param yamlConfigPath The path to the YAML configuration file
   * @return The loaded BaseAgent
   * @throws RuntimeException if loading fails
   */
  protected BaseAgent loadAgentFromPath(Path yamlConfigPath) {
    String agentName = yamlConfigPath.getParent().getFileName().toString();
    try {
      logger.debug("Loading YAML agent from: {}", yamlConfigPath);
      BaseAgent agent = ConfigAgentUtils.fromConfig(yamlConfigPath.toString());
      logger.info("Successfully loaded YAML agent '{}' from: {}", agent.name(), yamlConfigPath);
      failedAgents.remove(agentName);
      return agent;
    } catch (Exception e) {
      logger.error("Failed to load YAML agent from: {}", yamlConfigPath, e);
      failedAgents.add(agentName);
      throw new RuntimeException("Failed to load agent from: " + yamlConfigPath, e);
    }
  }

  /**
   * Starts the hot-loading service. Sets up file watching.
   *
   * <p>Subclasses can override to add additional initialization.
   *
   * @throws IOException if there's an error accessing the source directory
   */
  protected synchronized void start() throws IOException {
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

  /**
   * Returns whether the loader is currently running.
   *
   * @return true if the loader is started
   */
  public boolean isStarted() {
    return started;
  }

  /**
   * Returns whether hot-reloading is enabled.
   *
   * @return true if hot-reloading is enabled
   */
  public boolean isHotReloadingEnabled() {
    return hotReloadingEnabled;
  }

  /**
   * Returns the source directory.
   *
   * @return The source directory path
   */
  public String getSourceDir() {
    return sourceDir;
  }

  /**
   * Returns the YAML config filename being used.
   *
   * @return The YAML config filename
   */
  public String getYamlConfigFilename() {
    return yamlConfigFilename;
  }

  /**
   * Returns the watcher instance (for testing/subclasses).
   *
   * @return The ConfigAgentWatcher, or null if hot-reloading is disabled
   */
  @Nullable
  protected ConfigAgentWatcher getWatcher() {
    return watcher;
  }
}
