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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHContentBuilder;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHIssueStateReason;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

/**
 * Reusable GitHub function tools backed by the {@code org.kohsuke:github-api} client. Each returns
 * a {@code Map} with a {@code "status"} of {@code "success"}, {@code "error"} or {@code "dry_run"}.
 * Reads {@code GITHUB_TOKEN} from the environment; callers set {@link #dryRun} to gate writes.
 *
 * <p>The tools cover the operations needed by the ADK GitHub automation samples: reading releases,
 * diffs and file contents; searching code; listing and reading issues and their comments; listing
 * repository collaborators; creating issues and pull requests; labelling/assigning issues;
 * commenting on or closing issues; and reading, labelling and commenting on pull requests.
 *
 * <p>Defense in depth against prompt injection: the agents read untrusted GitHub content (diffs,
 * file contents, issue/PR titles) and could be steered into harmful writes. Independently of the
 * prompt, the write tools (a) only target {@link #writeRepoOwner}/{@link #writeRepoName} when set,
 * (b) restrict pull requests to Markdown files under {@code docs/}, and (c) cap how many issues and
 * pull requests a single run may <em>create</em>. The labelling/assignment/commenting tools are not
 * separately capped: unlike issue/PR creation they do not create new objects (so they carry no
 * unbounded-spam risk) and only mutate pre-existing issues or pull requests in the pinned target
 * repository from (a); the consuming triaging agents additionally bind them to a fixed label
 * allowlist and the specific issue/PR numbers the workflow authorized.
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
  private static final int MAX_ISSUES_LISTED = 100;

  /**
   * Upper bound for {@link #listOpenIssuesUpdatedSince}. Higher than {@link #MAX_ISSUES_LISTED}
   * because the spam-detection sweep audits the whole open backlog, not just a triage batch.
   */
  private static final int MAX_ISSUES_SCANNED = 500;

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

  /**
   * Caps for the {@code get_pull_request} payload. Pull requests can be large; we keep only the
   * first N files/commits/comments and truncate the unified diff so the model context stays small
   * (mirrors the {@code last: 50} / 10k-char limits in the Python PR triaging agent).
   */
  private static final int MAX_PR_DIFF_CHARS = 10_000;

  private static final int MAX_PR_FILES = 50;
  private static final int MAX_PR_COMMITS = 50;
  private static final int MAX_PR_COMMENTS = 50;

  /** Auto-generated merge commits filtered out of the PR commit list (matches the Python agent). */
  private static final String MERGE_COMMIT_PREFIX = "Merge branch 'main' into";

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
          "Lists OPEN issues in a repository that carry the 'docs updates' label, restricted to a"
              + " single code repository's release issues. Call this before creating an issue to"
              + " avoid filing a duplicate for the same release range.")
  public static Map<String, Object> findDocIssues(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(
              name = "code_repo",
              description =
                  "Only return issues whose title mentions this code repository (e.g."
                      + " \"adk-java\"), so results stay scoped to one language. Pass an empty"
                      + " string for no filter.")
          String codeRepo) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      String filter = codeRepo == null ? "" : codeRepo.trim().toLowerCase(Locale.ROOT);
      List<Map<String, Object>> issues = new ArrayList<>();
      for (GHIssue issue : repo.getIssues(GHIssueState.OPEN)) {
        if (issue.isPullRequest() || !hasDocsLabel(issue)) {
          continue;
        }
        if (!issue.getTitle().toLowerCase(Locale.ROOT).contains(filter)) {
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

  @Schema(
      name = "list_open_issues",
      description =
          "Lists OPEN issues (excluding pull requests) for a repository. Each entry has the issue's"
              + " number, title, body, html_url, labels and assignees.")
  public static Map<String, Object> listOpenIssues(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(
              name = "max_results",
              description = "Maximum number of issues to return (capped at 100).",
              optional = true)
          Integer maxResults) {
    int limit =
        (maxResults == null || maxResults <= 0)
            ? MAX_ISSUES_LISTED
            : Math.min(maxResults, MAX_ISSUES_LISTED);
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      List<Map<String, Object>> issues = new ArrayList<>();
      for (GHIssue issue : repo.getIssues(GHIssueState.OPEN)) {
        if (issue.isPullRequest()) {
          continue;
        }
        issues.add(formatIssue(issue));
        if (issues.size() >= limit) {
          break;
        }
      }
      return success("issues", issues);
    } catch (IOException | GHException e) {
      return error("Failed to list issues: " + e.getMessage());
    }
  }

  @Schema(
      name = "list_open_issues_updated_since",
      description =
          "Lists OPEN issues (excluding pull requests) for a repository, optionally restricted to"
              + " those updated at or after an ISO-8601 timestamp (e.g. 2026-01-01T00:00:00Z). Each"
              + " entry has the issue's number, title, body, html_url, author, labels and"
              + " assignees. Pass an empty updated_since to list all open issues.")
  public static Map<String, Object> listOpenIssuesUpdatedSince(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(
              name = "updated_since",
              description =
                  "Only include issues updated at or after this ISO-8601 instant. May be empty to"
                      + " disable the filter.",
              optional = true)
          String updatedSince,
      @Schema(
              name = "max_results",
              description = "Maximum number of issues to return (capped at 500).",
              optional = true)
          Integer maxResults) {
    int limit =
        (maxResults == null || maxResults <= 0)
            ? MAX_ISSUES_SCANNED
            : Math.min(maxResults, MAX_ISSUES_SCANNED);
    Date since = parseInstantOrNull(updatedSince);
    if (updatedSince != null && !updatedSince.isBlank() && since == null) {
      return error("updated_since '" + updatedSince + "' is not a valid ISO-8601 instant.");
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      org.kohsuke.github.GHIssueQueryBuilder.ForRepository query = repo.queryIssues();
      query.state(GHIssueState.OPEN);
      if (since != null) {
        query.since(since);
      }
      query.pageSize(100);
      List<Map<String, Object>> issues = new ArrayList<>();
      for (GHIssue issue : query.list()) {
        if (issue.isPullRequest()) {
          continue;
        }
        issues.add(formatIssue(issue));
        if (issues.size() >= limit) {
          break;
        }
      }
      return success("issues", issues);
    } catch (IOException | GHException e) {
      return error("Failed to list issues: " + e.getMessage());
    }
  }

  @Schema(
      name = "get_issue",
      description =
          "Fetches a single OPEN or closed issue by number, returning its number, title, body,"
              + " html_url, labels and assignees.")
  public static Map<String, Object> getIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to fetch.") int issueNumber) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHIssue issue = repo.getIssue(issueNumber);
      if (issue.isPullRequest()) {
        return error("#" + issueNumber + " is a pull request, not an issue.");
      }
      return success("issue", formatIssue(issue));
    } catch (GHFileNotFoundException e) {
      return error("Issue #" + issueNumber + " was not found.");
    } catch (IOException | GHException e) {
      return error("Failed to get issue #" + issueNumber + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "get_issue_comments",
      description =
          "Lists all comments on an issue (oldest first), each with the comment author's login,"
              + " body and html_url. Use this to inspect a thread for spam or to check whether the"
              + " bot has already commented.")
  public static Map<String, Object> getIssueComments(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number whose comments to fetch.")
          int issueNumber) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHIssue issue = repo.getIssue(issueNumber);
      List<Map<String, Object>> comments = new ArrayList<>();
      for (GHIssueComment comment : issue.getComments()) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("author", commentAuthorLogin(comment));
        info.put("body", comment.getBody() == null ? "" : comment.getBody());
        info.put("html_url", comment.getHtmlUrl() == null ? "" : comment.getHtmlUrl().toString());
        comments.add(info);
      }
      return success("comments", comments);
    } catch (GHFileNotFoundException e) {
      return error("Issue #" + issueNumber + " was not found.");
    } catch (IOException | GHException e) {
      return error("Failed to get comments for issue #" + issueNumber + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "list_repository_collaborators",
      description =
          "Lists the login handles of the repository's collaborators (repo insiders). Used to skip"
              + " content authored by maintainers when auditing for spam.")
  public static Map<String, Object> listRepositoryCollaborators(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      List<String> collaborators = new ArrayList<>(repo.getCollaboratorNames());
      return success("collaborators", collaborators);
    } catch (IOException | GHException e) {
      return error("Failed to list collaborators: " + e.getMessage());
    }
  }

  @Schema(
      name = "add_label_to_issue",
      description = "Adds a single label to an issue, preserving any labels already present.")
  public static Map<String, Object> addLabelToIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to label.") int issueNumber,
      @Schema(name = "label", description = "The label to add.") String label) {
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no label was added. Set DRY_RUN=0 to label issues for real.",
          "issue_number",
          issueNumber,
          "label",
          label);
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      repo.getIssue(issueNumber).addLabels(label);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issue_number", issueNumber);
      result.put("added_label", label);
      return success(result);
    } catch (IOException | GHException e) {
      return error(
          "Failed to add label '" + label + "' to issue #" + issueNumber + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "remove_label_from_issue",
      description =
          "Removes a single label from an issue. Succeeds as a no-op if the label is not present.")
  public static Map<String, Object> removeLabelFromIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to unlabel.") int issueNumber,
      @Schema(name = "label", description = "The label to remove.") String label) {
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no label was removed. Set DRY_RUN=0 to modify issues for real.",
          "issue_number",
          issueNumber,
          "label",
          label);
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      repo.getIssue(issueNumber).removeLabel(label);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issue_number", issueNumber);
      result.put("removed_label", label);
      return success(result);
    } catch (GHFileNotFoundException e) {
      // The label (or label-on-issue) was not present; removing it is a no-op success.
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issue_number", issueNumber);
      result.put("removed_label", label);
      result.put("note", "label was not present");
      return success(result);
    } catch (IOException | GHException e) {
      return error(
          "Failed to remove label '"
              + label
              + "' from issue #"
              + issueNumber
              + ": "
              + e.getMessage());
    }
  }

  @Schema(
      name = "assign_issue",
      description = "Adds one or more assignees (by GitHub handle) to an issue.")
  public static Map<String, Object> assignIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to assign.") int issueNumber,
      @Schema(name = "assignees", description = "GitHub handles to assign.")
          List<String> assignees) {
    if (assignees == null || assignees.isEmpty()) {
      return error("assignees must be non-empty.");
    }
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no assignee was added. Set DRY_RUN=0 to assign issues for real.",
          "issue_number",
          issueNumber,
          "assignees",
          assignees);
    }
    try {
      GitHub github = connect();
      GHRepository repo = github.getRepository(repoOwner + "/" + repoName);
      List<GHUser> users = new ArrayList<>();
      for (String assignee : assignees) {
        users.add(github.getUser(assignee));
      }
      repo.getIssue(issueNumber).addAssignees(users);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issue_number", issueNumber);
      result.put("assignees", assignees);
      return success(result);
    } catch (IOException | GHException e) {
      return error("Failed to assign issue #" + issueNumber + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "get_pull_request",
      description =
          "Fetches a single pull request by number. Returns its number, title, body, state,"
              + " author, labels, changed files, commits (merge commits filtered out), recent"
              + " comments, status checks and a truncated unified diff.")
  public static Map<String, Object> getPullRequest(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "pr_number", description = "The pull request number to fetch.") int prNumber) {
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHPullRequest pullRequest = repo.getPullRequest(prNumber);
      return success("pull_request", formatPullRequest(repo, pullRequest));
    } catch (GHFileNotFoundException e) {
      return error("Pull request #" + prNumber + " was not found.");
    } catch (IOException | GHException e) {
      return error("Failed to get pull request #" + prNumber + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "add_label_to_pull_request",
      description =
          "Adds a single label to a pull request, preserving any labels already present. (A pull"
              + " request is a special kind of issue, so this uses the issue labels endpoint.)")
  public static Map<String, Object> addLabelToPullRequest(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "pr_number", description = "The pull request number to label.") int prNumber,
      @Schema(name = "label", description = "The label to add.") String label) {
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no label was added. Set DRY_RUN=0 to label pull requests for real.",
          "pr_number",
          prNumber,
          "label",
          label);
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      repo.getPullRequest(prNumber).addLabels(label);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("pr_number", prNumber);
      result.put("added_label", label);
      return success(result);
    } catch (IOException | GHException e) {
      return error(
          "Failed to add label '"
              + label
              + "' to pull request #"
              + prNumber
              + ": "
              + e.getMessage());
    }
  }

  @Schema(name = "add_comment_to_pull_request", description = "Posts a comment on a pull request.")
  public static Map<String, Object> addCommentToPullRequest(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "pr_number", description = "The pull request number to comment on.")
          int prNumber,
      @Schema(name = "comment", description = "The comment body (Markdown).") String comment) {
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no comment was posted. Set DRY_RUN=0 to comment for real.",
          "pr_number",
          prNumber,
          "comment",
          comment);
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      repo.getPullRequest(prNumber).comment(comment);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("pr_number", prNumber);
      result.put("added_comment", comment);
      return success(result);
    } catch (IOException | GHException e) {
      return error("Failed to comment on pull request #" + prNumber + ": " + e.getMessage());
    }
  }

  /**
   * Formats a pull request into the compact map consumed by the PR triaging agent. Capped per the
   * {@code MAX_PR_*} constants so the model context stays small.
   */
  private static Map<String, Object> formatPullRequest(GHRepository repo, GHPullRequest pullRequest)
      throws IOException {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("number", pullRequest.getNumber());
    info.put("title", pullRequest.getTitle() == null ? "" : pullRequest.getTitle());
    info.put("body", pullRequest.getBody() == null ? "" : pullRequest.getBody());
    info.put("state", String.valueOf(pullRequest.getState()));
    info.put(
        "html_url", pullRequest.getHtmlUrl() == null ? "" : pullRequest.getHtmlUrl().toString());
    GHUser author = pullRequest.getUser();
    info.put("author", author == null ? "" : author.getLogin());

    List<String> labels = new ArrayList<>();
    for (GHLabel label : pullRequest.getLabels()) {
      labels.add(label.getName());
    }
    info.put("labels", labels);

    // Changed files + a truncated unified diff built from each file's patch.
    List<String> changedFiles = new ArrayList<>();
    StringBuilder diff = new StringBuilder();
    int fileCount = 0;
    for (GHPullRequestFileDetail file : pullRequest.listFiles()) {
      if (fileCount++ >= MAX_PR_FILES) {
        break;
      }
      changedFiles.add(file.getFilename());
      if (diff.length() < MAX_PR_DIFF_CHARS) {
        diff.append("diff --git a/")
            .append(file.getFilename())
            .append(" b/")
            .append(file.getFilename())
            .append("\n");
        if (file.getPatch() != null) {
          diff.append(file.getPatch()).append("\n");
        }
      }
    }
    info.put("changed_files", changedFiles);
    String diffString = diff.toString();
    if (diffString.length() > MAX_PR_DIFF_CHARS) {
      diffString = diffString.substring(0, MAX_PR_DIFF_CHARS);
    }
    info.put("diff", diffString);

    // Commits, with auto-generated merge commits filtered out (matches the Python agent).
    List<String> commits = new ArrayList<>();
    int commitCount = 0;
    for (GHPullRequestCommitDetail commit : pullRequest.listCommits()) {
      if (commitCount++ >= MAX_PR_COMMITS) {
        break;
      }
      String message =
          (commit.getCommit() == null || commit.getCommit().getMessage() == null)
              ? ""
              : commit.getCommit().getMessage();
      if (message.startsWith(MERGE_COMMIT_PREFIX)) {
        continue;
      }
      commits.add(message);
    }
    info.put("commits", commits);
    info.put("commit_count", commits.size());

    // Recent comments (author + body), so the agent can avoid posting duplicate comments.
    List<Map<String, Object>> comments = new ArrayList<>();
    int commentCount = 0;
    for (GHIssueComment comment : pullRequest.getComments()) {
      if (commentCount++ >= MAX_PR_COMMENTS) {
        break;
      }
      Map<String, Object> formatted = new LinkedHashMap<>();
      formatted.put("author", comment.getUserName() == null ? "" : comment.getUserName());
      formatted.put("body", comment.getBody() == null ? "" : comment.getBody());
      comments.add(formatted);
    }
    info.put("comments", comments);

    info.put("status_checks", collectStatusChecks(repo, pullRequest));
    return info;
  }

  /**
   * Collects the PR head commit's check runs and commit statuses (best-effort). Helps the agent
   * verify contribution-guideline checks such as CLA compliance. Any failure to read checks yields
   * an empty list rather than failing the whole {@code get_pull_request} call.
   */
  private static List<Map<String, Object>> collectStatusChecks(
      GHRepository repo, GHPullRequest pullRequest) {
    List<Map<String, Object>> checks = new ArrayList<>();
    String headSha = pullRequest.getHead() == null ? null : pullRequest.getHead().getSha();
    if (headSha == null) {
      return checks;
    }
    try {
      for (GHCheckRun run : repo.getCheckRuns(headSha)) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", run.getName() == null ? "" : run.getName());
        check.put("status", run.getStatus() == null ? "" : String.valueOf(run.getStatus()));
        check.put(
            "conclusion", run.getConclusion() == null ? "" : String.valueOf(run.getConclusion()));
        checks.add(check);
      }
    } catch (IOException | GHException e) {
      // Best effort: leave check runs out if they cannot be read.
    }
    try {
      for (GHCommitStatus status : repo.listCommitStatuses(headSha)) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("context", status.getContext() == null ? "" : status.getContext());
        check.put("state", status.getState() == null ? "" : String.valueOf(status.getState()));
        check.put("description", status.getDescription() == null ? "" : status.getDescription());
        checks.add(check);
      }
    } catch (IOException | GHException e) {
      // Best effort: leave commit statuses out if they cannot be read.
    }
    return checks;
  }

  @Schema(
      name = "add_comment_to_issue",
      description = "Posts a comment on an issue. Returns the created comment's html_url.")
  public static Map<String, Object> addCommentToIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to comment on.")
          int issueNumber,
      @Schema(name = "body", description = "The Markdown body of the comment.") String body) {
    if (body == null || body.isEmpty()) {
      return error("Comment body must not be empty.");
    }
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no comment was posted. Set DRY_RUN=0 to comment on issues for real.",
          "issue_number",
          issueNumber,
          "body",
          body);
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      GHIssueComment comment = repo.getIssue(issueNumber).comment(body);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issue_number", issueNumber);
      result.put("html_url", comment.getHtmlUrl() == null ? "" : comment.getHtmlUrl().toString());
      return success(result);
    } catch (IOException | GHException e) {
      return error("Failed to comment on issue #" + issueNumber + ": " + e.getMessage());
    }
  }

  @Schema(
      name = "close_issue",
      description =
          "Closes an issue as 'not planned' (the state used for stale/won't-do issues). Succeeds as"
              + " a no-op if the issue is already closed.")
  public static Map<String, Object> closeIssue(
      @Schema(name = "repo_owner", description = "The repository owner.") String repoOwner,
      @Schema(name = "repo_name", description = "The repository name.") String repoName,
      @Schema(name = "issue_number", description = "The issue number to close.") int issueNumber) {
    String targetError = writeTargetError(repoOwner, repoName);
    if (targetError != null) {
      return error(targetError);
    }
    if (dryRun) {
      return dryRunPreview(
          "DRY RUN: no issue was closed. Set DRY_RUN=0 to close issues for real.",
          "issue_number",
          issueNumber);
    }
    try {
      GHRepository repo = connect().getRepository(repoOwner + "/" + repoName);
      repo.getIssue(issueNumber).close(GHIssueStateReason.NOT_PLANNED);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("issue_number", issueNumber);
      result.put("state", "closed");
      return success(result);
    } catch (IOException | GHException e) {
      return error("Failed to close issue #" + issueNumber + ": " + e.getMessage());
    }
  }

  /**
   * Formats an issue into the compact map (number, title, body, html_url, author, labels,
   * assignees). {@code author} is the login of the issue opener (empty when unavailable), used by
   * the spam-detection sample to skip issues opened by maintainers/bots.
   */
  private static Map<String, Object> formatIssue(GHIssue issue) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("number", issue.getNumber());
    info.put("title", issue.getTitle());
    info.put("body", issue.getBody() == null ? "" : issue.getBody());
    info.put("html_url", issue.getHtmlUrl() == null ? "" : issue.getHtmlUrl().toString());
    info.put("author", issueAuthorLogin(issue));
    List<String> labels = new ArrayList<>();
    for (GHLabel label : issue.getLabels()) {
      labels.add(label.getName());
    }
    info.put("labels", labels);
    List<String> assignees = new ArrayList<>();
    for (GHUser user : issue.getAssignees()) {
      assignees.add(user.getLogin());
    }
    info.put("assignees", assignees);
    return info;
  }

  /** Returns the login of the issue's author, or {@code ""} if it cannot be determined. */
  private static String issueAuthorLogin(GHIssue issue) {
    try {
      GHUser user = issue.getUser();
      return user == null || user.getLogin() == null ? "" : user.getLogin();
    } catch (IOException | GHException e) {
      return "";
    }
  }

  /** Returns the login of a comment's author, or {@code ""} if it cannot be determined. */
  private static String commentAuthorLogin(GHIssueComment comment) {
    String name = comment.getUserName();
    return name == null ? "" : name;
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

  /**
   * Parses an ISO-8601 instant (e.g. {@code 2026-01-01T00:00:00Z}) into a {@link Date}, returning
   * {@code null} when {@code value} is null/blank or not a valid instant.
   */
  private static Date parseInstantOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Date.from(Instant.parse(value.trim()));
    } catch (DateTimeParseException e) {
      return null;
    }
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

  /**
   * Builds a {@code dry_run} preview envelope from {@code message} and an even number of key/value
   * pairs describing the write that would have happened.
   */
  private static Map<String, Object> dryRunPreview(String message, Object... keyValuePairs) {
    Map<String, Object> preview = new LinkedHashMap<>();
    preview.put(STATUS_KEY, STATUS_DRY_RUN);
    preview.put("message", message);
    for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
      preview.put(String.valueOf(keyValuePairs[i]), keyValuePairs[i + 1]);
    }
    return preview;
  }
}
