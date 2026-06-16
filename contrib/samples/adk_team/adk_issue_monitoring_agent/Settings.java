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
package com.example.adk_team.adk_issue_monitoring_agent;

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Configuration sourced from environment variables. Mirrors {@code settings.py} in the Python ADK
 * issue monitoring agent.
 *
 * <ul>
 *   <li>{@code GITHUB_TOKEN} (required for network calls) &mdash; token with {@code issues:write}.
 *   <li>{@code GOOGLE_API_KEY} (required) &mdash; Gemini API key (read by the ADK model layer).
 *   <li>{@code OWNER}/{@code REPO} &mdash; default {@code google}/{@code adk-java}.
 *   <li>{@code LLM_MODEL_NAME} &mdash; default {@code gemini-2.5-flash}.
 *   <li>{@code SPAM_LABEL_NAME} &mdash; default {@code spam}.
 *   <li>{@code BOT_NAME} &mdash; the bot whose comments are ignored; default {@code adk-bot}.
 *   <li>{@code BOT_ALERT_SIGNATURE} &mdash; signature stamped on alerts (for idempotency).
 *   <li>{@code CONCURRENCY_LIMIT} &mdash; issues processed concurrently; default {@code 3}.
 *   <li>{@code INITIAL_FULL_SCAN} &mdash; {@code 1} to audit all open issues, else last 24h.
 *   <li>{@code DRY_RUN} &mdash; {@code 1} to log intended changes without writing.
 * </ul>
 */
final class Settings {

  static final @Nullable String GITHUB_TOKEN = System.getenv("GITHUB_TOKEN");
  static final String OWNER = envOrDefault("OWNER", "google");
  static final String REPO = envOrDefault("REPO", "adk-java");
  static final String MODEL = envOrDefault("LLM_MODEL_NAME", "gemini-2.5-flash");
  static final String SPAM_LABEL = envOrDefault("SPAM_LABEL_NAME", "spam");
  static final String BOT_NAME = envOrDefault("BOT_NAME", "adk-bot");
  static final String BOT_ALERT_SIGNATURE =
      envOrDefault(
          "BOT_ALERT_SIGNATURE", "\uD83D\uDEA8 **Automated Spam Detection Alert** \uD83D\uDEA8");
  static final int CONCURRENCY_LIMIT = parsePositiveInt(System.getenv("CONCURRENCY_LIMIT"), 3);
  static final boolean INITIAL_FULL_SCAN = parseTruthy(System.getenv("INITIAL_FULL_SCAN"));
  static final boolean DRY_RUN = parseTruthy(System.getenv("DRY_RUN"));

  private Settings() {}

  // ---- Pure helpers (package-private for unit testing) ----

  /** Returns true if {@code value} is one of {@code 1/true/yes/on} (case-insensitive). */
  static boolean parseTruthy(@Nullable String value) {
    return value != null
        && Set.of("1", "true", "yes", "on").contains(value.trim().toLowerCase(Locale.ROOT));
  }

  /** Parses a positive int, falling back to {@code fallback} on null/blank/invalid/non-positive. */
  static int parsePositiveInt(@Nullable String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String envOrDefault(String name, String fallback) {
    String value = System.getenv(name);
    return (value == null || value.isEmpty()) ? fallback : value;
  }
}
