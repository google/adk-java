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
package com.example.adkstale;

import com.example.github.GitHubTools;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

/**
 * Entry point for the ADK Java Stale Issue Auditor. Mirrors {@code main.py} in the Python sample,
 * and follows the {@code *Run} entry-point convention of the ADK Issue Triaging Agent sample.
 *
 * <p>The runtime mode is selected by environment variables:
 *
 * <ul>
 *   <li><b>GitHub Actions workflow mode</b> (set {@code INTERACTIVE=0}): one-shot run.
 *       <ul>
 *         <li>If {@code ISSUE_NUMBER} is set &rarr; audit that single issue.
 *         <li>Otherwise &rarr; search for issues old enough to be stale candidates and audit up to
 *             {@code ISSUE_COUNT_TO_PROCESS} (default 20) of them.
 *       </ul>
 *   <li><b>Interactive console mode</b> (default; {@code INTERACTIVE=1}): a Scanner-based REPL. The
 *       system instruction tells the agent to ask for confirmation before mutating an issue. For a
 *       richer UI, the {@code google-adk-maven-plugin}'s {@code web} goal can serve this agent (see
 *       this module's README for the exact command).
 * </ul>
 *
 * <p>All GitHub writes go through the shared {@link GitHubTools}, whose {@link GitHubTools#dryRun}/
 * {@link GitHubTools#writeRepoOwner}/{@link GitHubTools#writeRepoName} guards are configured here
 * so untrusted issue content cannot redirect writes to another repository.
 */
public final class AdkStaleAgentRun {

  private static final String APP_NAME = "adk_stale_app";
  private static final String USER_ID = "adk_stale_user";

  private AdkStaleAgentRun() {}

  public static void main(String[] args) {
    if (!Settings.hasGithubToken()) {
      throw new IllegalStateException(
          "GITHUB_TOKEN environment variable is not set. Set it before running.");
    }
    // Route all writes through GitHubTools and restrict them to the configured repository so
    // untrusted issue content cannot redirect a comment/label/close to another repo.
    GitHubTools.dryRun = Settings.isDryRun();
    GitHubTools.writeRepoOwner = Settings.owner();
    GitHubTools.writeRepoName = Settings.repo();

    Instant start = Instant.now();
    System.out.printf(
        "Start auditing %s/%s issues at %s%n", Settings.owner(), Settings.repo(), start);
    if (Settings.isDryRun()) {
      System.out.println(
          "DRY_RUN is enabled: no comments, labels or closures will actually be written.");
    }
    System.out.println("-".repeat(80));

    InMemoryRunner runner = new InMemoryRunner(AdkStaleAgent.ROOT_AGENT, APP_NAME);

    if (Settings.isInteractive()) {
      runInteractive(runner);
    } else {
      runWorkflow(runner);
    }

    System.out.println("-".repeat(80));
    Instant end = Instant.now();
    System.out.printf("Auditing finished at %s%n", end);
    System.out.printf(
        "Total script execution time: %.2f seconds%n",
        (end.toEpochMilli() - start.toEpochMilli()) / 1000.0);
  }

  // ===========================================================================
  // Unattended workflow mode
  // ===========================================================================

  private static void runWorkflow(InMemoryRunner runner) {
    String issueNumberStr = Settings.issueNumber();
    if (issueNumberStr != null && !issueNumberStr.isBlank()) {
      int issueNumber = Settings.parseNumberString(issueNumberStr, 0);
      if (issueNumber <= 0) {
        System.err.printf("Error: Invalid issue number received: %s.%n", issueNumberStr);
        return;
      }
      System.out.printf(
          "EVENT: Auditing specific issue #%d (event: %s).%n", issueNumber, Settings.eventName());
      // Bind the mutating tools to exactly this issue so untrusted content cannot steer the agent
      // into modifying a different issue.
      AdkStaleAgent.authorizeIssue(issueNumber);
      auditIssue(runner, issueNumber);
      return;
    }

    System.out.printf(
        "EVENT: Batch auditing stale-candidate issues (event: %s).%n", Settings.eventName());
    int limit = Settings.parseNumberString(Settings.issueCountToProcess(), 20);
    Optional<List<Integer>> candidates = fetchStaleCandidates();
    if (candidates.isEmpty()) {
      return;
    }
    List<Integer> issueNumbers = candidates.get();
    if (issueNumbers.isEmpty()) {
      System.out.println("No stale-candidate issues found. Run finished.");
      return;
    }
    int total = Math.min(limit, issueNumbers.size());
    System.out.printf(
        "Found %d stale-candidate issue(s); auditing up to %d.%n", issueNumbers.size(), total);
    for (int i = 0; i < total; i++) {
      int issueNumber = issueNumbers.get(i);
      System.out.printf("--- Auditing issue %d/%d: #%d ---%n", i + 1, total, issueNumber);
      // Authorize only the issue being audited right now, so a prompt-injection payload in one
      // issue's content cannot reach another issue from the batch.
      AdkStaleAgent.clearAuthorizedIssues();
      AdkStaleAgent.authorizeIssue(issueNumber);
      auditIssue(runner, issueNumber);
      if (i < total - 1) {
        sleepBetweenIssues();
      }
    }
  }

  /**
   * Searches for issues old enough to be stale candidates via {@link
   * GitHubStaleClient#searchOldOpenIssueNumbers}. Returns {@link Optional#empty()} (logging to
   * stderr) if the search fails, so the caller can abort cleanly.
   */
  private static Optional<List<Integer>> fetchStaleCandidates() {
    double daysOld = Settings.staleHoursThreshold() / 24.0;
    try {
      return Optional.of(
          AdkStaleAgent.client()
              .searchOldOpenIssueNumbers(Settings.owner(), Settings.repo(), daysOld));
    } catch (Exception e) {
      System.err.println("Failed to fetch stale-candidate issues: " + e.getMessage());
      return Optional.empty();
    }
  }

  /** Runs one audit turn for {@code issueNumber} in a fresh session. */
  private static void auditIssue(InMemoryRunner runner, int issueNumber) {
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
    String finalText = callAgent(runner, session, buildAuditPrompt(issueNumber));
    System.out.printf("<<<< Agent Final Output for #%d: %s%n%n", issueNumber, finalText);
  }

  /** Builds the user prompt that asks the agent to audit a single issue. Pure (no env/network). */
  static String buildAuditPrompt(int issueNumber) {
    return String.format(
        "Audit Issue #%d. Call get_issue_state first, then follow your decision tree exactly.",
        issueNumber);
  }

  private static void sleepBetweenIssues() {
    long millis = Settings.sleepBetweenIssuesMs();
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ===========================================================================
  // Interactive console mode
  // ===========================================================================

  private static void runInteractive(InMemoryRunner runner) {
    System.out.println(
        """
        Interactive mode. The agent will ask for your approval before mutating an issue.
        Type a prompt (e.g. "Audit issue #123"), or 'exit' to quit.
        For a richer web UI, see the "adk web" instructions in this module's README.
        """);
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
    try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
      while (true) {
        System.out.print("\nYou > ");
        if (!scanner.hasNextLine()) {
          return;
        }
        String userInput = scanner.nextLine();
        if (userInput == null) {
          return;
        }
        String trimmed = userInput.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        if ("exit".equalsIgnoreCase(trimmed) || "quit".equalsIgnoreCase(trimmed)) {
          return;
        }
        try {
          callAgent(runner, session, trimmed);
        } catch (RuntimeException e) {
          System.err.println("Agent turn failed: " + e.getMessage());
        }
      }
    }
  }

  // ===========================================================================
  // Shared agent-call helper
  // ===========================================================================

  /**
   * Sends {@code prompt} as a user turn to the agent and prints every streamed event. Returns the
   * concatenated text of events emitted by the root agent (matches {@code call_agent_async} in the
   * Python implementation).
   */
  private static String callAgent(InMemoryRunner runner, Session session, String prompt) {
    Content userMessage =
        Content.builder().role("user").parts(ImmutableList.of(Part.fromText(prompt))).build();

    String rootName = AdkStaleAgent.ROOT_AGENT.name();
    StringBuilder finalText = new StringBuilder();
    // Consume events as they stream in (rather than buffering the whole turn) so progress is
    // printed
    // in real time, matching the Python implementation's `async for` loop.
    runner
        .runAsync(session.userId(), session.id(), userMessage, RunConfig.builder().build())
        .blockingForEach(
            event -> {
              Optional<Content> contentOpt = event.content();
              if (contentOpt.isEmpty()) {
                return;
              }
              Optional<List<Part>> partsOpt = contentOpt.get().parts();
              if (partsOpt.isEmpty()) {
                return;
              }
              // An event can carry multiple parts (e.g. text plus function calls); concatenate all
              // the text parts rather than reading only the first.
              StringBuilder eventText = new StringBuilder();
              for (Part part : partsOpt.get()) {
                part.text().filter(t -> !t.isEmpty()).ifPresent(eventText::append);
              }
              if (eventText.length() == 0) {
                return;
              }
              System.out.printf("** %s (ADK): %s%n", event.author(), eventText);
              if (rootName.equals(event.author())) {
                finalText.append(eventText);
              }
            });
    return finalText.toString();
  }
}
