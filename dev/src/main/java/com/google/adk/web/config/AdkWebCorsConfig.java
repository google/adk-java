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

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Configuration class for setting up Cross-Origin Resource Sharing (CORS) in the ADK Web
 * application. This class defines beans for configuring CORS settings based on properties defined
 * in {@link AdkWebCorsProperties}.
 *
 * <p>When no explicit origin allowlist is configured, this defaults to the server's own loopback
 * origins (localhost / 127.0.0.1 / [::1] on the configured port) instead of allowing every origin,
 * since allowing "*" by default lets any website a developer's browser visits call this server
 * while it is running. Set 'adk.web.cors.origins' explicitly to widen this.
 */
@Configuration
public class AdkWebCorsConfig {

  private static final Logger logger = LoggerFactory.getLogger(AdkWebCorsConfig.class);

  @Value("${server.port:8000}")
  private int serverPort;

  @Bean
  public CorsConfigurationSource corsConfigurationSource(AdkWebCorsProperties corsProperties) {
    List<String> origins = corsProperties.origins();

    if (origins.isEmpty()) {
      origins =
          List.of(
              "http://localhost:" + serverPort,
              "http://127.0.0.1:" + serverPort,
              "http://[::1]:" + serverPort);
      logger.info(
          "No 'adk.web.cors.origins' configured; defaulting to this server's own loopback"
              + " origins on port {}. Set 'adk.web.cors.origins' explicitly to allow other"
              + " origins.",
          serverPort);
    } else if (origins.contains("*")) {
      logger.warn(
          "CORS is configured to allow all origins (\"*\"), which is insecure and lets any"
              + " website call this server, including the /run_live WebSocket endpoint. Only"
              + " set this if you understand the risk.");
    }

    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOrigins(origins);
    configuration.setAllowedMethods(corsProperties.methods());
    configuration.setAllowedHeaders(corsProperties.headers());
    configuration.setAllowCredentials(corsProperties.allowCredentials());
    configuration.setMaxAge(corsProperties.maxAge());

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration(corsProperties.mapping(), configuration);

    return source;
  }

  @Bean
  public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
    return new CorsFilter(corsConfigurationSource);
  }
}
