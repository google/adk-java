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

package com.google.adk.web.config;

import com.google.adk.web.controller.httpserver.HttpServerSseController;
import com.google.adk.web.service.RunnerService;
import com.google.adk.web.service.eventprocessor.PassThroughEventProcessor;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for HttpServer-based SSE endpoints (default implementation).
 *
 * <p>This configuration starts the default HTTP server (using Java's HttpServer) that provides
 * zero-dependency SSE endpoints. The HttpServer implementation is the default, with Spring-based
 * endpoints available as an alternative.
 *
 * <p><b>Default Configuration:</b> HttpServer SSE is enabled by default. To disable, set:
 *
 * <pre>{@code
 * adk.httpserver.sse.enabled=false
 * }</pre>
 *
 * <p><b>Configuration Options:</b>
 *
 * <pre>{@code
 * # Enable/disable HttpServer SSE (default: true)
 * adk.httpserver.sse.enabled=true
 *
 * # Port for HttpServer (default: 9085)
 * adk.httpserver.sse.port=9085
 *
 * # Host to bind to (default: 0.0.0.0)
 * adk.httpserver.sse.host=0.0.0.0
 * }</pre>
 *
 * <p><b>Endpoints:</b>
 *
 * <ul>
 *   <li>POST http://localhost:9085/run_sse - Default SSE endpoint (HttpServer-based)
 *   <li>POST http://localhost:8080/run_sse_spring - Spring-based alternative (Spring Boot port)
 * </ul>
 *
 * <p><b>Note:</b> HttpServer SSE runs on port 9085 by default. Spring-based endpoint runs on the
 * Spring Boot server port (typically 8080).
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
@Configuration
@ConditionalOnProperty(
    name = "adk.httpserver.sse.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class HttpServerSseConfig {

  private static final Logger log = LoggerFactory.getLogger(HttpServerSseConfig.class);

  @Value("${adk.httpserver.sse.port:9085}")
  private int httpserverPort;

  @Value("${adk.httpserver.sse.host:0.0.0.0}")
  private String httpserverHost;

  @Autowired private RunnerService runnerService;

  @Autowired private PassThroughEventProcessor passThroughProcessor;

  private HttpServer httpServer;

  /**
   * Starts the HttpServer SSE server after Spring context is initialized.
   *
   * @throws IOException if the server cannot be started
   */
  @PostConstruct
  public void startHttpServer() throws IOException {
    log.info("Starting HttpServer SSE service on {}:{}", httpserverHost, httpserverPort);

    httpServer = HttpServer.create(new InetSocketAddress(httpserverHost, httpserverPort), 0);
    httpServer.setExecutor(Executors.newCachedThreadPool());

    // Register default SSE endpoint
    HttpServerSseController controller =
        new HttpServerSseController(runnerService, passThroughProcessor);
    httpServer.createContext("/run_sse", controller);

    httpServer.start();

    log.info(
        "HttpServer SSE service started successfully (default). Endpoint: http://{}:{}/run_sse",
        httpserverHost,
        httpserverPort);
  }

  /** Stops the HttpServer SSE server before Spring context is destroyed. */
  @PreDestroy
  public void stopHttpServer() {
    if (httpServer != null) {
      log.info("Stopping HttpServer SSE service...");
      httpServer.stop(0);
      log.info("HttpServer SSE service stopped");
    }
  }
}
