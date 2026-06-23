# ADK Docs Release Analyzer (Java)

A single ADK agent that keeps documentation in sync with code releases. It
analyzes the differences between two releases of a code repository
(`google/adk-java` by default), and—if the docs in a docs repository
(`google/adk-docs` by default) need updating—files a GitHub issue and opens a
pull request per recommendation that actually applies the edit.

This is a Java port of the Python `adk_release_analyzer` + `adk_docs_updater`
samples, collapsed into a single `LlmAgent` for clarity.

## How it works

The agent (`AdkDocsReleaseAnalyzerAgent`) is equipped with function tools
(`GitHubTools`) that talk to GitHub through the
[`org.kohsuke:github-api`](https://github-api.kohsuke.org/) client library (no
hand-rolled REST code, no local cloning):

1.  `list_releases` — find the two most recent release tags to compare.
2.  `find_doc_issues` — list open `docs updates` issues to avoid duplicates.
3.  `find_pull_requests_for_issue` — check whether an issue already has PRs.
4.  `get_changed_files` — list files changed between the two tags (compare API).
5.  `get_file_diff` — fetch the patch for an individual file.
6.  `search_code` — find related documentation via GitHub code search.
7.  `get_file_content` — read a documentation file (raw content).
8.  `create_issue` — file a single issue with the recommended doc updates.
9.  `create_pull_request` — open one PR per recommendation, updating one or more
    doc files.

Deduplication is anchored on the issue: if an open issue already covers the same
release range **and** already has pull requests, the agent stops. If the issue
exists but has no PRs, it reuses the issue and opens the PRs. If no
documentation changes are warranted, it creates nothing.

## Running locally

```bash
# From the repository root:
export GITHUB_TOKEN=...           # token with issues + pull-requests write on the docs repo
export GOOGLE_API_KEY=...         # Gemini API key

# Build and install the ADK libraries + this sample into your local Maven repo
# (once). Run exec:java separately (without -am) so it runs only on this module.
mvn -pl contrib/samples/github/adkreleasedocs -am install -DskipTests

# Analyze the two most recent releases (dry-run by default: previews the issue
# and pull requests, creates nothing):
mvn -pl contrib/samples/github/adkreleasedocs exec:java

# Or analyze an explicit range:
mvn -pl contrib/samples/github/adkreleasedocs exec:java \
    -Dexec.args="--start-tag v1.3.0 --end-tag v1.4.0"

# Actually file the issue and open the PRs:
mvn -pl contrib/samples/github/adkreleasedocs exec:java -Dexec.args="--no-dry-run"
```

By default the agent runs in **dry-run** mode: it does everything except write
to GitHub, and instead reports the issue and pull requests it *would* create.
Pass `--no-dry-run` to create them for real. Run with `--help` to see all
options.

## Command-line options

| Option                       | Default | Description                         |
| ---------------------------- | ------- | ----------------------------------- |
| `--start-tag <tag>`          | –       | Older release tag (base). Defaults  |
:                              :         : to the second most recent release.  :
| `--end-tag <tag>`            | –       | Newer release tag (head). Defaults  |
:                              :         : to the most recent release.         :
| `--dry-run` / `--no-dry-run` | dry-run | Preview the issue vs. actually file |
:                              :         : it.                                 :

## Configuration

The rest of the configuration is read from environment variables:

Variable                  | Required | Default               | Description
------------------------- | -------- | --------------------- | -----------
`GITHUB_TOKEN`            | yes      | –                     | Token with issues + pull-requests + contents write on the docs repository.
`GOOGLE_API_KEY`          | yes      | –                     | API key for the Gemini API.
`DOC_OWNER`               | no       | `google`              | Owner of the docs repository.
`CODE_OWNER`              | no       | `google`              | Owner of the code repository.
`DOC_REPO`                | no       | `adk-docs`            | Docs repository name.
`CODE_REPO`               | no       | `adk-java`            | Code repository name.
`CODE_SOURCE_PATH_FILTER` | no       | `core/src/main/java/` | Only analyze changes under this path.
`MODEL`                   | no       | `gemini-pro-latest`   | Model to use (a Pro model helps with deeper code understanding).

## Automated mode (GitHub workflow)

The workflow at `.github/workflows/analyze-releases-for-adk-docs-updates.yml`
runs the agent automatically whenever a release is published (and supports
manual dispatch with optional `start_tag` / `end_tag`). It defaults to
**dry-run** (preview only, no writes); to actually create the issue and PRs,
trigger it manually (`workflow_dispatch`) with `dry_run` set to `false`.
