// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.example.adkdocs;

/** Configuration sourced from environment variables. */
final class Settings {

  /** GitHub token with {@code issues:write} on the docs repository. */
  static final String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");

  static final String DOC_OWNER = envOrDefault("DOC_OWNER", "google");
  static final String CODE_OWNER = envOrDefault("CODE_OWNER", "google");
  static final String DOC_REPO = envOrDefault("DOC_REPO", "adk-docs");
  static final String CODE_REPO = envOrDefault("CODE_REPO", "adk-java");

  /** Implementation language documented by this analyzer; docs stay single-language. */
  static final String CODE_LANGUAGE = envOrDefault("CODE_LANGUAGE", "Java");

  /** Only changes under this path in the code repo are analyzed. */
  static final String CODE_SOURCE_PATH_FILTER =
      envOrDefault("CODE_SOURCE_PATH_FILTER", "core/src/main/java/");

  static final String MODEL = envOrDefault("MODEL", "gemini-pro-latest");

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isEmpty()) ? fallback : value;
  }

  private Settings() {}
}
