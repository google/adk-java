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
import com.google.adk.web.config.BackendUrl;
import com.google.adk.web.config.DevUiAssets;
import org.jspecify.annotations.Nullable;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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

  @Value("${adk.web.backend-url:}")
  private String backendUrlProperty;

  private @Nullable BackendUrl parsedBackendUrl;

  /** Parsed here once, and shared, so this and the runtime-config endpoint cannot diverge. */
  @Bean
  public synchronized BackendUrl backendUrl() {
    if (parsedBackendUrl == null) {
      parsedBackendUrl = BackendUrl.from(backendUrlProperty);
    }
    return parsedBackendUrl;
  }

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
  @Primary
  public ObjectMapper objectMapper() {
    return JsonBaseModel.getMapper();
  }

  /**
   * Configures the message converter to use the custom ADK ObjectMapper. This ensures that Spring
   * Web uses the correct JSON serialization settings (like omitting absent optional fields) and
   * prevents double-serialization issues, particularly for Server-Sent Events (SSE).
   *
   * @param objectMapper The primary ObjectMapper configured for the ADK.
   * @return A configured MappingJackson2HttpMessageConverter.
   */
  @Bean
  public MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter(
      ObjectMapper objectMapper) {
    return new MappingJackson2HttpMessageConverter(objectMapper);
  }

  /**
   * Maps requests under "/dev-ui/" to the directory named by the 'adk.web.ui.dir' property, or to
   * the bundled copy on the classpath when that is unset.
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String location = DevUiAssets.assetRoot(webUiDir);
    log.debug("Mapping URL path /dev-ui/** to static resources at location: {}", location);
    registry
        .addResourceHandler("/dev-ui/**")
        .addResourceLocations(location)
        .setCachePeriod(0)
        .resourceChain(true);
  }

  /**
   * Configures simple automated controllers: "/" and "/dev-ui" both redirect to the UI, at {@code
   * adk.web.backend-url}'s path when that is set, and it forwards to index.html. The trailing slash
   * is required: index.html declares a {@code <base href="./">}, so served from "/dev-ui" the app
   * resolves its own router path to "dev-ui" and matches none of its routes. The query string is
   * carried across because the UI selects its agent from {@code ?app=}.
   */
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    String prefix = backendUrl().pathPrefix();
    // The configured value is the public base, so do not stack the context path on it.
    boolean contextRelative = prefix.isEmpty();
    registry
        .addRedirectViewController("/", prefix + "/dev-ui/")
        .setKeepQueryParams(true)
        .setContextRelative(contextRelative);
    registry
        .addRedirectViewController("/dev-ui", prefix + "/dev-ui/")
        .setKeepQueryParams(true)
        .setContextRelative(contextRelative);
    registry.addViewController("/dev-ui/").setViewName("forward:/dev-ui/index.html");
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
