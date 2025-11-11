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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.BaseAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring Boot application for the Agent Server. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AdkWebServer implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(AdkWebServer.class);

  @Value("${adk.web.ui.dir:#{null}}")
  private String webUiDir;

  @Bean
  public BaseSessionService sessionService() {
    // TODO: Add logic to select service based on config (e.g., DB URL)
    log.info("Using InMemorySessionService");
    return new InMemorySessionService();
  }

  /**
   * Provides the singleton instance of the ArtifactService (InMemory). TODO: configure this based
   * on config (e.g., DB URL)
   *
   * @return An instance of BaseArtifactService (currently InMemoryArtifactService).
   */
  @Bean
  public BaseArtifactService artifactService() {
    log.info("Using InMemoryArtifactService");
    return new InMemoryArtifactService();
  }

  /**
   * Provides the singleton instance of the MemoryService (InMemory). Will be made configurable once
   * we have the Vertex MemoryService.
   *
   * @return An instance of BaseMemoryService (currently InMemoryMemoryService).
   */
  @Bean
  public BaseMemoryService memoryService() {
    log.info("Using InMemoryMemoryService");
    return new InMemoryMemoryService();
  }

  /**
   * Configures the Jackson ObjectMapper for JSON serialization. Uses the ADK standard mapper
   * configuration.
   *
   * @return Configured ObjectMapper instance
   */
  @Bean
  public ObjectMapper objectMapper() {
    return JsonBaseModel.getMapper();
  }

  /**
   * Configures resource handlers for serving static content (like the Dev UI). Maps requests
   * starting with "/dev-ui/" to the directory specified by the 'adk.web.ui.dir' system property.
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (webUiDir != null && !webUiDir.isEmpty()) {
      // Ensure the path uses forward slashes and ends with a slash
      String location = webUiDir.replace("\\", "/");
      if (!location.startsWith("file:")) {
        location = "file:" + location; // Ensure file: prefix
      }
      if (!location.endsWith("/")) {
        location += "/";
      }
      log.debug("Mapping URL path /** to static resources at location: {}", location);
      registry
          .addResourceHandler("/**")
          .addResourceLocations(location)
          .setCachePeriod(0)
          .resourceChain(true);

    } else {
      log.debug(
          "System property 'adk.web.ui.dir' or config 'adk.web.ui.dir' is not set. Mapping URL path"
              + " /** to classpath:/browser/");
      registry
          .addResourceHandler("/**")
          .addResourceLocations("classpath:/browser/")
          .setCachePeriod(0)
          .resourceChain(true);
    }
  }

  /**
   * Configures simple automated controllers: - Redirects the root path "/" to "/dev-ui". - Forwards
   * requests to "/dev-ui" to "/dev-ui/index.html" so the ResourceHandler serves it.
   */
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addRedirectViewController("/", "/dev-ui");
    registry.addViewController("/dev-ui").setViewName("forward:/index.html");
    registry.addViewController("/dev-ui/").setViewName("forward:/index.html");
  }

  /**
   * Main entry point for the Spring Boot application.
   *
   * @param args Command line arguments.
   */
  public static void main(String[] args) {
    // Increase the default websocket buffer size to 10MB to accommodate live API messages.
    System.setProperty(
        "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", String.valueOf(10 * 1024 * 1024));
    SpringApplication.run(AdkWebServer.class, args);
    log.info("AdkWebServer application started successfully.");
  }

  // TODO(vorburger): #later return Closeable, which can stop the server (and resets static)
  public static void start(BaseAgent... agents) {
    // Disable CompiledAgentLoader by setting property to prevent its creation
    System.setProperty("adk.agents.loader", "static");
    // Increase the default websocket buffer size to 10MB to accommodate live API messages.
    System.setProperty(
        "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE", String.valueOf(10 * 1024 * 1024));

    // Create Spring Application with custom initializer
    SpringApplication app = new SpringApplication(AdkWebServer.class);
    app.addInitializers(
        new ApplicationContextInitializer<ConfigurableApplicationContext>() {
          @Override
          public void initialize(ConfigurableApplicationContext context) {
            // Register the AgentStaticLoader bean before context refresh
            DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) context.getBeanFactory();
            beanFactory.registerSingleton("agentLoader", new AgentStaticLoader(agents));
          }
        });

    app.run(new String[0]);
  }
}
