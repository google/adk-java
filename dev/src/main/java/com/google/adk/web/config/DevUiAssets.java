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

package com.google.adk.web.config;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ResourceLoader;

/**
 * Where the dev UI's static assets live. Normalizes {@code adk.web.ui.dir} into a resource
 * location, so callers that need it do not each do it differently.
 */
public final class DevUiAssets {

  private static final String CLASSPATH_ROOT = ResourceLoader.CLASSPATH_URL_PREFIX + "/browser/";

  /**
   * The asset root: {@code webUiDir} as a {@code file:} URL when set, else the bundled classpath
   * copy. Always ends in a slash.
   */
  public static String assetRoot(@Nullable String webUiDir) {
    if (webUiDir == null || webUiDir.isEmpty()) {
      return CLASSPATH_ROOT;
    }
    String location = webUiDir.replace("\\", "/");
    if (!location.startsWith("file:")) {
      location = "file:" + location;
    }
    return location.endsWith("/") ? location : location + "/";
  }

  private DevUiAssets() {}
}
