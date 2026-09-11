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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.web.config.BackendUrl;
import com.google.adk.web.config.DevUiAssets;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the dev UI's runtime configuration, shadowing the copy bundled in the static assets so
 * {@code adk.web.backend-url} can point the UI at the address browsers reach this server on. The
 * bundled document is merged rather than replaced, so keys the UI gains in a later bundle survive.
 */
@RestController
public class RuntimeConfigController {

  private static final Logger log = LoggerFactory.getLogger(RuntimeConfigController.class);

  private final ResourceLoader resourceLoader;
  private final ObjectMapper objectMapper;
  private final @Nullable String webUiDir;
  private final String backendUrl;

  /** Reads the bundled config through {@code resourceLoader}, or from {@code webUiDir} if set. */
  @Autowired
  public RuntimeConfigController(
      ResourceLoader resourceLoader,
      ObjectMapper objectMapper,
      @Value("${adk.web.ui.dir:#{null}}") @Nullable String webUiDir,
      BackendUrl backendUrl) {
    this.resourceLoader = resourceLoader;
    this.objectMapper = objectMapper;
    this.webUiDir = webUiDir;
    this.backendUrl = backendUrl.value();
  }

  /** Serves the bundled config with {@code backendUrl} taken from configuration when set. */
  @GetMapping(
      value = "/dev-ui/" + DevUiAssets.RUNTIME_CONFIG_PATH,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> runtimeConfig() {
    Map<String, Object> config = readBundledConfig();
    // Unset leaves a value the bundled document already carries, which is what used to be served.
    if (backendUrl.isEmpty()) {
      config.putIfAbsent("backendUrl", "");
    } else {
      config.put("backendUrl", backendUrl);
    }
    // The bundled document can change on disk under adk.web.ui.dir, so do not let it be cached.
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(config);
  }

  /**
   * The bundled config, or an empty document when it is absent or unreadable. A dev UI that cannot
   * read its own config is worse than one whose extra keys defaulted, so this never fails the
   * request.
   */
  private Map<String, Object> readBundledConfig() {
    Resource resource =
        resourceLoader.getResource(
            DevUiAssets.assetLocation(webUiDir, DevUiAssets.RUNTIME_CONFIG_PATH));
    if (!resource.exists()) {
      log.debug("No bundled dev UI runtime config at {}; serving backendUrl only.", resource);
      return new LinkedHashMap<>();
    }
    try (InputStream in = resource.getInputStream()) {
      Map<String, Object> parsed = objectMapper.readValue(in, new TypeReference<>() {});
      return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
    } catch (IOException e) {
      log.warn("Could not read the bundled dev UI runtime config at {}.", resource, e);
      return new LinkedHashMap<>();
    }
  }
}
