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
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Properties for configuring CORS in ADK Web. This class is used to load CORS settings from
 * application properties.
 */
@ConfigurationProperties(prefix = "adk.web.cors")
public record AdkWebCorsProperties(
    String mapping,
    List<String> origins,
    List<String> methods,
    List<String> headers,
    boolean allowCredentials,
    long maxAge) {

  public AdkWebCorsProperties {
    mapping = mapping != null ? mapping : "/**";
    origins = origins != null && !origins.isEmpty() ? origins : List.of("*");
    methods =
        methods != null && !methods.isEmpty()
            ? methods
            : List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    headers = headers != null && !headers.isEmpty() ? headers : List.of("*");
    maxAge = maxAge > 0 ? maxAge : 3600;
  }
}
