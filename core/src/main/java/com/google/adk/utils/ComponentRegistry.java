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

package com.google.adk.utils;

import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Callbacks;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A registry for storing and retrieving ADK instances by name.
 *
 * <p>This class provides a base registry with common ADK components and is designed to be extended
 * by users who want to add their own pre-wired entries. The registry is fully thread-safe and
 * supports storing any type of object.
 *
 * <p><strong>Thread Safety:</strong>
 *
 * <ul>
 *   <li>All instance methods are thread-safe due to the underlying ConcurrentHashMap
 *   <li>The singleton instance access is thread-safe using volatile semantics
 *   <li>The setInstance() method is synchronized to ensure atomic singleton replacement
 * </ul>
 *
 * <p>Base pre-wired entries include:
 *
 * <ul>
 *   <li>"google_search" - GoogleSearchTool instance
 *   <li>"code_execution" - BuiltInCodeExecutionTool instance
 *   <li>"exit_loop" - ExitLoopTool instance
 *   <li>"url_context" - UrlContextTool instance
 *   <li>"google_maps_grounding" - GoogleMapsTool instance
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Use the singleton instance
 * ComponentRegistry registry = ComponentRegistry.getInstance();
 * Optional<GoogleSearchTool> searchTool = registry.get("google_search", GoogleSearchTool.class);
 *
 * // Extend ComponentRegistry to add custom pre-wired entries
 * public class MyComponentRegistry extends ComponentRegistry {
 *   public MyComponentRegistry() {
 *     super(); // Initialize base pre-wired entries
 *     register("my_custom_tool", new MyCustomTool());
 *     register("my_agent", new MyCustomAgent());
 *   }
 * }
 *
 * // Replace the singleton with custom registry when server starts
 * ComponentRegistry.setInstance(new MyComponentRegistry());
 * }</pre>
 */
public class ComponentRegistry {

  private static final Logger logger = LoggerFactory.getLogger(ComponentRegistry.class);
  private static volatile ImmutableMap<String, Object> DEFAULT_REGISTRY;

  private static volatile ComponentRegistry instance = new ComponentRegistry();

  private final Map<String, Object> registry = new ConcurrentHashMap<>();

  protected ComponentRegistry() {
    initializePreWiredEntries();
  }

  /** Initializes the registry with base pre-wired ADK instances. */
  private void initializePreWiredEntries() {
    if (DEFAULT_REGISTRY == null) {
      synchronized (ComponentRegistry.class) {
        if (DEFAULT_REGISTRY == null) {
          registerAdkClassByName("com.google.adk.agents.LlmAgent");
          registerAdkClassByName("com.google.adk.agents.LoopAgent");
          registerAdkClassByName("com.google.adk.agents.ParallelAgent");
          registerAdkClassByName("com.google.adk.agents.SequentialAgent");

          registerAdkToolInstance("google_search", "com.google.adk.tools.GoogleSearchTool");
          registerAdkToolInstance("load_artifacts", "com.google.adk.tools.LoadArtifactsTool");
          registerAdkToolInstance("exit_loop", "com.google.adk.tools.ExitLoopTool");
          registerAdkToolInstance("url_context", "com.google.adk.tools.UrlContextTool");
          registerAdkToolInstance("google_maps_grounding", "com.google.adk.tools.GoogleMapsTool");

          registerAdkClassByName("com.google.adk.tools.AgentTool");
          registerAdkClassByName("com.google.adk.tools.LongRunningFunctionTool");
          registerAdkClassByName("com.google.adk.tools.ExampleTool");

          registerAdkClassByName("com.google.adk.tools.mcp.McpToolset");
          // TODO: add all python tools that also exist in Java.

          DEFAULT_REGISTRY = ImmutableMap.copyOf(registry);
          return;
        }
      }
    }
    registry.putAll(DEFAULT_REGISTRY);
    logger.debug("Initialized base pre-wired entries in ComponentRegistry");
  }

  private void registerAdkClassByName(String className) {
    try {
      Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
      String standardPrefix;
      if (BaseAgent.class.isAssignableFrom(clazz)) {
        standardPrefix = "google.adk.agents.";
      } else if (BaseTool.class.isAssignableFrom(clazz)) {
        standardPrefix = "google.adk.tools.";
      } else if (BaseToolset.class.isAssignableFrom(clazz)) {
        standardPrefix = "google.adk.tools.";
      } else {
        throw new IllegalArgumentException(
            "Cannot determine standardPrefix for type " + clazz.getName());
      }

      registry.put(clazz.getName(), clazz);
      // For python compatibility, also register the name used in ADK Python.
      registry.put(standardPrefix + clazz.getSimpleName(), clazz);

      if (BaseToolset.class.isAssignableFrom(clazz)) {
        registry.put(clazz.getSimpleName(), clazz);
        if (clazz.getSimpleName().equals("McpToolset")) {
          registry.put("mcp.McpToolset", clazz);
        }
      }
    } catch (Exception e) {
      logger.info(
          "{} not found, skipping registration: {}",
          className.substring(className.lastIndexOf('.') + 1),
          e.getMessage());
    }
  }

  private void registerAdkToolInstance(String name, String toolClassName) {
    try {
      Object toolInstance = Class.forName(toolClassName).getField("INSTANCE").get(null);
      registry.put(name, toolInstance);
      // For python compatibility, also register the name used in ADK Python.
      registry.put("google.adk.tools." + name, toolInstance);
    } catch (Exception e) {
      logger.info(
          "{} not found, skipping registration: {}",
          toolClassName.substring(toolClassName.lastIndexOf('.') + 1),
          e.getMessage());
    }
  }

  /**
   * Registers an object with the given name. This can override pre-wired entries.
   *
   * <p>This method is thread-safe due to the underlying ConcurrentHashMap.
   *
   * @param name the name to associate with the object
   * @param value the object to register (can be an instance, class, function, etc.)
   * @throws IllegalArgumentException if name is null or empty, or if value is null
   */
  public void register(String name, Object value) {
    if (isNullOrEmpty(name) || name.trim().isEmpty()) {
      throw new IllegalArgumentException("Name cannot be null or empty");
    }
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }

    Object previous = registry.put(name, value);
    if (previous != null) {
      logger.info(
          "Overriding existing registration for name: {} (was: {}, now: {})",
          name,
          previous.getClass().getSimpleName(),
          value.getClass().getSimpleName());
    } else {
      logger.debug(
          "Registered new object of type {} with name: {}", value.getClass().getSimpleName(), name);
    }
  }

  /**
   * Retrieves an object by name and attempts to cast it to the specified type.
   *
   * @param name the name of the object to retrieve
   * @param type the expected type of the object
   * @param <T> the type parameter
   * @return an Optional containing the object if found and castable to the specified type, or an
   *     empty Optional otherwise
   */
  public <T> Optional<T> get(String name, Class<T> type) {
    return get(name)
        .filter(
            value -> {
              if (type.isInstance(value)) {
                return true;
              } else {
                logger.info(
                    "Object with name '{}' is of type {} but expected type {}",
                    name,
                    value.getClass().getSimpleName(),
                    type.getSimpleName());
                return false;
              }
            })
        .map(type::cast);
  }

  /**
   * Retrieves an object by name without type checking.
   *
   * @param name the name of the object to retrieve
   * @return an Optional containing the object if found, or an empty Optional otherwise
   */
  public Optional<Object> get(String name) {
    return Optional.ofNullable(emptyToNull(name))
        .filter(n -> !n.trim().isEmpty())
        .flatMap(n -> Optional.ofNullable(registry.get(n)));
  }

  /**
   * Returns the global singleton instance of ComponentRegistry.
   *
   * @return the singleton ComponentRegistry instance
   */
  public static ComponentRegistry getInstance() {
    return instance;
  }

  /**
   * Updates the global singleton instance with a new ComponentRegistry. This is useful for
   * replacing the default registry with a custom one when the server starts.
   *
   * <p>This method is thread-safe and ensures that all threads see the updated instance atomically.
   *
   * @param newInstance the new ComponentRegistry instance to use as the singleton
   * @throws IllegalArgumentException if newInstance is null
   */
  public static synchronized void setInstance(ComponentRegistry newInstance) {
    if (newInstance == null) {
      throw new IllegalArgumentException("ComponentRegistry instance cannot be null");
    }
    instance = newInstance;
    logger.info("ComponentRegistry singleton instance updated");
  }

  /**
   * Resolves an agent instance from the registry.
   *
   * <p>This method looks up an agent in the ComponentRegistry by the given key. The registry should
   * have been pre-populated with all available agents during initialization.
   *
   * <p>The key can be any string that was used to register the agent, such as:
   *
   * <ul>
   *   <li>A class name: "com.example.LifeAgent"
   *   <li>A static field reference: "com.example.LifeAgent.INSTANCE"
   *   <li>A simple name: "life_agent"
   *   <li>Any custom key: "sub_agents_config.life_agent.agent"
   * </ul>
   *
   * @param name the registry key to look up
   * @return an Optional containing the BaseAgent if found, or empty if not found
   */
  public static Optional<BaseAgent> resolveAgentInstance(String name) {
    return getInstance().get(name, BaseAgent.class);
  }

  /**
   * Resolves the agent class based on the agent class name from the configuration.
   *
   * @param agentClassName the name of the agent class from the config
   * @return the corresponding agent class
   * @throws IllegalArgumentException if the agent class is not supported
   */
  public static Class<? extends BaseAgent> resolveAgentClass(String agentClassName) {
    // If no agent_class is specified, it will default to LlmAgent.
    final String effectiveAgentClassName =
        isNullOrEmpty(agentClassName) ? "com.google.adk.agents.LlmAgent" : agentClassName;

    Optional<Class<? extends BaseAgent>> agentClass;

    if (effectiveAgentClassName.contains(".")) {
      // If agentClassName contains '.', use it directly
      agentClass = getType(effectiveAgentClassName, BaseAgent.class);
    } else {
      // First try the simple name
      agentClass =
          getType(effectiveAgentClassName, BaseAgent.class)
              // If not found, try with com.google.adk.agents prefix
              .or(
                  () ->
                      getType("com.google.adk.agents." + effectiveAgentClassName, BaseAgent.class))
              // For Python compatibility, also try with google.adk.agents prefix
              .or(() -> getType("google.adk.agents." + effectiveAgentClassName, BaseAgent.class));
    }

    return agentClass.orElseThrow(
        () ->
            new IllegalArgumentException(
                "agentClass '"
                    + effectiveAgentClassName
                    + "' is not in registry or not a subclass of BaseAgent."));
  }

  /**
   * Resolves the tool instance based on the tool name from the configuration.
   *
   * @param name the name of the tool from the config
   * @return an Optional containing the tool instance if found, empty otherwise
   */
  /**
   * Resolves a toolset instance by name from the registry.
   *
   * @param name The name of the toolset instance to resolve.
   * @return An Optional containing the toolset instance if found, empty otherwise.
   */
  public static Optional<BaseToolset> resolveToolsetInstance(String name) {
    return resolveInstance(name, "tools", BaseToolset.class);
  }

  public static Optional<BaseTool> resolveToolInstance(String name) {
    return resolveInstance(name, "tools", BaseTool.class);
  }

  /**
   * Resolves an instance from the registry by name, attempting various prefixes if the name is not
   * fully qualified.
   *
   * @param name The name of the instance to resolve.
   * @param type The expected type of the instance.
   * @param <T> The type parameter.
   * @return An Optional containing the instance if found and castable to the specified type, empty
   *     otherwise.
   */
  private static <T> Optional<T> resolveInstance(String name, String adkPackage, Class<T> type) {
    if (isNullOrEmpty(name)) {
      return Optional.empty();
    } else if (name.contains(".")) {
      // If name contains '.', use it directly
      return getInstance().get(name, type);
    } else {
      // Try simple name, then common prefixes (com/google)
      return getInstance()
          .get(name, type)
          .or(
              () ->
                  getInstance().get(String.format("com.google.adk.%s.%s", adkPackage, name), type))
          .or(() -> getInstance().get(String.format("google.adk.%s.%s", adkPackage, name), type));
    }
  }

  /**
   * Resolves the tool class based on the tool class name from the configuration.
   *
   * @param toolClassName the name of the tool class from the config
   * @return an Optional containing the tool class if found, empty otherwise
   */
  public static Optional<Class<? extends BaseTool>> resolveToolClass(String toolClassName) {
    if (isNullOrEmpty(toolClassName)) {
      return Optional.empty();
    } else if (toolClassName.contains(".")) {
      // If toolClassName contains '.', use it directly
      return getType(toolClassName, BaseTool.class);
    } else {
      // First try the simple name
      return getType(toolClassName, BaseTool.class)
          // If not found, try with common prefixes (com/google)
          .or(() -> getType("com.google.adk.tools." + toolClassName, BaseTool.class))
          .or(() -> getType("google.adk.tools." + toolClassName, BaseTool.class));
    }
  }

  /**
   * Resolves a toolset class by name from the registry or by attempting to load it.
   *
   * <p>This method follows the same pattern as {@code resolveToolClass} but for BaseToolset
   * implementations. It first checks the registry, then attempts direct class loading if the name
   * contains a dot (indicating a fully qualified class name).
   *
   * @param toolsetClassName the name of the toolset class from the config
   * @return an Optional containing the toolset class if found, empty otherwise
   */
  public static Optional<Class<? extends BaseToolset>> resolveToolsetClass(
      String toolsetClassName) {
    if (isNullOrEmpty(toolsetClassName)) {
      return Optional.empty();
    } else if (toolsetClassName.contains(".")) {
      // If toolsetClassName contains '.', use it directly
      return getType(toolsetClassName, BaseToolset.class)
          .or(() -> loadToolsetClass(toolsetClassName));
    } else {
      // First try the simple name, then with google.adk.tools prefix (consistent with
      // resolveToolClass)
      return getType(toolsetClassName, BaseToolset.class)
          .or(() -> getType("com.google.adk.tools." + toolsetClassName, BaseToolset.class))
          .or(() -> getType("google.adk.tools." + toolsetClassName, BaseToolset.class));
    }
  }

  public Set<String> getToolNamesWithPrefix(String prefix) {
    return registry.keySet().stream()
        .filter(name -> name.startsWith(prefix))
        .collect(toImmutableSet());
  }

  public static Optional<Callbacks.BeforeAgentCallback> resolveBeforeAgentCallback(String name) {
    return getInstance().get(name, Callbacks.BeforeAgentCallback.class);
  }

  public static Optional<Callbacks.AfterAgentCallback> resolveAfterAgentCallback(String name) {
    return getInstance().get(name, Callbacks.AfterAgentCallback.class);
  }

  public static Optional<Callbacks.BeforeModelCallback> resolveBeforeModelCallback(String name) {
    return getInstance().get(name, Callbacks.BeforeModelCallback.class);
  }

  public static Optional<Callbacks.AfterModelCallback> resolveAfterModelCallback(String name) {
    return getInstance().get(name, Callbacks.AfterModelCallback.class);
  }

  public static Optional<Callbacks.BeforeToolCallback> resolveBeforeToolCallback(String name) {
    return getInstance().get(name, Callbacks.BeforeToolCallback.class);
  }

  public static Optional<Callbacks.AfterToolCallback> resolveAfterToolCallback(String name) {
    return getInstance().get(name, Callbacks.AfterToolCallback.class);
  }

  /**
   * Retrieves a Class object from the registry by name and casts it to a specific type.
   *
   * @param name The name of the class in the registry.
   * @param type The expected superclass or interface of the class.
   * @param <T> The type parameter extending Class.
   * @return An Optional containing the Class if found and assignable to the specified type,
   *     otherwise empty.
   */
  @SuppressWarnings("unchecked") // For type casting.
  private static <T> Optional<Class<? extends T>> getType(String name, Class<T> type) {
    return getInstance()
        .get(name, Class.class)
        .filter(type::isAssignableFrom)
        .map(clazz -> clazz.asSubclass(type));
  }

  private static Optional<Class<? extends BaseToolset>> loadToolsetClass(String className) {
    try {
      Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
      if (BaseToolset.class.isAssignableFrom(clazz)) {
        return Optional.of(clazz.asSubclass(BaseToolset.class));
      }
    } catch (ClassNotFoundException e) {
      // Class not found, return empty
    }
    return Optional.empty();
  }
}
