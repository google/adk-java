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

import com.google.adk.utils.ComponentRegistry;
import com.google.adk.web.AdkWebServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Maven plugin goal that starts the Google ADK Web Server with user-provided agents.
 *
 * <p>This Mojo provides a convenient way for developers to test and interact with their agents
 * through ADK Web UI. The plugin dynamically loads user-defined agents and makes them available
 * through a browser interface.
 *
 * <h3>Basic Usage</h3>
 *
 * <pre>{@code
 * mvn google-adk:web -Dagents=com.example.MyAgentLoader
 * }</pre>
 *
 * <h3>Configuration Parameters</h3>
 *
 * <ul>
 *   <li><strong>agents</strong> (required) - Full class path to AgentLoader implementation
 *   <li><strong>port</strong> (optional, default: 8000) - Server port
 *   <li><strong>host</strong> (optional, default: localhost) - Server host address
 *   <li><strong>hotReloading</strong> (optional, default: true) - Enable hot reloading for
 *       config-based agents
 *   <li><strong>registry</strong> (optional) - Full class path to custom ComponentRegistry subclass
 *       for injecting customized tools and agents
 * </ul>
 *
 * <h3>AgentLoader Implementation</h3>
 *
 * <p>The agents parameter should point to a class that implements {@link AgentLoader}. It can
 * reference either:
 *
 * <ul>
 *   <li>A static field: {@code com.example.MyProvider.INSTANCE}
 *   <li>A class with default constructor: {@code com.example.MyProvider}
 * </ul>
 *
 * <h3>Web Interface</h3>
 *
 * <p>Once started, ADK Web UI is available at {@code http://host:port} where users can interact
 * with available agents.
 *
 * @author Google ADK Team
 * @since 0.2.1
 */
@Mojo(name = "web", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.COMPILE)
public class WebMojo extends AbstractMojo {

  /** The Maven project instance. */
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /**
   * Full class path to the AgentLoader instance or path to agent configuration directory.
   *
   * <p>This parameter specifies either:
   *
   * <ul>
   *   <li><strong>Static field reference:</strong> {@code com.example.MyProvider.INSTANCE}
   *   <li><strong>Class name:</strong> {@code com.example.MyProvider} (requires default
   *       constructor)
   *   <li><strong>Directory path:</strong> {@code /path/to/agents} (parent directory containing
   *       agent subdirectories, each with root_agent.yaml)
   * </ul>
   *
   * <p>When a directory path is provided, the plugin will use ConfigAgentLoader to scan for
   * subdirectories containing {@code root_agent.yaml} files.
   *
   * <p>Examples:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=com.example.MyAgentLoader
   * mvn google-adk:web -Dagents=/path/to/agents
   * }</pre>
   */
  @Parameter(property = "agents")
  private String agents;

  /**
   * Port number for the web server.
   *
   * <p>The web server will listen on this port. Must be between 1 and 65535. Default is 8000.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... -Dport=9090
   * }</pre>
   */
  @Parameter(property = "port", defaultValue = "8000")
  private int port;

  /**
   * Host address to bind the web server to.
   *
   * <p>The web server will bind to this host address. Use "localhost" or "127.0.0.1" for local
   * access only, or "0.0.0.0" to accept connections from any network interface. Default is
   * localhost.
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... -Dhost=0.0.0.0
   * }</pre>
   */
  @Parameter(property = "host", defaultValue = "localhost")
  private String host;

  /**
   * Whether to enable hot reloading of agent configurations.
   *
   * <p>When enabled, certain agent configurations may be reloaded without restarting the server.
   * Default is true.
   */
  @Parameter(property = "hotReloading", defaultValue = "true")
  private boolean hotReloading;

  /**
   * Full class path to a custom ComponentRegistry subclass.
   *
   * <p>This parameter allows users to specify a custom ComponentRegistry implementation that will
   * be used instead of the default ComponentRegistry. The custom registry can pre-register
   * additional tools, agents, or other components.
   *
   * <p>The parameter can reference either:
   *
   * <ul>
   *   <li><strong>Static field reference:</strong> {@code com.example.MyRegistry.INSTANCE}
   *   <li><strong>Class name:</strong> {@code com.example.MyRegistry} (requires default
   *       constructor)
   * </ul>
   *
   * <p>Example:
   *
   * <pre>{@code
   * mvn google-adk:web -Dagents=... -Dregistry=com.example.MyCustomRegistry
   * }</pre>
   */
  @Parameter(property = "registry")
  private String registry;

  private ConfigurableApplicationContext applicationContext;
  private URLClassLoader projectClassLoader;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    getLog().info("Starting Google ADK Web Server...");
    getLog().info("Plugin version: " + getClass().getPackage().getImplementationVersion());

    validateParameters();
    logConfiguration();

    try {
      // Create custom classloader with project dependencies
      projectClassLoader = createProjectClassLoader();
      Thread.currentThread().setContextClassLoader(projectClassLoader);

      // Set up custom ComponentRegistry if provided
      if (registry != null) {
        getLog().info("Loading custom ComponentRegistry: " + registry);
        ComponentRegistry customRegistry = loadCustomRegistry();
        ComponentRegistry.setInstance(customRegistry);
      }

      // Load and instantiate the AgentLoader
      getLog().info("Loading agent loader: " + agents);
      com.google.adk.web.AgentLoader provider = loadAgentProvider();

      // Set up system properties for Spring Boot
      setupSystemProperties();

      // Start the Spring Boot application with custom agent provider
      SpringApplication app = new SpringApplication(AdkWebServer.class);

      // Add the agent provider as a bean
      app.addInitializers(
          ctx -> {
            ctx.getBeanFactory().registerSingleton("agentLoader", provider);
          });

      getLog().info("Starting Spring Boot application...");
      applicationContext = app.run(new String[0]);

      getLog().info("🎉 ADK Web Server started successfully!");
      getLog().info("🌐 Web UI available at: http://" + host + ":" + port);
      getLog().info("⏹️  Press Ctrl+C to stop the server...");
      getLog().info("");

      // Keep the server running until interrupted
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    getLog().info("Shutting down ADK Web Server...");
                    cleanupResources();
                  }));

      // Wait for shutdown signal
      Thread.currentThread().join();

    } catch (InterruptedException e) {
      getLog().info("Server interrupted, shutting down...");
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      cleanupResources();
      throw new MojoExecutionException("Failed to start ADK Web Server", e);
    }
  }

  /** Validates all plugin parameters for correctness and completeness. */
  private void validateParameters() throws MojoFailureException {
    if (agents == null || agents.trim().isEmpty()) {
      throw new MojoFailureException(
          "agents parameter is required. Usage: mvn google-adk:web"
              + " -Dagents=com.example.MyProvider.INSTANCE or -Dagents=/path/to/agents");
    }

    if (port < 1 || port > 65535) {
      throw new MojoFailureException("Port must be between 1 and 65535, got: " + port);
    }
  }

  /** Logs the current configuration for debugging purposes. */
  private void logConfiguration() {
    getLog().info("Configuration:");
    getLog().info("  Agent Provider: " + agents);
    getLog().info("  Server Host: " + host);
    getLog().info("  Server Port: " + port);
    getLog().info("  Registry: " + (registry != null ? registry : "default"));
  }

  private void setupSystemProperties() {
    System.setProperty("server.address", host);
    System.setProperty("server.port", String.valueOf(port));

    // Use custom loader instead of compiled loader
    System.setProperty("adk.agents.loader", "custom");
    getLog().debug("Set adk.agents.loader=custom");
  }

  /**
   * Creates a classloader that can access the user's project classes and dependencies.
   *
   * <p>This is necessary because the Maven plugin runs in its own classloader context, but needs to
   * load the user's AgentLoader class and its dependencies at runtime.
   *
   * @return URLClassLoader containing the user's compiled classes and runtime dependencies
   * @throws MojoExecutionException if project dependencies cannot be resolved
   */
  private URLClassLoader createProjectClassLoader() throws MojoExecutionException {
    try {
      List<String> classpathElements = new ArrayList<>();
      classpathElements.addAll(project.getCompileClasspathElements());
      classpathElements.addAll(project.getRuntimeClasspathElements());

      List<URL> classpathUrls = new ArrayList<>();
      for (String element : classpathElements) {
        try {
          classpathUrls.add(new URL("file:" + element + (element.endsWith(".jar") ? "" : "/")));
        } catch (MalformedURLException e) {
          getLog().warn("Skipping invalid classpath element: " + element);
        }
      }

      return new URLClassLoader(
          classpathUrls.toArray(URL[]::new), Thread.currentThread().getContextClassLoader());
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Failed to resolve project dependencies", e);
    }
  }

  private ComponentRegistry loadCustomRegistry() throws MojoExecutionException {
    // Try to interpret as class.field syntax
    if (registry.contains(".")) {
      ComponentRegistry customRegistry = tryLoadFromStaticField(registry, ComponentRegistry.class);
      if (customRegistry != null) {
        return customRegistry;
      }
    }

    // Fallback to trying the entire string as a class name
    return tryLoadFromConstructor(registry, ComponentRegistry.class);
  }

  /**
   * Attempts to load an instance from a static field reference.
   *
   * @param classAndField the class.field string to parse
   * @param expectedType the expected type/interface the instance should implement
   * @param <T> the expected type
   * @return instance of the specified type if successful, null if the field approach should be
   *     abandoned
   * @throws MojoExecutionException if field is found but has issues (wrong type, access problems)
   */
  private <T> T tryLoadFromStaticField(String classAndField, Class<T> expectedType)
      throws MojoExecutionException {
    int lastDotIndex = classAndField.lastIndexOf('.');
    String className = classAndField.substring(0, lastDotIndex);
    String fieldName = classAndField.substring(lastDotIndex + 1);

    try {
      Class<?> clazz = projectClassLoader.loadClass(className);
      Field field = clazz.getField(fieldName);

      Object instance = field.get(null);
      if (!expectedType.isInstance(instance)) {
        throw new MojoExecutionException(
            "Field "
                + fieldName
                + " in class "
                + className
                + " is not an instance of "
                + expectedType.getSimpleName());
      }
      return expectedType.cast(instance);

    } catch (ClassNotFoundException | NoSuchFieldException e) {
      // Field approach failed, return null to try constructor approach
      return null;
    } catch (IllegalAccessException e) {
      throw new MojoExecutionException(
          "Cannot access field " + fieldName + " in class " + className, e);
    }
  }

  /**
   * Attempts to load an instance by instantiating the class using its default constructor.
   *
   * @param className the name of the class to instantiate
   * @param expectedType the expected type/interface the instance should implement
   * @param <T> the expected type
   * @return instance of the specified type
   * @throws MojoExecutionException if class cannot be found or instantiated
   */
  private <T> T tryLoadFromConstructor(String className, Class<T> expectedType)
      throws MojoExecutionException {
    try {
      Class<?> clazz = projectClassLoader.loadClass(className);
      Object instance = clazz.getDeclaredConstructor().newInstance();
      if (!expectedType.isInstance(instance)) {
        throw new MojoExecutionException(
            "Class " + className + " does not implement/extend " + expectedType.getSimpleName());
      }
      return expectedType.cast(instance);

    } catch (ClassNotFoundException e) {
      throw new MojoExecutionException(
          expectedType.getSimpleName()
              + " class not found: "
              + className
              + ". Make sure the class is in your project's classpath.",
          e);
    } catch (Exception e) {
      throw new MojoExecutionException(
          "Failed to instantiate "
              + className
              + ". Make sure it has a public default constructor or use the field syntax: "
              + "com.example.MyClass.INSTANCE",
          e);
    }
  }

  private com.google.adk.web.AgentLoader loadAgentProvider() throws MojoExecutionException {
    // First, check if agents parameter is a directory path
    Path agentsPath = Paths.get(agents);
    if (Files.isDirectory(agentsPath)) {
      getLog().info("Detected directory path, using ConfigAgentLoader: " + agents);
      return new ConfigAgentLoader(agents, hotReloading);
    }

    // Next, try to interpret as class.field syntax
    if (agents.contains(".")) {
      com.google.adk.web.AgentLoader provider =
          tryLoadFromStaticField(agents, com.google.adk.web.AgentLoader.class);
      if (provider != null) {
        return provider;
      }
    }

    // Fallback to trying the entire string as a class name
    return tryLoadFromConstructor(agents, com.google.adk.web.AgentLoader.class);
  }

  /** Cleans up all resources including application context, classloader. */
  private void cleanupResources() {
    getLog().debug("Cleaning up resources...");

    // Close application context
    if (applicationContext != null) {
      try {
        applicationContext.close();
        getLog().debug("Application context closed");
      } catch (Exception e) {
        getLog().warn("Error closing application context", e);
      }
    }

    // Close project classloader
    if (projectClassLoader != null) {
      try {
        projectClassLoader.close();
        getLog().debug("Project classloader closed");
      } catch (IOException e) {
        getLog().warn("Error closing project classloader", e);
      }
    }

    // Clear system properties to avoid memory leaks
    clearSystemProperties();

    getLog().info("Resource cleanup completed");
  }

  /** Clears system properties set by this plugin to prevent memory leaks. */
  private void clearSystemProperties() {
    try {
      System.clearProperty("server.address");
      System.clearProperty("server.port");
      System.clearProperty("adk.agents.source-dir");
      System.clearProperty("spring.autoconfigure.exclude");
      getLog().debug("System properties cleared");
    } catch (Exception e) {
      getLog().warn("Error clearing system properties", e);
    }
  }
}
