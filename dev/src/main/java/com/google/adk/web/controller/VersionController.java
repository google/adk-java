/*
 * Copyright 2026 Google LLC
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

package com.google.adk.web.controller;

import com.google.adk.Version;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the ADK and language versions, which the dev UI requests on startup, plus a liveness
 * endpoint.
 */
@RestController
public class VersionController {

  /** Returns the ADK version, the implementation language, and the running JVM's version. */
  @GetMapping(value = "/version", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, String> version() {
    return Map.of(
        "version",
        Version.JAVA_ADK_VERSION,
        "language",
        "java",
        "language_version",
        System.getProperty("java.version", "unknown"));
  }

  /** Returns a fixed OK status, so a load balancer can tell the server is up. */
  @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public Map<String, String> health() {
    return Map.of("status", "ok");
  }
}
