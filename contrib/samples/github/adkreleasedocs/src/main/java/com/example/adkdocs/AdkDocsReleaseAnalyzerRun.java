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
package com.example.adkdocs;

import com.example.github.GitHubTools;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Console entry point for the ADK Docs Release Analyzer. */
@Command(
    name = "adk-docs-release-analyzer",
    mixinStandardHelpOptions = true,
    description =
        "Analyzes the differences between two ADK releases and files a docs issue (dry-run by"
            + " default).")
public final class AdkDocsReleaseAnalyzerRun implements Runnable {

  private static final String APP_NAME = "adk_docs_release_analyzer";
  private static final String USER_ID = "adk_docs_release_analyzer_user";

  @Option(
      names = "--start-tag",
      description = "Older release tag (base). Defaults to the second most recent release.")
  private String startTag;

  @Option(
      names = "--end-tag",
      description = "Newer release tag (head). Defaults to the most recent release.")
  private String endTag;

  @Option(
      names = "--dry-run",
      negatable = true,
      defaultValue = "true",
      // Keeps "--dry-run" = true; without it picocli assigns the opposite of defaultValue.
      fallbackValue = "true",
      description =
          "Preview the issue without creating it (default). Use --no-dry-run to file it for real.")
  private boolean dryRun;

  public static void main(String[] args) {
    System.exit(new CommandLine(new AdkDocsReleaseAnalyzerRun()).execute(args));
  }

  @Override
  public void run() {
    if (Settings.GITHUB_TOKEN == null || Settings.GITHUB_TOKEN.isEmpty()) {
      throw new IllegalStateException(
          "GITHUB_TOKEN environment variable is not set. Set it before running.");
    }
    GitHubTools.dryRun = dryRun;
    // Restrict all writes to the docs repository so untrusted content cannot redirect them.
    GitHubTools.writeRepoOwner = Settings.DOC_OWNER;
    GitHubTools.writeRepoName = Settings.DOC_REPO;

    String prompt = buildPrompt();

    InMemoryRunner runner = new InMemoryRunner(AdkDocsReleaseAnalyzerAgent.ROOT_AGENT, APP_NAME);
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();

    System.out.println("Session ID: " + session.id());
    System.out.println("-".repeat(80));
    System.out.println("You> " + prompt);

    Content message = Content.fromParts(Part.fromText(prompt));
    RunConfig runConfig = RunConfig.builder().build();
    Flowable<Event> events = runner.runAsync(USER_ID, session.id(), message, runConfig);

    StringBuilder response = new StringBuilder();
    for (Event event : events.blockingIterable()) {
      String text = event.stringifyContent();
      if (!text.isEmpty()) {
        response.append(text);
      }
    }
    System.out.println("Agent> " + response.toString().stripTrailing());
  }

  private String buildPrompt() {
    if (startTag != null && endTag != null) {
      return "Please analyze "
          + Settings.CODE_REPO
          + " releases from "
          + startTag
          + " to "
          + endTag
          + "!";
    }
    if (endTag != null) {
      return "Please analyze the "
          + Settings.CODE_REPO
          + " release "
          + endTag
          + " against its previous release!";
    }
    return "Please analyze the most recent two releases of " + Settings.CODE_REPO + "!";
  }
}
