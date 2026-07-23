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
package com.example.adkstale;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Configuration read from environment variables. Mirrors {@code settings.py} in the Python ADK
 * stale issue auditor.
 *
 * <p>Values are exposed as <b>accessor methods</b> (read lazily on each call) rather than {@code
 * static final} fields. This keeps the class loadable in unit tests and agent loaders without a
 * {@code GITHUB_TOKEN} present &mdash; only {@link #githubToken()} throws when the token is
 * actually required (i.e. right before a network call).
 *
 * <p>Required variables:
 *
 * <ul>
 *   <li>{@code GITHUB_TOKEN} &mdash; GitHub Personal Access Token (or the workflow's built-in
 *       token) with {@code issues:write}. Required for both interactive and workflow modes.
 *   <li>{@code GOOGLE_API_KEY} &mdash; Gemini API key. Required for both modes (or set up Vertex AI
 *       credentials and {@code GOOGLE_GENAI_USE_VERTEXAI=TRUE}).
 * </ul>
 *
 * <p>Optional variables (defaults match the Python sample unless noted):
 *
 * <ul>
 *   <li>{@code OWNER} / {@code REPO} &mdash; default to {@code google} / {@code adk-java}.
 *   <li>{@code MODEL} &mdash; Gemini model used for reasoning. Defaults to {@code
 *       gemini-flash-latest}; this is a high-volume, low-complexity classification task for which a
 *       Flash model is the right cost/latency trade-off (the Python sample uses {@code
 *       gemini-2.5-flash}). Overridable without a code change.
 *   <li>{@code INTERACTIVE} &mdash; {@code 1}/{@code true} for interactive mode (asks for
 *       confirmation before mutating issues), {@code 0}/{@code false} for unattended workflow mode.
 *       Defaults to interactive when unset.
 *   <li>{@code DRY_RUN} &mdash; {@code 1}/{@code true} to log intended comments/labels/closures
 *       without calling the GitHub mutation endpoints. Defaults to off.
 *   <li>{@code EVENT_NAME} &mdash; the GitHub event that triggered the workflow ({@code schedule},
 *       {@code workflow_dispatch}, {@code issues}, ...). Drives single-issue vs. batch behavior.
 *   <li>{@code ISSUE_NUMBER} &mdash; populated by GitHub Actions for issue events; audits that one
 *       issue.
 *   <li>{@code ISSUE_COUNT_TO_PROCESS} &mdash; max number of stale-candidate issues to audit per
 *       batch run. Defaults to {@code 20}.
 *   <li>{@code STALE_LABEL} &mdash; the label that marks an issue as stale. Defaults to {@code
 *       stale}. This label must exist in the repository.
 *   <li>{@code REQUEST_CLARIFICATION_LABEL} &mdash; label applied alongside {@code stale} when a
 *       maintainer asked for clarification. Defaults to {@code request clarification}.
 *   <li>{@code MAINTAINERS} &mdash; optional comma-separated list of GitHub handles to treat as
 *       maintainers. When set, it overrides the (push-access) collaborator lookup &mdash; useful
 *       when the token cannot list collaborators.
 *   <li>{@code STALE_HOURS_THRESHOLD} &mdash; hours of inactivity after a maintainer question
 *       before an issue is marked stale. Defaults to {@code 168} (7 days).
 *   <li>{@code CLOSE_HOURS_AFTER_STALE_THRESHOLD} &mdash; hours an issue may remain stale before it
 *       is closed. Defaults to {@code 168} (7 days).
 *   <li>{@code GRAPHQL_COMMENT_LIMIT} / {@code GRAPHQL_EDIT_LIMIT} / {@code GRAPHQL_TIMELINE_LIMIT}
 *       &mdash; how many recent comments / body edits / timeline events to fetch per issue. Default
 *       to {@code 30} / {@code 10} / {@code 20}.
 *   <li>{@code SLEEP_BETWEEN_ISSUES_MS} &mdash; pause between issues in a batch run to respect rate
 *       limits. Defaults to {@code 1500}.
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
   * Returns the Gemini model used for reasoning. Defaults to {@code gemini-flash-latest} (a Flash
   * model suits this high-volume, low-complexity decision task) and is overridable via the {@code
   * MODEL} environment variable.
   */
  public static String model() {
    return envOrDefault("MODEL", "gemini-flash-latest");
  }

  public static String staleLabel() {
    return envOrDefault("STALE_LABEL", "stale");
  }

  public static String requestClarificationLabel() {
    return envOrDefault("REQUEST_CLARIFICATION_LABEL", "request clarification");
  }

  public static @Nullable String eventName() {
    return System.getenv("EVENT_NAME");
  }

  public static @Nullable String issueNumber() {
    return System.getenv("ISSUE_NUMBER");
  }

  public static @Nullable String issueCountToProcess() {
    return System.getenv("ISSUE_COUNT_TO_PROCESS");
  }

  public static @Nullable String maintainersOverride() {
    return System.getenv("MAINTAINERS");
  }

  public static boolean isInteractive() {
    return parseTruthy(envOrDefault("INTERACTIVE", "1"));
  }

  public static boolean isDryRun() {
    return parseTruthy(envOrDefault("DRY_RUN", "0"));
  }

  /** Hours of inactivity after a maintainer question before an issue is marked stale. */
  public static double staleHoursThreshold() {
    return parseDouble(System.getenv("STALE_HOURS_THRESHOLD"), 168.0);
  }

  /** Hours an issue may stay marked stale before it is closed. */
  public static double closeHoursAfterStaleThreshold() {
    return parseDouble(System.getenv("CLOSE_HOURS_AFTER_STALE_THRESHOLD"), 168.0);
  }

  public static int graphqlCommentLimit() {
    return parseNumberString(System.getenv("GRAPHQL_COMMENT_LIMIT"), 30);
  }

  public static int graphqlEditLimit() {
    return parseNumberString(System.getenv("GRAPHQL_EDIT_LIMIT"), 10);
  }

  public static int graphqlTimelineLimit() {
    return parseNumberString(System.getenv("GRAPHQL_TIMELINE_LIMIT"), 20);
  }

  public static long sleepBetweenIssuesMs() {
    return parseNumberString(System.getenv("SLEEP_BETWEEN_ISSUES_MS"), 1500);
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

  /** Parses a double, falling back to {@code defaultValue} on null/blank/invalid input. */
  static double parseDouble(@Nullable String value, double defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(value.trim());
    } catch (NumberFormatException e) {
      System.err.printf(
          "Warning: Invalid number string: %s. Defaulting to %s.%n", value, defaultValue);
      return defaultValue;
    }
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isEmpty()) ? fallback : value;
  }
}
