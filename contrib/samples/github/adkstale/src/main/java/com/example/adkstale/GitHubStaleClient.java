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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Thin GitHub client for the reads the Stale Issue Auditor needs that the shared {@code
 * GitHubTools} (REST/kohsuke) does not cover: a single <b>GraphQL</b> query that reconstructs an
 * issue's full conversation history, the push-access collaborator ("maintainer") list, and the
 * Search API lookup of issues old enough to be stale candidates.
 *
 * <p>Mirrors the network layer of the Python sample's {@code utils.py} + the GraphQL parts of
 * {@code agent.py}. It uses the JDK's built-in {@link HttpClient} (no extra HTTP dependency) and
 * Jackson for JSON, and retries transient failures (HTTP 429/5xx) with exponential backoff.
 *
 * <p>All <em>writes</em> (comments, labels, closing) go through the shared {@code GitHubTools}
 * instead, so the dry-run and target-repository guards are enforced uniformly across the samples.
 */
final class GitHubStaleClient {

  /** GraphQL query reconstructing an issue's history: comments, body edits, and timeline events. */
  private static final String ISSUE_HISTORY_QUERY =
      """
      query($owner: String!, $name: String!, $number: Int!, $commentLimit: Int!, \
      $timelineLimit: Int!, $editLimit: Int!) {
        repository(owner: $owner, name: $name) {
          issue(number: $number) {
            author { login }
            createdAt
            labels(first: 20) { nodes { name } }

            comments(last: $commentLimit) {
              nodes {
                author { login }
                body
                createdAt
                lastEditedAt
              }
            }

            userContentEdits(last: $editLimit) {
              nodes {
                editor { login }
                editedAt
              }
            }

            timelineItems(itemTypes: [LABELED_EVENT, RENAMED_TITLE_EVENT, REOPENED_EVENT], \
      last: $timelineLimit) {
              nodes {
                __typename
                ... on LabeledEvent { createdAt actor { login } label { name } }
                ... on RenamedTitleEvent { createdAt actor { login } }
                ... on ReopenedEvent { createdAt actor { login } }
              }
            }
          }
        }
      }
      """;

  private static final DateTimeFormatter SEARCH_CUTOFF_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

  /** Transient HTTP statuses worth retrying. */
  private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 500, 502, 503, 504);

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

  private final String baseUrl;
  private final String token;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  GitHubStaleClient(String baseUrl, String token) {
    this.baseUrl = baseUrl;
    this.token = token;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
  }

  /** Builds a client pointed at the public GitHub API using the configured {@code GITHUB_TOKEN}. */
  static GitHubStaleClient createDefault() {
    return new GitHubStaleClient("https://api.github.com", Settings.githubToken());
  }

  /**
   * Fetches the raw {@code issue} GraphQL node for {@code number}, including the most recent
   * comments, body edits and timeline events (labels, renames, reopens). Throws if GraphQL returns
   * an error or the issue does not exist.
   */
  JsonNode fetchIssueHistory(
      String owner, String repo, int number, int commentLimit, int editLimit, int timelineLimit)
      throws IOException, InterruptedException {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("query", ISSUE_HISTORY_QUERY);
    ObjectNode variables = payload.putObject("variables");
    variables.put("owner", owner);
    variables.put("name", repo);
    variables.put("number", number);
    variables.put("commentLimit", commentLimit);
    variables.put("editLimit", editLimit);
    variables.put("timelineLimit", timelineLimit);

    HttpRequest request =
        baseRequest(baseUrl + "/graphql")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();

    JsonNode response = mapper.readTree(send(request));
    JsonNode errors = response.get("errors");
    if (errors != null && errors.isArray() && !errors.isEmpty()) {
      throw new IOException("GraphQL error: " + errors.get(0).path("message").asText("unknown"));
    }
    JsonNode issue = response.path("data").path("repository").path("issue");
    if (issue.isMissingNode() || issue.isNull()) {
      throw new IOException("Issue #" + number + " not found.");
    }
    return issue;
  }

  /**
   * Returns the GitHub handles of collaborators with push access (the repository's maintainers).
   * Throws on failure so the caller can fail closed rather than mis-classifying every actor.
   */
  List<String> listMaintainers(String owner, String repo) throws IOException, InterruptedException {
    HttpRequest request =
        baseRequest(
                baseUrl
                    + "/repos/"
                    + owner
                    + "/"
                    + repo
                    + "/collaborators?permission=push&per_page=100")
            .GET()
            .build();
    JsonNode data = mapper.readTree(send(request));
    if (!data.isArray()) {
      throw new IOException("Unexpected collaborators response: expected a JSON array.");
    }
    List<String> maintainers = new ArrayList<>();
    for (JsonNode user : data) {
      String login = user.path("login").asText(null);
      if (login != null && !login.isEmpty()) {
        maintainers.add(login);
      }
    }
    return maintainers;
  }

  /**
   * Finds open issues (excluding pull requests) created more than {@code daysOld} days ago, using
   * the Search API's server-side {@code created:<DATE} filter so brand-new issues are never
   * fetched. Mirrors {@code get_old_open_issue_numbers} in the Python utils.
   */
  List<Integer> searchOldOpenIssueNumbers(String owner, String repo, double daysOld)
      throws IOException, InterruptedException {
    Instant cutoff = Instant.now().minus(Duration.ofMinutes((long) (daysOld * 24 * 60)));
    String query =
        "repo:"
            + owner
            + "/"
            + repo
            + " is:issue state:open created:<"
            + SEARCH_CUTOFF_FORMAT.format(cutoff);
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);

    List<Integer> issueNumbers = new ArrayList<>();
    int page = 1;
    while (true) {
      HttpRequest request =
          baseRequest(baseUrl + "/search/issues?q=" + encodedQuery + "&per_page=100&page=" + page)
              .GET()
              .build();
      JsonNode items = mapper.readTree(send(request)).path("items");
      if (!items.isArray() || items.isEmpty()) {
        break;
      }
      for (JsonNode item : items) {
        // Search returns both issues and PRs; PRs carry a "pull_request" object.
        if (item.has("pull_request")) {
          continue;
        }
        issueNumbers.add(item.path("number").asInt());
      }
      if (items.size() < 100) {
        break;
      }
      page++;
    }
    return issueNumbers;
  }

  /** Shared request builder with the auth, accept and user-agent headers GitHub requires. */
  private HttpRequest.Builder baseRequest(String url) {
    return HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(REQUEST_TIMEOUT)
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "adk-stale-agent");
  }

  /**
   * Sends {@code request}, retrying transient failures (HTTP 429/5xx and {@link IOException}) with
   * exponential backoff. Returns the response body on a 2xx, otherwise throws.
   */
  private String send(HttpRequest request) throws IOException, InterruptedException {
    IOException lastError = null;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      if (attempt > 0) {
        backoff(attempt);
      }
      try {
        HttpResponse<String> response =
            http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
          return response.body();
        }
        if (!RETRYABLE_STATUSES.contains(status)) {
          throw new IOException(
              "GitHub API request to " + request.uri() + " failed with HTTP " + status + ".");
        }
        lastError =
            new IOException(
                "GitHub API request to " + request.uri() + " failed with HTTP " + status + ".");
      } catch (IOException e) {
        lastError = e;
      }
    }
    throw (lastError != null)
        ? lastError
        : new IOException("GitHub API request to " + request.uri() + " failed.");
  }

  /** Sleeps for an exponentially increasing interval (1s, 2s, 4s, ...), capped at 30s. */
  private static void backoff(int attempt) throws InterruptedException {
    long seconds = Math.min(30, (long) Math.pow(2, attempt - 1));
    Thread.sleep(seconds * 1000L);
  }
}
