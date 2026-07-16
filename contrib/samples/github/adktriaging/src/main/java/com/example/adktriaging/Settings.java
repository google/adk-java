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
package com.example.adktriaging;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Configuration read from environment variables. Mirrors {@code settings.py} in the Python ADK
 * issue triaging agent.
 *
 * <p>Values are exposed as <b>accessor methods</b> (read lazily on each call) rather than {@code
 * static final} fields. This keeps the class loadable in unit tests and agent loaders without a
 * {@code GITHUB_TOKEN} present &mdash; only {@link #githubToken()} throws when the token is
 * actually required (i.e. right before a network call).
 *
 * <p>Required variables:
 *
 * <ul>
 *   <li>{@code GITHUB_TOKEN} &mdash; GitHub Personal Access Token with {@code issues:write}
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
 *   <li>{@code MODEL} &mdash; Gemini model used for triaging. Defaults to {@code gemini-2.5-pro}; a
 *       Pro model favors classification quality over latency, which suits this low-volume,
 *       accuracy-sensitive task. Overridable without a code change.
 *   <li>{@code INTERACTIVE} &mdash; {@code 1}/{@code true} for interactive mode (asks for
 *       confirmation before applying labels), {@code 0}/{@code false} for unattended workflow mode.
 *       Defaults to interactive when unset.
 *   <li>{@code DRY_RUN} &mdash; {@code 1}/{@code true} to log intended label/assignment changes
 *       without calling the GitHub mutation endpoints. Lets you verify the full pipeline (incl.
 *       Gemini) without modifying any real issue. Defaults to off.
 *   <li>{@code EVENT_NAME} &mdash; the GitHub event that triggered the workflow ({@code issues},
 *       {@code schedule}, etc.). Drives single-issue vs. batch behavior in {@link
 *       AdkTriagingAgentRun}.
 *   <li>{@code ISSUE_NUMBER}, {@code ISSUE_TITLE}, {@code ISSUE_BODY} &mdash; populated by the
 *       GitHub Actions workflow when the trigger is an issue event.
 *   <li>{@code ISSUE_COUNT_TO_PROCESS} &mdash; how many untriaged issues to process per scheduled
 *       run. Defaults to {@code 3}.
 *   <li>{@code GTECH_ASSIGNEES} &mdash; comma-separated list of GitHub handles to round-robin
 *       assign issues to. When unset, owner assignment is disabled (the agent reports that no
 *       triagers are configured). adk-java has no public {@code CODEOWNERS}, so real handles are
 *       supplied here rather than hard-coded in source.
 * </ul>
 */
public final class Settings {

  /** Truthy strings accepted by boolean env vars. Matches the Python settings logic. */
  private static final Set<String> TRUTHY = Set.of("1", "true", "yes", "on");

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

  public static @Nullable String issueNumber() {
    return System.getenv("ISSUE_NUMBER");
  }

  public static @Nullable String issueTitle() {
    return System.getenv("ISSUE_TITLE");
  }

  public static @Nullable String issueBody() {
    return System.getenv("ISSUE_BODY");
  }

  public static @Nullable String issueCountToProcess() {
    return System.getenv("ISSUE_COUNT_TO_PROCESS");
  }

  public static @Nullable String gtechAssignees() {
    return System.getenv("GTECH_ASSIGNEES");
  }

  public static boolean isInteractive() {
    return parseTruthy(envOrDefault("INTERACTIVE", "1"));
  }

  public static boolean isDryRun() {
    return parseTruthy(envOrDefault("DRY_RUN", "0"));
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
