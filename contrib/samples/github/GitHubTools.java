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
package com.example.github;

import com.google.adk.tools.Annotations.Schema;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

/**
 * Reusable GitHub function tools backed by the {@code org.kohsuke:github-api} client. Each returns
 * a {@code Map} with a {@code "status"} of {@code "success"} or {@code "error"}. Reads {@code
 * GITHUB_TOKEN} from the environment; callers set {@link #dryRun} to gate writes.
 *
 * <p>Defense in depth against prompt injection: the agent reads untrusted GitHub content (diffs,
 * file contents, issue/PR titles) and could be steered into harmful writes. Independently of the
 * prompt, the write tools (a) only target {@link #writeRepoOwner}/{@link #writeRepoName} when set,
 * (b) only modify Markdown files under {@code docs/}, and (c) are capped per run.
 */
public final class GitHubTools {

  /**
   * When true, {@code create_issue}/{@code create_pull_request} return a preview instead of
   * writing.
   */
  public static boolean dryRun = true;

  /**
   * When both are set, {@code create_issue}/{@code create_pull_request} refuse to write to any
   * other repository, regardless of the owner/repo the model passes. Set by the entry point to the
   * docs repository so untrusted content cannot redirect writes elsewhere.
   */
  public static String writeRepoOwner = null;

  public static String writeRepoName = null;

  private static final int MAX_SEARCH_RESULTS = 50;
  private static final String DOCS_UPDATES_LABEL = "docs updates";
  private static final String STATUS_KEY = "status";
  private static final String STATUS_SUCCESS = "success";
  private static final String STATUS_ERROR = "error";
  private static final String STATUS_DRY_RUN = "dry_run";

  /** Only Markdown files under {@code docs/} (excluding api-reference) may be written by a PR. */
  private static final String DOCS_PATH_PREFIX = "docs/";

  private static final String API_REFERENCE_PREFIX = "docs/api-reference/";

  /** Per-run write caps to bound spam/abuse if the agent is hijacked. */
  private static final int MAX_ISSUES_PER_RUN = 1;

  private static final int MAX_PULL_REQUESTS_PER_RUN = 20;
  private static int issuesCreated = 0;
  private static int pullRequestsCreated = 0;

  private GitHubTools() {}

  @Schema(
      name = "list_releases",
      description =
          "Lists releases for a repository (most recent first), returning each release's tag_name,"
              + " name and published_at.")
  public static Map<String, Object> listReleases(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      List<Map<String, Object>> releases = new ArrayList<>();
      for (GHRelease release : repo.listReleases()) {
        Map<String, Object> formatted = new LinkedHashMap<>();
        formatted.put("tag_name", release.getTagName());
        formatted.put("name", release.getName());
        formatted.put(
            "published_at",
            release.getPublished_at() == null ? null : release.getPublished_at().toString());
        releases.add(formatted);
      }
      return success("releases", releases);
    } catch (IOException | GHException e) {
      return error("Failed to list releases: " + e.getMessage());
    }
  }

  @Schema(
      name = "get_changed_files",
      description =
          "Lists files changed between two release tags (without patch content), optionally"
              + " filtered to a path prefix. Use this to decide which files to inspect in detail.")
  public static Map<String, Object> getChangedFiles(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "start_tag", description = "The older tag (base) for the comparison.")
          String startTag,
      @Schema(name = "end_tag", description = "The newer tag (head) for the comparison.")
          String endTag,
      @Schema(
              name = "path_filter",
              description = "Only include files whose path starts with this prefix. May be empty.",
              optional = true)
          String pathFilter) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHCompare comparison = repo.getCompare(startTag, endTag);
      List<Map<String, Object>> files = new ArrayList<>();
      for (GHCommit.File file : comparison.getFiles()) {
        String filename = file.getFileName();
        if (pathFilter != null && !pathFilter.isEmpty() && !filename.startsWith(pathFilter)) {
          continue;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("relative_path", filename);
        info.put("status", file.getStatus());
        info.put("additions", file.getLinesAdded());
        info.put("deletions", file.getLinesDeleted());
        files.add(info);
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("total_files", files.size());
      response.put("files", files);
      response.put(
          "compare_url",
          "https://github.com/"
              + repoOwner
              + "/"
              + repoName
              + "/compare/"
              + startTag
              + "..."
              + endTag);
      return success(response);
    } catch (IOException | GHException e) {
      return error("Failed to get changed files: " + e.getMessage());
    }
  }

  @Schema(
      name = "get_file_diff",
      description = "Gets the patch/diff for a single file between two release tags.")
  public static Map<String, Object> getFileDiff(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "start_tag", description = "The older tag (base) for the comparison.")
          String startTag,
      @Schema(name = "end_tag", description = "The newer tag (head) for the comparison.")
          String endTag,
      @Schema(name = "file_path", description = "Relative path of the file to get the diff for.")
          String filePath) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHCompare comparison = repo.getCompare(startTag, endTag);
      for (GHCommit.File file : comparison.getFiles()) {
        if (file.getFileName().equals(filePath)) {
          Map<String, Object> info = new LinkedHashMap<>();
          info.put("relative_path", file.getFileName());
          info.put("status", file.getStatus());
          info.put("additions", file.getLinesAdded());
          info.put("deletions", file.getLinesDeleted());
          info.put("patch", file.getPatch() == null ? "No patch available." : file.getPatch());
          return success("file", info);
        }
      }
      return error("File " + filePath + " not found in the comparison.");
    } catch (IOException | GHException e) {
      return error("Failed to get file diff: " + e.getMessage());
    }
  }

  @Schema(
      name = "search_code",
      description =
          "Searches a repository's content via the GitHub code search API and returns matching file"
              + " paths. Use it to find documentation related to a change, e.g. query"
              + " \"AgentBuilder path:docs\".")
  public static Map<String, Object> searchCode(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "query", description = "The code search query (GitHub search syntax).")
          String query) {
    try {
      GitHub github = connect();
      List<Map<String, Object>> matches = new ArrayList<>();
      int count = 0;
      for (GHContent content :
          github.searchContent().q(query).repo(repoOwner + "/" + repoName).list()) {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("file_path", content.getPath());
        matches.add(match);
        if (++count >= MAX_SEARCH_RESULTS) {
          break;
        }
      }
      return success("matches", matches);
    } catch (IOException | GHException e) {
      return error("Code search failed: " + e.getMessage());
    }
  }

  @Schema(
      name = "get_file_content",
      description =
          "Reads and returns the raw content of a file in a repository. Pass this content back"
              + " (edited) to create_pull_request to apply changes.")
  public static Map<String, Object> getFileContent(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "file_path", description = "Relative path of the file to read.")
          String filePath) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHContent content = repo.getFileContent(filePath);
      if (content.isDirectory()) {
        return error(filePath + " is a directory, not a file.");
      }
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("file_path", filePath);
      response.put("content", content.getContent());
      return success(response);
    } catch (IOException | GHException e) {
      return error("Failed to read file " + filePath + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "create_issue",
      description =
          "Creates a new issue in the specified repository with the 'docs updates' label. Returns"
              + " the created issue's number and html_url.")
  public static Map<String, Object> createIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "title", description = "The title of the issue.") String title,
      @Schema(name = "body", description = "The body of the issue.") String body) {
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      Map<String, Object> preview = new LinkedHashMap<>();
      preview.put(STATUS_KEY, STATUS_DRY_RUN);
      preview.put(
          "message", "DRY RUN: no issue was created. Set DRY_RUN=0 to file issues for real.");
      preview.put("repository", repoOwner + "/" + repoName);
      preview.put("title", title);
      preview.put("body", body);
      return preview;
    }
    if (issuesCreated >= MAX_ISSUES_PER_RUN) {
      return error("Issue creation limit reached (" + MAX_ISSUES_PER_RUN + " per run).");
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHIssue issue = repo.createIssue(title).body(body).label(DOCS_UPDATES_LABEL).create();
      issuesCreated++;
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("number", issue.getNumber());
      result.put("html_url", issue.getHtmlUrl().toString());
      result.put("title", issue.getTitle());
      return success("issue", result);
    } catch (IOException | GHException e) {
      return error("Failed to create issue: " + e.getMessage());
    }
  }

  @Schema(
      name = "find_doc_issues",
      description =
          "Lists OPEN issues in a repository that carry the 'docs updates' label. Call this before"
              + " creating an issue to avoid filing a duplicate for the same release range.")
  public static Map<String, Object> findDocIssues(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      List<Map<String, Object>> issues = new ArrayList<>();
      for (GHIssue issue : repo.getIssues(GHIssueState.OPEN)) {
        if (issue.isPullRequest() || !hasDocsLabel(issue)) {
          continue;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("number", issue.getNumber());
        info.put("title", issue.getTitle());
        info.put("html_url", issue.getHtmlUrl().toString());
        issues.add(info);
      }
      return success("issues", issues);
    } catch (IOException | GHException e) {
      return error("Failed to list issues: " + e.getMessage());
    }
  }

  @Schema(
      name = "find_pull_requests_for_issue",
      description =
          "Lists OPEN pull requests whose body references the given issue number. Use this to check"
              + " whether an issue already has pull requests before opening new ones (dedupe).")
  public static Map<String, Object> findPullRequestsForIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to look for.")
          int issueNumber) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      String marker = "#" + issueNumber;
      List<Map<String, Object>> pullRequests = new ArrayList<>();
      for (GHPullRequest pullRequest : repo.getPullRequests(GHIssueState.OPEN)) {
        String prBody = pullRequest.getBody();
        if (prBody != null && prBody.contains(marker)) {
          Map<String, Object> info = new LinkedHashMap<>();
          info.put("number", pullRequest.getNumber());
          info.put("title", pullRequest.getTitle());
          info.put("html_url", pullRequest.getHtmlUrl().toString());
          pullRequests.add(info);
        }
      }
      return success("pull_requests", pullRequests);
    } catch (IOException | GHException e) {
      return error("Failed to list pull requests: " + e.getMessage());
    }
  }

  @Schema(
      name = "create_pull_request",
      description =
          "Opens ONE pull request for a recommendation, updating one or more documentation files:"
              + " creates a branch off base_branch, commits each file, and opens the PR.")
  public static Map<String, Object> createPullRequest(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "base_branch", description = "Branch to merge into, e.g. \"main\".")
          String baseBranch,
      @Schema(name = "file_paths", description = "Documentation files to update.")
          List<String> filePaths,
      @Schema(
              name = "new_contents",
              description = "Full new content for each file, aligned 1:1 with file_paths.")
          List<String> newContents,
      @Schema(name = "title", description = "The pull request title.") String title,
      @Schema(name = "body", description = "The pull request body.") String body) {
    if (filePaths == null
        || newContents == null
        || filePaths.isEmpty()
        || filePaths.size() != newContents.size()) {
      return error("file_paths and new_contents must be non-empty and the same length.");
    }
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    for (String filePath : filePaths) {
      String pathError = docPathError(filePath);
      if (pathError != null) {
        return error(pathError);
      }
    }
    if (dryRun) {
      Map<String, Object> preview = new LinkedHashMap<>();
      preview.put(STATUS_KEY, STATUS_DRY_RUN);
      preview.put(
          "message", "DRY RUN: no pull request was created. Set DRY_RUN=0 to open PRs for real.");
      preview.put("base_branch", baseBranch);
      preview.put("file_paths", filePaths);
      preview.put("title", title);
      preview.put("body", body);
      return preview;
    }
    if (pullRequestsCreated >= MAX_PULL_REQUESTS_PER_RUN) {
      return error(
          "Pull request creation limit reached (" + MAX_PULL_REQUESTS_PER_RUN + " per run).");
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      String baseSha = repo.getRef("heads/" + baseBranch).getObject().getSha();
      String branch = "adk-docs-update-" + System.currentTimeMillis();
      repo.createRef("refs/heads/" + branch, baseSha);
      for (int i = 0; i < filePaths.size(); i++) {
        String filePath = filePaths.get(i);
        GHContentBuilder change =
            repo.createContent()
                .path(filePath)
                .content(newContents.get(i))
                .branch(branch)
                .message(title);
        try {
          change.sha(repo.getFileContent(filePath, branch).getSha());
        } catch (GHFileNotFoundException e) {
          // File does not exist yet; create it without a base sha.
        }
        change.commit();
      }
      GHPullRequest pullRequest = repo.createPullRequest(title, branch, baseBranch, body);
      pullRequestsCreated++;
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("number", pullRequest.getNumber());
      result.put("html_url", pullRequest.getHtmlUrl().toString());
      result.put("branch", branch);
      return success("pull_request", result);
    } catch (IOException | GHException e) {
      return error("Failed to create pull request: " + e.getMessage());
    }
  }

  private static boolean hasDocsLabel(GHIssue issue) {
    for (GHLabel label : issue.getLabels()) {
      if (label.getName().equals(DOCS_UPDATES_LABEL)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns an error message if writes are restricted (via {@link #writeRepoOwner}/{@link
   * #writeRepoName}) and the requested repository is not the allowed one, otherwise null. Prevents
   * untrusted content from redirecting writes to another repository.
   */
  private static String writeTargetError(String repoOwner, String repoName) {
    if (writeRepoOwner != null
        && writeRepoName != null
        && (!writeRepoOwner.equals(repoOwner) || !writeRepoName.equals(repoName))) {
      return "Refusing to write to "
          + repoOwner
          + "/"
          + repoName
          + ": writes are restricted to "
          + writeRepoOwner
          + "/"
          + writeRepoName
          + ".";
    }
    return null;
  }

  /**
   * Returns an error message if {@code path} is not a safe documentation file to write, otherwise
   * null. Untrusted model output may try to write outside {@code docs/} (e.g. workflows or source);
   * only Markdown files under {@code docs/} (excluding the auto-generated api-reference) are
   * allowed.
   */
  private static String docPathError(String path) {
    if (path == null || path.isEmpty()) {
      return "file path must not be empty.";
    }
    String normalized = path.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.contains("..") || normalized.contains(":")) {
      return "file path '" + path + "' must be a relative path inside the repository.";
    }
    if (!normalized.startsWith(DOCS_PATH_PREFIX)) {
      return "file path '" + path + "' must be under '" + DOCS_PATH_PREFIX + "'.";
    }
    if (normalized.startsWith(API_REFERENCE_PREFIX)) {
      return "file path '" + path + "' is auto-generated api-reference and must not be edited.";
    }
    String lower = normalized.toLowerCase(Locale.ROOT);
    if (!lower.endsWith(".md") && !lower.endsWith(".mdx")) {
      return "file path '" + path + "' must be a Markdown (.md/.mdx) documentation file.";
    }
    return null;
  }

  /** Connects to GitHub using GITHUB_TOKEN from the environment (anonymous if unset). */
  private static GitHub connect() throws IOException {
    GitHubBuilder builder = new GitHubBuilder();
    String token = System.getenv("GITHUB_TOKEN");
    if (token != null && !token.isEmpty()) {
      builder = builder.withOAuthToken(token);
    }
    return builder.build();
  }

  private static Map<String, Object> success(String key, Object value) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put(key, value);
    return success(response);
  }

  /** Wraps {@code response} with a success status, keeping {@code status} as the first key. */
  private static Map<String, Object> success(Map<String, Object> response) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(STATUS_KEY, STATUS_SUCCESS);
    result.putAll(response);
    return result;
  }

  private static Map<String, Object> error(String message) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put(STATUS_KEY, STATUS_ERROR);
    response.put("error_message", message);
    return response;
  }
}
