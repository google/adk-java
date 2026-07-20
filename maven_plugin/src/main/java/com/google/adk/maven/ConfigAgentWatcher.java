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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File watcher for monitoring changes to YAML configuration files in agent directories.
 *
 * <p>This class monitors individual agent folders containing `root_agent.yaml` files and triggers
 * callbacks when files are created, modified, or deleted.
 *
 * <p>The watcher polls for changes at regular intervals rather than using native filesystem events
 * for better cross-platform compatibility.
 *
 * <p>This class is designed to be extensible. Subclasses can override protected methods to
 * customize behavior such as:
 *
 * <ul>
 *   <li>{@link #checkForChanges()} - Add cascading reload logic
 *   <li>{@link #checkDirectoryForChanges(Path, ChangeCallback)} - Add validation before reload
 *   <li>{@link #scanYamlFiles(Path)} - Customize file discovery
 * </ul>
 *
 * <p>Inspired by Python ADK's agent_change_handler.py and AgentLoader patterns.
 */
@ThreadSafe
public class ConfigAgentWatcher {
  private static final Logger logger = LoggerFactory.getLogger(ConfigAgentWatcher.class);

  /** Default polling interval in milliseconds. */
  protected static final long DEFAULT_POLL_INTERVAL_MS = 2000;

  /** Map of watched folder paths to their callbacks. */
  protected final Map<Path, ChangeCallback> watchedFolders = new ConcurrentHashMap<>();

  /** Map of folder paths to their tracked YAML files and last modified times. */
  protected final Map<Path, Map<Path, Long>> watchedYamlFiles = new ConcurrentHashMap<>();

  /** Executor service for polling file changes. */
  protected final ScheduledExecutorService fileWatcher;

  /** Polling interval in milliseconds. */
  protected final long pollIntervalMs;

  /** Whether the watcher is currently running. */
  protected volatile boolean started = false;

  /**
   * Callback interface for handling file change events.
   *
   * <p>Implementations should be thread-safe as callbacks may be invoked from multiple threads.
   */
  @FunctionalInterface
  public interface ChangeCallback {
    /**
     * Called when a watched YAML file changes, is created, or is deleted.
     *
     * @param agentDirPath The path to the agent configuration directory
     */
    void onConfigChanged(Path agentDirPath);
  }

  /** Creates a new ConfigAgentWatcher with default polling interval. */
  public ConfigAgentWatcher() {
    this(DEFAULT_POLL_INTERVAL_MS);
  }

  /**
   * Creates a new ConfigAgentWatcher with custom polling interval.
   *
   * @param pollIntervalMs The polling interval in milliseconds
   */
  public ConfigAgentWatcher(long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
    this.fileWatcher = createExecutorService();
  }

  /**
   * Creates the executor service for polling. Subclasses can override to provide custom executor.
   *
   * @return The ScheduledExecutorService to use for polling
   */
  protected ScheduledExecutorService createExecutorService() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, "ConfigAgentWatcher-Poll");
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * Starts watching for file changes.
   *
   * @throws IllegalStateException if the watcher is already started
   */
  synchronized void start() {
    if (started) {
      throw new IllegalStateException("ConfigAgentWatcher is already started");
    }

    logger.info("Starting ConfigAgentWatcher with {}ms poll interval", pollIntervalMs);
    fileWatcher.scheduleAtFixedRate(
        this::checkForChanges, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    started = true;

    Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "ConfigAgentWatcher-Shutdown"));
    logger.info(
        "ConfigAgentWatcher started successfully. Watching {} folders.", watchedFolders.size());
  }

  /** Stops the file watcher. */
  synchronized void stop() {
    if (!started) {
      return;
    }

    logger.info("Stopping ConfigAgentWatcher...");
    fileWatcher.shutdown();
    try {
      if (!fileWatcher.awaitTermination(5, TimeUnit.SECONDS)) {
        fileWatcher.shutdownNow();
      }
    } catch (InterruptedException e) {
      fileWatcher.shutdownNow();
      Thread.currentThread().interrupt();
    }
    started = false;
    logger.info("ConfigAgentWatcher stopped.");
  }

  /**
   * Adds a folder to be watched for changes to any YAML files within it.
   *
   * @param agentDirPath The path to the agent configuration directory
   * @param callback The callback to invoke when changes are detected
   * @throws IllegalArgumentException if the folder doesn't exist
   */
  public void watch(Path agentDirPath, ChangeCallback callback) {
    if (!Files.isDirectory(agentDirPath)) {
      throw new IllegalArgumentException("Config folder does not exist: " + agentDirPath);
    }

    watchedFolders.put(agentDirPath, callback);

    // Scan and track all YAML files in the directory
    Map<Path, Long> yamlFiles = scanYamlFiles(agentDirPath);
    watchedYamlFiles.put(agentDirPath, yamlFiles);

    logger.debug("Now watching {} YAML files in agent folder: {}", yamlFiles.size(), agentDirPath);
  }

  /**
   * Removes a folder from being watched.
   *
   * <p>This is useful for cleanup when agents are removed or the loader is stopped. Inspired by
   * Python ADK's remove_agent_from_cache pattern.
   *
   * @param agentDirPath The path to stop watching
   * @return true if the folder was being watched, false otherwise
   */
  public boolean unwatch(Path agentDirPath) {
    ChangeCallback removed = watchedFolders.remove(agentDirPath);
    watchedYamlFiles.remove(agentDirPath);
    if (removed != null) {
      logger.debug("Stopped watching folder: {}", agentDirPath);
      return true;
    }
    return false;
  }

  /**
   * Gets all currently watched folder paths.
   *
   * @return An unmodifiable set of watched folder paths
   */
  public Set<Path> getWatchedFolders() {
    return Set.copyOf(watchedFolders.keySet());
  }

  /**
   * Scans a directory recursively for all YAML files and returns their last modified times.
   *
   * <p>Subclasses can override this to customize file discovery (e.g., different extensions,
   * filtering patterns, etc.).
   *
   * @param agentDirPath The directory to scan recursively
   * @return A map of YAML file paths to their last modified times
   */
  protected Map<Path, Long> scanYamlFiles(Path agentDirPath) {
    Map<Path, Long> yamlFiles = new HashMap<>();
    try (Stream<Path> files = Files.walk(agentDirPath)) {
      files
          .filter(Files::isRegularFile)
          .filter(this::isYamlFile)
          .forEach(
              yamlFile -> {
                long lastModified = getLastModified(yamlFile);
                yamlFiles.put(yamlFile, lastModified);
                logger.trace("Found YAML file: {} (modified: {})", yamlFile, lastModified);
              });
    } catch (IOException e) {
      logger.warn("Failed to scan YAML files in: {}", agentDirPath, e);
    }
    return yamlFiles;
  }

  /**
   * Checks if a path is a YAML file.
   *
   * <p>Subclasses can override to customize file matching.
   *
   * @param path The path to check
   * @return true if this is a YAML file
   */
  protected boolean isYamlFile(Path path) {
    String fileName = path.toString().toLowerCase();
    return fileName.endsWith(".yaml") || fileName.endsWith(".yml");
  }

  /**
   * Returns whether the watcher is currently running.
   *
   * @return true if the watcher is started, false otherwise
   */
  public boolean isStarted() {
    return started;
  }

  /** Checks all watched files for changes and triggers callbacks if needed. */
  protected void checkForChanges() {
    for (Map.Entry<Path, ChangeCallback> entry : new HashMap<>(watchedFolders).entrySet()) {
      Path agentDirPath = entry.getKey();
      ChangeCallback callback = entry.getValue();

      try {
        checkDirectoryForChanges(agentDirPath, callback);
      } catch (Exception e) {
        logger.error("Error checking directory for changes: {}", agentDirPath, e);
      }
    }
  }

  /**
   * Checks a specific agent directory for YAML file changes.
   *
   * @param agentDirPath The agent directory to check
   * @param callback The callback for this directory
   */
  protected void checkDirectoryForChanges(Path agentDirPath, ChangeCallback callback) {
    if (!Files.isDirectory(agentDirPath)) {
      // Directory was deleted
      handleDirectoryDeleted(agentDirPath);
      return;
    }

    Map<Path, Long> currentYamlFiles = watchedYamlFiles.get(agentDirPath);
    if (currentYamlFiles == null) {
      return; // No tracked files for this directory
    }

    // Scan current YAML files in the directory
    Map<Path, Long> freshYamlFiles = scanYamlFiles(agentDirPath);
    boolean hasChanges = false;

    // Check for new or modified files
    for (Map.Entry<Path, Long> freshEntry : freshYamlFiles.entrySet()) {
      Path yamlFile = freshEntry.getKey();
      long currentModified = freshEntry.getValue();
      Long previousModified = currentYamlFiles.get(yamlFile);

      if (previousModified == null) {
        // New file
        logger.info("Detected new YAML file: {}", yamlFile);
        hasChanges = true;
      } else if (currentModified > previousModified) {
        // Modified file
        logger.info("Detected change in YAML file: {}", yamlFile);
        hasChanges = true;
      }
    }

    // Check for deleted files
    for (Path trackedFile : currentYamlFiles.keySet()) {
      if (!freshYamlFiles.containsKey(trackedFile)) {
        logger.info("Detected deleted YAML file: {}", trackedFile);
        hasChanges = true;
      }
    }

    // Update tracked files and trigger callback if there were changes
    if (hasChanges) {
      watchedYamlFiles.put(agentDirPath, freshYamlFiles);
      onChangesDetected(agentDirPath, callback);
    }
  }

  /**
   * Called when changes are detected in a directory.
   *
   * <p>Subclasses can override this to add pre/post processing around the callback, such as
   * logging, metrics, or batching multiple callbacks.
   *
   * @param agentDirPath The directory where changes were detected
   * @param callback The callback to invoke
   */
  protected void onChangesDetected(Path agentDirPath, ChangeCallback callback) {
    callback.onConfigChanged(agentDirPath);
  }

  /**
   * Handles the deletion of a watched agent directory.
   *
   * <p>Subclasses can override to customize cleanup behavior.
   *
   * @param agentDirPath The path of the deleted agent directory
   */
  protected void handleDirectoryDeleted(Path agentDirPath) {
    logger.info("Agent directory deleted: {}", agentDirPath);
    ChangeCallback callback = watchedFolders.remove(agentDirPath);
    watchedYamlFiles.remove(agentDirPath);

    if (callback != null) {
      callback.onConfigChanged(agentDirPath);
    }
  }

  /**
   * Gets the last modified time of a file, handling potential I/O errors.
   *
   * @param path The file path
   * @return The last modified time in milliseconds, or 0 if there's an error
   */
  protected long getLastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      logger.warn("Could not get last modified time for: {}", path, e);
      return 0;
    }
  }
}
