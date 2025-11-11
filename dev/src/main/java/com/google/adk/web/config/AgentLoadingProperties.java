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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Properties for loading agents. */
@Component
@ConfigurationProperties(prefix = "adk.agents")
public class AgentLoadingProperties {
  private String sourceDir = ".";
  private String[] buildOutputDirs = {"target/classes", "build/classes/java/main", "build/classes"};

  public String getSourceDir() {
    return sourceDir;
  }

  public void setSourceDir(String sourceDir) {
    this.sourceDir = sourceDir;
  }

  public String[] getBuildOutputDirs() {
    return buildOutputDirs;
  }

  public void setBuildOutputDirs(String[] buildOutputDirs) {
    this.buildOutputDirs = buildOutputDirs;
  }
}
