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

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseTool.ToolArgsConfig;
import com.google.adk.tools.BaseTool.ToolConfig;
import com.google.adk.tools.BaseToolset;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves tool and toolset instances and classes. */
final class ToolResolver {

  private static final Logger logger = LoggerFactory.getLogger(LlmAgent.class);

  private ToolResolver() {}

  /**
   * Resolves a list of tool configurations into both {@link BaseTool} and {@link BaseToolset}
   * instances.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param toolConfigs The list of tool configurations to resolve.
   * @param configAbsPath The absolute path to the agent config file currently being processed. This
   *     path can be used to resolve relative paths for tool configurations, if necessary.
   * @return An immutable list of resolved {@link BaseTool} and {@link BaseToolset} instances.
   * @throws ConfigurationException if any tool configuration is invalid (e.g., missing name), if a
   *     tool cannot be found by its name or class, or if tool instantiation fails.
   */
  static ImmutableList<Object> resolveToolsAndToolsets(
      List<ToolConfig> toolConfigs, String configAbsPath) throws ConfigurationException {

    if (toolConfigs == null || toolConfigs.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<Object> resolvedItems = ImmutableList.builder();

    for (ToolConfig toolConfig : toolConfigs) {
      try {
        if (isNullOrEmpty(toolConfig.name())) {
          throw new ConfigurationException("Tool name cannot be empty");
        }

        String toolName = toolConfig.name().trim();

        // First try to resolve as a toolset
        BaseToolset toolset = resolveToolsetFromClass(toolName, toolConfig.args(), configAbsPath);
        if (toolset != null) {
          resolvedItems.add(toolset);
          logger.debug("Successfully resolved toolset from class: {}", toolName);
          continue;
        }

        // Option 1: Try to resolve as a tool instance
        BaseTool tool = resolveToolInstance(toolName);
        if (tool != null) {
          resolvedItems.add(tool);
          logger.debug("Successfully resolved tool instance: {}", toolName);
          continue;
        }

        // Option 2: Try to resolve as a tool class (with or without args)
        BaseTool toolFromClass = resolveToolFromClass(toolName, toolConfig.args(), configAbsPath);
        if (toolFromClass != null) {
          resolvedItems.add(toolFromClass);
          logger.debug("Successfully resolved tool from class: {}", toolName);
          continue;
        }

        throw new ConfigurationException("Tool or toolset not found: " + toolName);

      } catch (RuntimeException e) {
        String errorMsg = "Failed to resolve tool or toolset: " + toolConfig.name();
        logger.error(errorMsg, e);
        throw new ConfigurationException(errorMsg, e);
      }
    }

    return resolvedItems.build();
  }

  /**
   * Resolves a list of tool configurations into {@link BaseTool} instances.
   *
   * <p>This method is only for use by Agent Development Kit.
   *
   * @param toolConfigs The list of tool configurations to resolve.
   * @param configAbsPath The absolute path to the agent config file currently being processed. This
   *     path can be used to resolve relative paths for tool configurations, if necessary.
   * @return An immutable list of resolved {@link BaseTool} instances.
   * @throws ConfigurationException if any tool configuration is invalid (e.g., missing name), if a
   *     tool cannot be found by its name or class, or if tool instantiation fails.
   */
  static ImmutableList<BaseTool> resolveTools(List<ToolConfig> toolConfigs, String configAbsPath)
      throws ConfigurationException {

    if (toolConfigs == null || toolConfigs.isEmpty()) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<BaseTool> resolvedTools = ImmutableList.builder();

    for (ToolConfig toolConfig : toolConfigs) {
      try {
        if (isNullOrEmpty(toolConfig.name())) {
          throw new ConfigurationException("Tool name cannot be empty");
        }

        String toolName = toolConfig.name().trim();

        // Option 1: Try to resolve as a tool instance
        BaseTool tool = resolveToolInstance(toolName);
        if (tool != null) {
          resolvedTools.add(tool);
          logger.debug("Successfully resolved tool instance: {}", toolName);
          continue;
        }

        // Option 2: Try to resolve as a tool class (with or without args)
        BaseTool toolFromClass = resolveToolFromClass(toolName, toolConfig.args(), configAbsPath);
        if (toolFromClass != null) {
          resolvedTools.add(toolFromClass);
          logger.debug("Successfully resolved tool from class: {}", toolName);
          continue;
        }

        throw new ConfigurationException("Tool not found: " + toolName);

      } catch (RuntimeException e) {
        String errorMsg = "Failed to resolve tool: " + toolConfig.name();
        logger.error(errorMsg, e);
        throw new ConfigurationException(errorMsg, e);
      }
    }

    return resolvedTools.build();
  }

  /**
   * Resolves a tool instance by its unique name or its static field reference.
   *
   * <p>It first checks the {@link ComponentRegistry} for a registered tool instance. If not found,
   * and the name looks like a fully qualified Java name referencing a static field (e.g.,
   * "com.google.mytools.MyToolClass.INSTANCE"), it attempts to resolve it via reflection using
   * {@link #resolveInstanceViaReflection(String)}.
   *
   * @param toolName The name of the tool or a static field reference (e.g., "myTool",
   *     "com.google.mytools.MyToolClass.INSTANCE").
   * @return The resolved tool instance, or {@code null} if the tool is not found in the registry
   *     and cannot be resolved via reflection.
   */
  @Nullable
  static BaseTool resolveToolInstance(String toolName) {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // First try registry
    Optional<BaseTool> toolOpt = ComponentRegistry.resolveToolInstance(toolName);
    if (toolOpt.isPresent()) {
      return toolOpt.get();
    }

    // If not in registry and looks like Java qualified name, try reflection
    if (isJavaQualifiedName(toolName)) {
      try {
        BaseTool tool = resolveInstanceViaReflection(toolName);
        if (tool != null) {
          registry.register(toolName, tool);
          logger.debug("Resolved and registered tool instance via reflection: {}", toolName);
          return tool;
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        logger.debug("Failed to resolve instance via reflection: {}", toolName, e);
      }
    }
    logger.debug("Could not resolve tool instance: {}", toolName);
    return null;
  }

  /**
   * Resolves a toolset instance by its unique name or its static field reference.
   *
   * <p>It first checks the {@link ComponentRegistry} for a registered toolset instance. If not
   * found, it attempts to resolve the toolset via reflection if the name looks like a Java
   * qualified name (e.g., "com.google.mytools.MyToolsetClass.INSTANCE").
   *
   * @param toolsetName The name of the toolset to resolve (could be simple name or full qualified
   *     "com.google.mytools.MyToolsetClass.INSTANCE").
   * @return The resolved toolset instance, or {@code null} if the toolset is not found in the
   *     registry and cannot be resolved via reflection.
   */
  @Nullable
  static BaseToolset resolveToolsetInstance(String toolsetName) {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // First try registry
    Optional<BaseToolset> toolsetOpt = ComponentRegistry.resolveToolsetInstance(toolsetName);
    if (toolsetOpt.isPresent()) {
      return toolsetOpt.get();
    }

    // If not in registry and looks like Java qualified name, try reflection
    if (isJavaQualifiedName(toolsetName)) {
      try {
        BaseToolset toolset = resolveToolsetInstanceViaReflection(toolsetName);
        if (toolset != null) {
          registry.register(toolsetName, toolset);
          logger.debug("Resolved and registered toolset instance via reflection: {}", toolsetName);
          return toolset;
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        logger.debug("Failed to resolve toolset instance via reflection: {}", toolsetName, e);
      }
    }
    logger.debug("Could not resolve toolset instance: {}", toolsetName);
    return null;
  }

  /**
   * Resolves a toolset from a class name and configuration arguments.
   *
   * <p>It attempts to resolve the toolset class using the ComponentRegistry, then instantiates it
   * using the fromConfig method if available.
   *
   * @param className The name of the toolset class to instantiate.
   * @param args Configuration arguments for toolset creation.
   * @return The instantiated toolset instance, or {@code null} if the class cannot be found or is
   *     not a toolset.
   * @throws ConfigurationException if toolset instantiation fails.
   */
  @Nullable
  static BaseToolset resolveToolsetFromClass(
      String className, ToolArgsConfig args, String configAbsPath) throws ConfigurationException {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // First try registry for class
    Optional<Class<? extends BaseToolset>> toolsetClassOpt =
        ComponentRegistry.resolveToolsetClass(className);
    Class<? extends BaseToolset> toolsetClass = null;

    if (toolsetClassOpt.isPresent()) {
      toolsetClass = toolsetClassOpt.get();
    } else if (isJavaQualifiedName(className)) {
      // Try reflection to get class
      try {
        Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
        if (BaseToolset.class.isAssignableFrom(clazz)) {
          toolsetClass = clazz.asSubclass(BaseToolset.class);
          // Optimization: register for reuse
          registry.register(className, toolsetClass);
          logger.debug("Resolved and registered toolset class via reflection: {}", className);
        }
      } catch (ClassNotFoundException e) {
        logger.debug("Failed to resolve toolset class via reflection: {}", className, e);
        return null;
      }
    }

    if (toolsetClass == null) {
      logger.debug("Could not resolve toolset class: {}", className);
      return null;
    }

    // First try to resolve as a toolset instance
    BaseToolset toolsetInstance = resolveToolsetInstance(className);
    if (toolsetInstance != null) {
      logger.debug("Successfully resolved toolset instance: {}", className);
      return toolsetInstance;
    }

    // Look for fromConfig method
    try {
      Method fromConfigMethod =
          toolsetClass.getMethod("fromConfig", ToolConfig.class, String.class);
      ToolConfig toolConfig = new ToolConfig(className, args);
      Object instance = fromConfigMethod.invoke(null, toolConfig, "");
      if (instance instanceof BaseToolset baseToolset) {
        return baseToolset;
      }
    } catch (NoSuchMethodException e) {
      logger.debug("Class {} does not have fromConfig method", className);
      return null;
    } catch (IllegalAccessException e) {
      logger.error("Cannot access fromConfig method on toolset class {}", className, e);
      throw new ConfigurationException(
          "Access denied to fromConfig method on class " + className, e);
    } catch (InvocationTargetException e) {
      logger.error(
          "Error during fromConfig method invocation on toolset class {}", className, e.getCause());
      throw new ConfigurationException(
          "Error during toolset creation from class " + className, e.getCause());
    } catch (RuntimeException e) {
      logger.error("Unexpected error calling fromConfig on toolset class {}", className, e);
      throw new ConfigurationException(
          "Unexpected error creating toolset from class " + className, e);
    }

    return null;
  }

  /**
   * Resolves a toolset instance via reflection from a static field reference.
   *
   * @param toolsetName The toolset name in format "com.example.MyToolsetClass.INSTANCE".
   * @return The resolved toolset instance, or {@code null} if not found or not a BaseToolset.
   * @throws ReflectiveOperationException if the class cannot be loaded or field access fails.
   */
  @Nullable
  static BaseToolset resolveToolsetInstanceViaReflection(String toolsetName)
      throws ReflectiveOperationException {
    int lastDotIndex = toolsetName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return null;
    }

    String className = toolsetName.substring(0, lastDotIndex);
    String fieldName = toolsetName.substring(lastDotIndex + 1);

    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

    try {
      Field field = clazz.getField(fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        logger.debug("Field {} in class {} is not static", fieldName, className);
        return null;
      }
      Object instance = field.get(null);
      if (instance instanceof BaseToolset baseToolset) {
        return baseToolset;
      } else {
        logger.debug("Field {} in class {} is not a BaseToolset instance", fieldName, className);
        return null;
      }
    } catch (NoSuchFieldException e) {
      logger.debug("Field {} not found in class {}", fieldName, className);
      return null;
    }
  }

  /**
   * Resolves a tool from a class name and optional arguments.
   *
   * <p>It attempts to load the class specified by {@code className}. If {@code args} are provided
   * and non-empty, it looks for a static factory method {@code fromConfig(ToolArgsConfig)} on the
   * class to instantiate the tool. If {@code args} are null or empty, it looks for a default
   * constructor.
   *
   * @param className The fully qualified name of the tool class to instantiate.
   * @param args Optional configuration arguments for tool creation. If provided, the class must
   *     implement a static {@code fromConfig(ToolArgsConfig)} factory method. If null or empty, the
   *     class must have a default constructor.
   * @return The instantiated tool instance, or {@code null} if the class cannot be found or loaded.
   * @throws ConfigurationException if {@code args} are provided but no {@code fromConfig} method
   *     exists, if {@code args} are not provided but no default constructor exists, or if
   *     instantiation via the factory method or constructor fails.
   */
  @Nullable
  static BaseTool resolveToolFromClass(String className, ToolArgsConfig args, String configAbsPath)
      throws ConfigurationException {
    ComponentRegistry registry = ComponentRegistry.getInstance();

    // First try registry for class
    Optional<Class<? extends BaseTool>> classOpt = ComponentRegistry.resolveToolClass(className);
    Class<? extends BaseTool> toolClass = null;

    if (classOpt.isPresent()) {
      toolClass = classOpt.get();
    } else if (isJavaQualifiedName(className)) {
      // Try reflection to get class
      try {
        Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
        if (BaseTool.class.isAssignableFrom(clazz)) {
          toolClass = clazz.asSubclass(BaseTool.class);
          // Optimization: register for reuse
          registry.register(className, toolClass);
          logger.debug("Resolved and registered tool class via reflection: {}", className);
        }
      } catch (ClassNotFoundException e) {
        logger.debug("Failed to resolve class via reflection: {}", className, e);
        return null;
      }
    }

    if (toolClass == null) {
      return null;
    }

    // If args provided and not empty, try fromConfig method first
    if (args != null && !args.isEmpty()) {
      try {
        Method fromConfigMethod =
            toolClass.getMethod("fromConfig", ToolArgsConfig.class, String.class);
        Object instance = fromConfigMethod.invoke(null, args, configAbsPath);
        if (instance instanceof BaseTool baseTool) {
          return baseTool;
        }
      } catch (NoSuchMethodException e) {
        throw new ConfigurationException(
            "Class " + className + " does not have fromConfig method but args were provided.", e);
      } catch (ReflectiveOperationException | RuntimeException e) {
        logger.error("Error calling fromConfig on class {}", className, e);
        throw new ConfigurationException("Error creating tool from class " + className, e);
      }
    }

    // No args provided or empty args, try default constructor
    try {
      Constructor<? extends BaseTool> constructor = toolClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (NoSuchMethodException e) {
      throw new ConfigurationException(
          "Class " + className + " does not have a default constructor and no args were provided.",
          e);
    } catch (ReflectiveOperationException | RuntimeException e) {
      logger.error("Error calling default constructor on class {}", className, e);
      throw new ConfigurationException(
          "Error creating tool from class " + className + " using default constructor", e);
    }
  }

  /**
   * Checks if a string appears to be a Java fully qualified name, such as "com.google.adk.MyClass"
   * or "com.google.adk.MyClass.MY_FIELD".
   *
   * <p>It verifies that the name contains at least one dot ('.') and consists of characters valid
   * for Java identifiers and package names.
   *
   * @param name The string to check.
   * @return {@code true} if the string matches the pattern of a Java qualified name, {@code false}
   *     otherwise.
   */
  static boolean isJavaQualifiedName(String name) {
    if (name == null || name.trim().isEmpty()) {
      return false;
    }
    return name.contains(".") && name.matches("^[a-zA-Z_$][a-zA-Z0-9_.$]*$");
  }

  /**
   * Resolves a {@link BaseTool} instance by attempting to access a public static field via
   * reflection.
   *
   * <p>This method expects {@code toolName} to be in the format
   * "com.google.package.ClassName.STATIC_FIELD_NAME", where "STATIC_FIELD_NAME" is the name of a
   * public static field in "com.google.package.ClassName" that holds a {@link BaseTool} instance.
   *
   * @param toolName The fully qualified name of a static field holding a tool instance.
   * @return The {@link BaseTool} instance, or {@code null} if {@code toolName} is not in the
   *     expected format, or if the field is not found, not static, or not of type {@link BaseTool}.
   * @throws ReflectiveOperationException if the class specified in {@code toolName} cannot be
   *     loaded, or if accessing the field causes an exception.
   */
  @Nullable
  static BaseTool resolveInstanceViaReflection(String toolName)
      throws ReflectiveOperationException {
    int lastDotIndex = toolName.lastIndexOf('.');
    if (lastDotIndex == -1) {
      return null;
    }

    String className = toolName.substring(0, lastDotIndex);
    String fieldName = toolName.substring(lastDotIndex + 1);

    Class<?> clazz = Thread.currentThread().getContextClassLoader().loadClass(className);

    try {
      Field field = clazz.getField(fieldName);
      if (!Modifier.isStatic(field.getModifiers())) {
        logger.debug("Field {} in class {} is not static", fieldName, className);
        return null;
      }
      Object instance = field.get(null);
      if (instance instanceof BaseTool baseTool) {
        return baseTool;
      } else {
        logger.debug("Field {} in class {} is not a BaseTool instance", fieldName, className);
      }
    } catch (NoSuchFieldException e) {
      logger.debug("Field {} not found in class {}", fieldName, className);
    }
    return null;
  }
}
