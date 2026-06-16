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

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.HttpException;

/**
 * Thin GitHub helper backed by the {@code org.kohsuke:github-api} client, which handles auth,
 * pagination, and JSON so this sample doesn't hand-roll a REST client.
 */
final class GitHubUtils {

  /**
   * Set when a <em>systemic</em> GitHub failure (auth, rate limit, network, or 5xx) is seen. Tool
   * calls turn exceptions into {@code {"status":"error"}} envelopes for the model, so without this
   * an unattended run could exit 0 (green) having done nothing. The entry point checks {@link
   * #hadSystemicFailure()} and exits non-zero.
   */
  private static final AtomicBoolean SYSTEMIC_FAILURE_SEEN = new AtomicBoolean(false);

  private static volatile GitHub client;

  private GitHubUtils() {}

  /** Lazily builds and caches a GitHub client authenticated with the configured token. */
  static GitHub connect() throws IOException {
    GitHub gh = client;
    if (gh == null) {
      synchronized (GitHubUtils.class) {
        if (client == null) {
          GitHubBuilder builder = new GitHubBuilder();
          if (Settings.GITHUB_TOKEN != null && !Settings.GITHUB_TOKEN.isEmpty()) {
            builder = builder.withOAuthToken(Settings.GITHUB_TOKEN);
          }
          client = builder.build();
        }
        gh = client;
      }
    }
    return gh;
  }

  /** Returns the configured {@code OWNER/REPO} repository. */
  static GHRepository repository() throws IOException {
    return connect().getRepository(Settings.OWNER + "/" + Settings.REPO);
  }

  // ---- High-level fetchers ----

  /**
   * Returns the logins of the repository's collaborators (whose comments are never flagged).
   * Listing collaborators needs push access, which the default Actions {@code GITHUB_TOKEN} often
   * lacks; a permission error ({@code 403}/{@code 404}) is tolerated by returning an empty list (so
   * every non-bot commenter is reviewed) rather than failing the whole run.
   */
  static List<String> getMaintainers() throws IOException {
    boolean priorFailure = SYSTEMIC_FAILURE_SEEN.get();
    try {
      return new ArrayList<>(repository().getCollaboratorNames());
    } catch (HttpException e) {
      if (e.getResponseCode() == 403 || e.getResponseCode() == 404) {
        if (!priorFailure) {
          SYSTEMIC_FAILURE_SEEN.set(false); // tolerated; this call alone must not fail the run
        }
        System.err.printf(
            "Warning: cannot list collaborators (HTTP %d); proceeding without maintainer"
                + " filtering. Grant the token push access to enable it.%n",
            e.getResponseCode());
        return new ArrayList<>();
      }
      throw e;
    }
  }

  /**
   * Returns the numbers of open issues to audit. When {@code fullScan} is true, all open issues;
   * otherwise only those updated in the last 24 hours. Pull requests and issues already carrying
   * the spam label are skipped.
   */
  static List<Integer> getTargetIssues(boolean fullScan) throws IOException {
    Date cutoff = fullScan ? null : Date.from(Instant.now().minus(Duration.ofDays(1)));
    List<Integer> result = new ArrayList<>();
    for (GHIssue issue : repository().getIssues(GHIssueState.OPEN)) {
      if (issue.isPullRequest()) {
        continue;
      }
      if (cutoff != null && issue.getUpdatedAt().before(cutoff)) {
        continue;
      }
      if (hasLabel(issue, Settings.SPAM_LABEL)) {
        continue;
      }
      result.add(issue.getNumber());
    }
    return result;
  }

  static GHIssue getIssue(int number) throws IOException {
    return repository().getIssue(number);
  }

  static List<GHIssueComment> getComments(GHIssue issue) throws IOException {
    return issue.getComments();
  }

  static void addLabel(GHIssue issue, String label) throws IOException {
    issue.addLabels(label);
  }

  static void comment(GHIssue issue, String body) throws IOException {
    issue.comment(body);
  }

  /**
   * Returns true if the issue carries a label whose name equals {@code labelName} (ignoring case).
   */
  static boolean hasLabel(GHIssue issue, String labelName) throws IOException {
    for (GHLabel label : issue.getLabels()) {
      if (labelName.equalsIgnoreCase(label.getName())) {
        return true;
      }
    }
    return false;
  }

  // ---- Error reporting / systemic-failure tracking ----

  /** Canonical error envelope returned by tools. */
  static ImmutableMap<String, Object> errorResponse(String message) {
    return ImmutableMap.of("status", "error", "message", message);
  }

  /** Returns true if any systemic GitHub failure was observed during this process. */
  static boolean hadSystemicFailure() {
    return SYSTEMIC_FAILURE_SEEN.get();
  }

  /** Records a systemic failure (auth, rate limit, network, or 5xx) from a caught exception. */
  static void noteFailure(Throwable t) {
    if (t instanceof HttpException httpException) {
      if (isSystemicStatus(httpException.getResponseCode())) {
        SYSTEMIC_FAILURE_SEEN.set(true);
      }
    } else if (t instanceof IOException) {
      // Connectivity-level failures (DNS, timeouts, resets) are systemic, not domain outcomes.
      SYSTEMIC_FAILURE_SEEN.set(true);
    }
  }

  /** Resets the systemic-failure flag. Exposed for unit tests. */
  static void clearSystemicFailureForTesting() {
    SYSTEMIC_FAILURE_SEEN.set(false);
  }

  /**
   * Returns true for HTTP statuses that indicate a systemic (run-wide) failure: auth ({@code
   * 401}/{@code 403}), rate limit ({@code 429}), or server error ({@code 5xx}). Pure, so it is
   * directly unit-testable.
   */
  static boolean isSystemicStatus(int statusCode) {
    return statusCode == 401 || statusCode == 403 || statusCode == 429 || statusCode >= 500;
  }
}
