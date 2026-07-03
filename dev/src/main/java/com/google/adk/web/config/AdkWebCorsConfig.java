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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>CORS allows the application to handle requests from different origins, enabling secure
 * communication between the frontend and backend services.
 *
 * <p>Beans provided:
 *
 * <ul>
 *   <li>{@link CorsConfigurationSource}: Configures CORS settings such as allowed origins, methods,
 *       headers, credentials, and max age.
 *   <li>{@link CorsFilter}: Applies the CORS configuration to incoming requests.
 * </ul>
 */
@Configuration
public class AdkWebCorsConfig {

  private static final Logger logger = LoggerFactory.getLogger(AdkWebCorsConfig.class);

  @Bean
  public CorsConfigurationSource corsConfigurationSource(AdkWebCorsProperties corsProperties) {
    if (corsProperties.origins().contains("*")) {
      logger.warn(
          "CORS is configured to allow all origins (\"*\"), which is insecure and intended for"
              + " local development only. This also applies to the /run_live WebSocket endpoint."
              + " Set 'adk.web.cors.origins' to an explicit allowlist to restrict which origins"
              + " may call the server.");
    }

    CorsConfiguration configuration = new CorsConfiguration();

    configuration.setAllowedOrigins(corsProperties.origins());
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
