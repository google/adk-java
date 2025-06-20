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

package com.google.adk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Version {
  private static final Logger logger = LoggerFactory.getLogger(Version.class);

  public static final String JAVA_ADK_VERSION;

  static {
    String version = "unknown";
    try (InputStream input =
        Version.class.getClassLoader().getResourceAsStream("version.properties")) {
      if (input != null) {
        Properties properties = new Properties();
        properties.load(input);
        version = properties.getProperty("version", "unknown");
      } else {
        logger.warn("version.properties file not found in classpath");
      }
    } catch (IOException e) {
      logger.warn("Failed to load version from properties file", e);
    }
    JAVA_ADK_VERSION = version;
  }

  private Version() {}
}
