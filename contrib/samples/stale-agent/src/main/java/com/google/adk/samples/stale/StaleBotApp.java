package com.google.adk.samples.stale;

import com.google.adk.runner.InMemoryRunner;
import com.google.adk.samples.stale.agent.StaleAgent;
import com.google.adk.samples.stale.config.StaleBotSettings;
import com.google.adk.samples.stale.utils.GitHubUtils;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaleBotApp {

  private static final Logger logger = LoggerFactory.getLogger(StaleAgent.class);
  private static final String USER_ID = "stale_bot_user";

  record IssueResult(long issueNumber, double durationSeconds, int apiCalls) {}

  public static void main(String[] args) {

    try {
      runBot();
    } catch (Exception e) {
      logger.error(String.format("Unexpected fatal error", e));
    }
  }

  public static void runBot() {
    logger.info(
        String.format(
            " Starting Stale Bot for " + StaleBotSettings.OWNER + "/" + StaleBotSettings.REPO));
    logger.info(String.format("Concurrency level set to " + StaleBotSettings.CONCURRENCY_LIMIT));

    GitHubUtils.resetApiCallCount();

    double filterDays = StaleBotSettings.STALE_HOURS_THRESHOLD / 24.0;
    logger.info(String.format("Fetching issues older than %.2f days...", filterDays));

    List<Integer> allIssues;
    try {
      allIssues =
          GitHubUtils.getOldOpenIssueNumbers(
              StaleBotSettings.OWNER, StaleBotSettings.REPO, filterDays);
    } catch (Exception e) {
      logger.error(String.format("Failed to fetch issue list", e));
      return;
    }

    int totalCount = allIssues.size();
    int searchApiCalls = GitHubUtils.getApiCallCount();

    if (totalCount == 0) {
      logger.info(String.format("No issues matched the criteria. Run finished."));
      return;
    }

    logger.info(
        String.format(
            "Found %d issues to process. (Initial search used %d API calls).",
            totalCount, searchApiCalls));

    double totalProcessingTime = 0.0;
    int totalIssueApiCalls = 0;
    int processedCount = 0;

    InMemoryRunner runner = new InMemoryRunner(StaleAgent.create());

    for (int i = 0; i < totalCount; i += StaleBotSettings.CONCURRENCY_LIMIT) {
      int end = Math.min(i + StaleBotSettings.CONCURRENCY_LIMIT, totalCount);
      List<Integer> chunk = allIssues.subList(i, end);
      int currentChunkNum = (i / StaleBotSettings.CONCURRENCY_LIMIT) + 1;

      logger.info(
          String.format("Starting chunk %d: Processing issues %s ", currentChunkNum, chunk));

      // Create a list of Futures (Async Tasks)
      List<CompletableFuture<IssueResult>> futures =
          chunk.stream()
              .map(issueNum -> processSingleIssue(issueNum, runner))
              .collect(Collectors.toList());

      // Wait for all tasks in this chunk to complete
      CompletableFuture<Void> allFutures =
          CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

      try {
        allFutures.join();

        // Aggregate results
        for (CompletableFuture<IssueResult> f : futures) {
          IssueResult result = f.get();
          if (result != null) {
            totalProcessingTime += result.durationSeconds();
            totalIssueApiCalls += result.apiCalls();
          }
        }
      } catch (Exception e) {
        logger.error(String.format("Error gathering chunk results", e));
      }

      processedCount += chunk.size();
      logger.info(
          String.format(
              "Finished chunk %d. Progress: %d/%d ", currentChunkNum, processedCount, totalCount));

      // Sleep between chunks if not finished
      if (end < totalCount) {
        logger.info(
            String.format(
                "Sleeping for "
                    + StaleBotSettings.SLEEP_BETWEEN_CHUNKS
                    + "s to respect rate limits..."));
        try {
          Thread.sleep((long) (StaleBotSettings.SLEEP_BETWEEN_CHUNKS * 1000));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          logger.warn(String.format("Sleep interrupted."));
        }
      }
    }

    int totalApiCallsForRun = searchApiCalls + totalIssueApiCalls;
    double avgTimePerIssue = totalCount > 0 ? totalProcessingTime / totalCount : 0;

    logger.info(String.format("Successfully processed " + processedCount + " issues."));
    logger.info(String.format("Total API calls made this run: " + totalApiCallsForRun));
    logger.info(String.format("Average processing time per issue: %.2f seconds.", avgTimePerIssue));
  }

  private static CompletableFuture<IssueResult> processSingleIssue(
      int issueNumber, InMemoryRunner localRunner) {
    return CompletableFuture.supplyAsync(
        () -> {
          long startNano = System.nanoTime();
          int startApiCalls = GitHubUtils.getApiCallCount();

          logger.info(String.format("Processing Issue #" + issueNumber + "..."));

          String sessionId = "session-" + issueNumber + "-" + UUID.randomUUID().toString();

          try {

            localRunner
                .sessionService()
                .createSession(localRunner.appName(), USER_ID, null, sessionId)
                .blockingGet();

            logger.info(String.format("Session created successfully: " + sessionId));

            String promptText = "Audit Issue #" + issueNumber + ".";
            Content promptMessage = Content.fromParts(Part.fromText(promptText));
            StringBuilder fullResponse = new StringBuilder();

            localRunner
                .runAsync(USER_ID, sessionId, promptMessage)
                .blockingSubscribe(
                    event -> {
                      try {
                        if (event.content() != null && event.content().isPresent()) {
                          event
                              .content()
                              .get()
                              .parts()
                              .get()
                              .forEach(
                                  p -> {
                                    p.text().ifPresent(text -> fullResponse.append(text));
                                  });
                        }
                      } catch (Exception e) {
                        logger.warn(String.format("Failed to process Issue #" + issueNumber, e));
                      }
                    },
                    error -> {
                      logger.error(
                          String.format(
                              "Stream failed for Issue #"
                                  + issueNumber
                                  + ": "
                                  + error.getMessage()));
                    });

            String decision = fullResponse.toString().replace("\n", " ");
            if (decision.length() > 150) decision = decision.substring(0, 150);

            logger.info("#" + issueNumber + " Decision: " + decision + "...");

          } catch (Exception e) {
            logger.error(String.format("Error processing issue #" + issueNumber, e));
          }

          double durationSeconds = (System.nanoTime() - startNano) / 1_000_000_000.0;
          int issueApiCalls = Math.max(0, GitHubUtils.getApiCallCount() - startApiCalls);

          return new IssueResult(issueNumber, durationSeconds, issueApiCalls);
        });
  }
}
