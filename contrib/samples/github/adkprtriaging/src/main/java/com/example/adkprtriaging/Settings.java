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
package com.example.adkprtriaging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Configuration read from environment variables. Mirrors {@code settings.py} in the Python ADK PR
 * triaging agent, and matches the lazy-accessor style of the sibling ADK Issue Triaging Agent
 * sample.
 *
 * <p>Values are exposed as <b>accessor methods</b> (read lazily on each call) rather than {@code
 * static final} fields. This keeps the class loadable in unit tests and {@code adk web} agent
 * loaders without a {@code GITHUB_TOKEN} present &mdash; only {@link #githubToken()} throws when
 * the token is actually required (i.e. right before a network call).
 *
 * <p>Required variables:
 *
 * <ul>
 *   <li>{@code GITHUB_TOKEN} &mdash; GitHub Personal Access Token with {@code pull_requests:write}
 *       permission. Required for both interactive and workflow modes.
 *   <li>{@code GOOGLE_API_KEY} &mdash; Gemini API key. Required for both modes (or set up Vertex AI
 *       credentials and {@code GOOGLE_GENAI_USE_VERTEXAI=TRUE}).
 * </ul>
 *
 * <p>Optional variables:
 *
 * <ul>
 *   <li>{@code OWNER} &mdash; defaults to {@code google}.
 *   <li>{@code REPO} &mdash; defaults to {@code adk-java}.
 *   <li>{@code MODEL} &mdash; Gemini model used for triaging. Defaults to {@code
 *       gemini-pro-latest}; a Pro model favors classification quality over latency, which suits
 *       this low-volume, accuracy-sensitive task. Overridable without a code change.
 *   <li>{@code INTERACTIVE} &mdash; {@code 1}/{@code true} for interactive mode (asks for
 *       confirmation before labeling/commenting), {@code 0}/{@code false} for unattended workflow
 *       mode. Defaults to interactive when unset.
 *   <li>{@code DRY_RUN} &mdash; {@code 1}/{@code true} to log intended label/comment changes
 *       without calling the GitHub mutation endpoints. Lets you verify the full pipeline (incl.
 *       Gemini) without modifying any real pull request. Defaults to off.
 *   <li>{@code PULL_REQUEST_NUMBER} &mdash; the pull request to triage in workflow mode. Populated
 *       by the GitHub Actions workflow from the triggering PR.
 *   <li>{@code EVENT_NAME} &mdash; the GitHub event that triggered the workflow ({@code
 *       pull_request_target}, {@code workflow_dispatch}, ...). Logged for diagnostics.
 *   <li>{@code CONTRIBUTING_MD_PATH} &mdash; path to the repository's {@code CONTRIBUTING.md}
 *       (default {@code CONTRIBUTING.md}, resolved against the working directory, which is the repo
 *       root in the workflow). Its content is embedded in the agent instruction so the guideline
 *       check stays in sync with the repo. Missing file is tolerated (empty content).
 * </ul>
 */
public final class Settings {

  /** Truthy strings accepted by boolean env vars. Matches the Python settings logic. */
  private static final Set<String> TRUTHY = Set.of("1", "true", "yes", "on");

  /** Upper bound on the embedded {@code CONTRIBUTING.md} so the instruction stays a sane size. */
  private static final int MAX_CONTRIBUTING_CHARS = 8_000;

  private Settings() {}

  /** Returns the GitHub token, throwing a clear error if it is not configured. */
  public static String githubToken() {
    String value = System.getenv("GITHUB_TOKEN");
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("GITHUB_TOKEN environment variable not set");
    }
    return value;
  }

  /** Returns true if a {@code GITHUB_TOKEN} is configured, without throwing. */
  public static boolean hasGithubToken() {
    String value = System.getenv("GITHUB_TOKEN");
    return value != null && !value.isEmpty();
  }

  public static String owner() {
    return envOrDefault("OWNER", "google");
  }

  public static String repo() {
    return envOrDefault("REPO", "adk-java");
  }

  /**
   * Returns the Gemini model used for triaging. Defaults to {@code gemini-pro-latest} (a Pro model
   * favors classification quality over latency for this low-volume, accuracy-sensitive task) and is
   * overridable via the {@code MODEL} environment variable, so it can be changed without editing
   * source.
   */
  public static String model() {
    return envOrDefault("MODEL", "gemini-pro-latest");
  }

  public static @Nullable String eventName() {
    return System.getenv("EVENT_NAME");
  }

  public static @Nullable String pullRequestNumber() {
    return System.getenv("PULL_REQUEST_NUMBER");
  }

  public static boolean isInteractive() {
    return parseTruthy(envOrDefault("INTERACTIVE", "1"));
  }

  public static boolean isDryRun() {
    return parseTruthy(envOrDefault("DRY_RUN", "0"));
  }

  /**
   * Reads the repository's {@code CONTRIBUTING.md} (path overridable via {@code
   * CONTRIBUTING_MD_PATH}) so the agent can check pull requests against the real contribution
   * guidelines, mirroring the Python agent's {@code read_file(CONTRIBUTING.md)}. Returns an empty
   * string if the file cannot be read (e.g. in unit tests or when the working directory is not the
   * repo root), so callers never need to handle an exception.
   */
  public static String contributingGuidelines() {
    String path = envOrDefault("CONTRIBUTING_MD_PATH", "CONTRIBUTING.md");
    try {
      String content = Files.readString(Path.of(path), StandardCharsets.UTF_8);
      return content.length() > MAX_CONTRIBUTING_CHARS
          ? content.substring(0, MAX_CONTRIBUTING_CHARS)
          : content;
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  // ---- Pure helpers (package-private for unit testing) ----

  /** Returns true if {@code value} is one of the recognized truthy tokens (case-insensitive). */
  static boolean parseTruthy(@Nullable String value) {
    return value != null && TRUTHY.contains(value.toLowerCase(Locale.ROOT));
  }

  /**
   * Parses a number from a string, falling back to {@code defaultValue} on null/blank/invalid
   * input. Mirrors {@code parse_number_string} in the Python utils.
   */
  public static int parseNumberString(@Nullable String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      System.err.printf(
          "Warning: Invalid number string: %s. Defaulting to %d.%n", value, defaultValue);
      return defaultValue;
    }
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isEmpty()) ? fallback : value;
  }
}
