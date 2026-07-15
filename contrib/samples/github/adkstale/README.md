# ADK Stale Issue Auditor (Java)

The ADK Stale Issue Auditor is a Java-based agent that keeps the
`google/adk-java` issue tracker healthy. Unlike a timestamp-only "stale bot", it
reconstructs each issue's **Unified History Trace** with a single GitHub
**GraphQL** query and uses Gemini to reason about the *intent* of the last
action — distinguishing a maintainer asking a question (a stale candidate) from
a maintainer posting a status update (still active).

This sample is the Java port of
[`adk-python/contributing/samples/adk_team/adk_stale_agent`](https://github.com/google/adk-python/tree/main/contributing/samples/adk_team/adk_stale_agent).
It is built with the [Google ADK for Java](https://github.com/google/adk-java)
itself and doubles as a community sample: every tool is a real `FunctionTool`,
every JSON envelope matches the Python contract, and the agent runs in both
interactive mode (local CLI / `adk web`) and unattended GitHub Actions workflow
mode. GitHub *reads* (the GraphQL history, the maintainer list, the
stale-candidate search) go through `GitHubStaleClient`; GitHub *writes*
(comments, labels, closing) go through the shared `GitHubTools` (backed by the
[`org.kohsuke:github-api`](https://github-api.kohsuke.org/) client) reused
across the ADK GitHub samples, so the dry-run and target-repository guards apply
uniformly.

--------------------------------------------------------------------------------

## Core Logic

For each open issue the agent calls `get_issue_state`, which:

1.  **Fetches the history in one GraphQL query** — comments, description/body
    edits ("ghost edits"), title renames, reopens, and `stale` label events.
2.  **Builds a Unified History Trace** — all events are normalized and sorted
    chronologically; events from the bot (`adk-bot`) and any `*[bot]` account
    are ignored.
3.  **Finds the Last Actor** and classifies their role as `author`, `maintainer`
    (a push-access collaborator), or `other_user`.

The agent then follows a strict decision tree:

| Last actor                          | Verdict & action                       |
| ----------------------------------- | -------------------------------------- |
| Author / other user                 | **ACTIVE** — remove the `stale` label. |
:                                     : If the author *silently edited* the    :
:                                     : description (no comment, which GitHub  :
:                                     : does not notify on), post a one-time   :
:                                     : maintainer alert.                      :
| Maintainer asked a question and     | **STALE** — comment + add the `stale`  |
: `days_since_activity` > stale       : label (and `request clarification` if  :
: threshold                           : missing).                              :
| Issue already `stale` and           | **CLOSE** — comment + close as not     |
: `days_since_stale_label` > close    : planned.                               :
: threshold                           :                                        :
| Maintainer posted a status update / | **ACTIVE** — no action.                |
: is talking to another maintainer    :                                        :

The thresholds default to **7 days** to mark stale and a further **7 days** to
close (matching the Python sample), and are configurable via environment
variables.

--------------------------------------------------------------------------------

## Project Layout

```
contrib/samples/github/
├── GitHubTools.java          // Shared kohsuke-based GitHub tools (reused across samples)
└── adkstale/
    ├── AdkStaleAgent.java     // LlmAgent + @Schema FunctionTools + history/state logic
    ├── AdkStaleAgentRun.java  // Entry point: interactive + workflow modes
    ├── GitHubStaleClient.java // GraphQL history + maintainer + stale-candidate reads
    ├── Settings.java          // Environment-variable configuration (lazy accessors)
    ├── pom.xml                // Maven module config
    ├── src/test/java/...      // Unit tests for the deterministic logic
    └── README.md             // This file
```

The GitHub Actions workflow lives at
`.github/workflows/stale-adk-java-issues.yml`.

--------------------------------------------------------------------------------

## Prerequisites

The `stale` label (and, if you keep it enabled, `request clarification`) **must
exist** in the repository — GitHub does not auto-create labels when the agent
applies them. Create them once in the repo's Labels settings, or rename the
labels the agent uses via `STALE_LABEL` / `REQUEST_CLARIFICATION_LABEL`.

--------------------------------------------------------------------------------

## Interactive Mode

Use interactive mode locally to inspect the agent's reasoning before any change
is made to your repository's issues. In this mode the system instruction tells
the agent to **describe what it intends to do and ask for confirmation** before
calling any mutating tool; `get_issue_state` (read-only) runs without approval.

### Required environment variables

```bash
export GITHUB_TOKEN=ghp_...
export GOOGLE_API_KEY=...
export GOOGLE_GENAI_USE_VERTEXAI=0
# Optional:
export OWNER=google
export REPO=adk-java
export INTERACTIVE=1
```

### Option A — Console REPL (zero extra setup)

From the repository root:

```bash
# Install the ADK libraries + this sample once, then run exec:java scoped to
# this module (exec:java with -am would also run on the parent/core modules,
# which have no mainClass).
./mvnw -pl contrib/samples/github/adkstale -am install -DskipTests
./mvnw -pl contrib/samples/github/adkstale exec:java
```

The REPL prompts for a request, e.g. `Audit issue #123`, streams every model
event back to the terminal, and waits for your approval before each tool call.

### Option B — ADK Web UI

The Java equivalent of Python's `adk web` is the `web` goal of the
[`google-adk-maven-plugin`](https://github.com/google/adk-java/tree/main/maven_plugin).
The goal loads an agent from a static-field reference, so it must run **in this
module's context**. From this module's directory:

```bash
cd contrib/samples/github/adkstale
mvn google-adk:web \
    -Dagents=com.example.adkstale.AdkStaleAgent.ROOT_AGENT \
    -Dhost=localhost -Dport=8000
```

Then open <http://localhost:8000/dev-ui> and pick the `adk_stale_issue_auditor`
agent from the dropdown.

--------------------------------------------------------------------------------

## Verifying It Works

Because this agent mutates real GitHub issues, verify it in layers — cheapest
and safest first:

### 1. Unit tests (no secrets, no network)

The deterministic logic (history reconstruction, audit-state computation, label
allowlist, authorization guard, and the dry-run short-circuits) is covered by
JUnit tests. From the repository root:

```bash
./mvnw -pl contrib/samples/github/adkstale -am test
```

### 2. `DRY_RUN` — full live pipeline, zero writes

Set `DRY_RUN=1` to exercise the entire pipeline (real GraphQL fetches, real
Gemini calls) while the comment/label/close tools only **log** what they *would*
do and return a `"dry_run": true` envelope instead of calling GitHub's mutation
endpoints:

```bash
# Install the ADK libs + this sample once (no env vars needed for the build):
./mvnw -q -pl contrib/samples/github/adkstale -am install -DskipTests

# Then run exec:java scoped to this module, with the env vars on the exec step:
GITHUB_TOKEN=… GOOGLE_API_KEY=… GOOGLE_GENAI_USE_VERTEXAI=0 \
INTERACTIVE=0 EVENT_NAME=schedule ISSUE_COUNT_TO_PROCESS=3 DRY_RUN=1 \
./mvnw -q -pl contrib/samples/github/adkstale exec:java
```

This is the recommended way to confirm the workflow end-to-end before enabling
real writes. The same command without `DRY_RUN` is exactly what CI runs.

### 3. `workflow_dispatch`

Once the workflow is installed, trigger it manually from the Actions tab and
watch the logs — ideally with `DRY_RUN` left at `1` for the first run.

--------------------------------------------------------------------------------

## GitHub Workflow Mode

In workflow mode the agent runs fully unattended (`INTERACTIVE=0`):

*   **With `ISSUE_NUMBER` set** → audits that single issue.
*   **Otherwise** → searches for issues old enough to be stale candidates (via
    the Search API's `created:<DATE` filter) and audits up to
    `ISSUE_COUNT_TO_PROCESS` (default 20) of them.

The supplied workflow at `.github/workflows/stale-adk-java-issues.yml` runs:

1.  **Daily at 06:00 UTC** (`schedule`) — the batch sweep.
2.  **Manual dispatch** (`workflow_dispatch`) — run on demand (handy for a first
    `DRY_RUN` verification).

> **Heads up:** the workflow ships with `DRY_RUN: '1'`, so the first runs only
> *log* the comments/labels/closures they would make. Flip it to `'0'` once
> you've confirmed the output looks right.

### Installation

Set this secret on the repository:

| Secret           | Purpose                                            |
| ---------------- | -------------------------------------------------- |
| `GOOGLE_API_KEY` | Gemini API key for the agent (or wire up Vertex AI |
:                  : service accounts).                                 :

Commenting, labelling and closing use the workflow's built-in `GITHUB_TOKEN`,
which the `permissions: issues: write` block scopes appropriately — there is no
PAT to create or rotate. Provide your own PAT (and point `GITHUB_TOKEN` at it in
the workflow) only if you want stale actions attributed to a distinct bot
identity.

> **Maintainer detection:** classifying actors needs the repository's
> push-access collaborator list. The built-in `GITHUB_TOKEN` may not be able to
> read it; if so, set the `ADK_MAINTAINERS` repository variable (a
> comma-separated list of GitHub handles) — the workflow passes it through as
> the `MAINTAINERS` environment variable, which overrides the API lookup.

### Safety and prompt injection

Issue titles, bodies and comments are untrusted input fed to the model, so this
sample defends in depth:

*   The mutating tools only act on a fixed **label allowlist** (`stale` /
    `request clarification`) and are **bound to authorized issues** — in
    single-issue mode only the triggering issue, and in batch mode only the
    issues surfaced by the stale-candidate search — so a crafted comment cannot
    steer the agent into mutating an unrelated issue.
*   The shared `GitHubTools` writes are pinned to the configured `OWNER`/`REPO`,
    so untrusted content cannot redirect a write to a different repository.
*   The system instruction marks all issue content as untrusted data, never
    instructions.

Keep `DRY_RUN` on until you trust the output, and review the `permissions:`
block before widening the token's scope.

--------------------------------------------------------------------------------

## Environment Variables

Variable                            | Required | Default                 | Purpose
----------------------------------- | -------- | ----------------------- | -------
`GITHUB_TOKEN`                      | Yes      | —                       | PAT/built-in token with `issues:write`.
`GOOGLE_API_KEY`                    | Yes\*    | —                       | Gemini API key (\*not required if you use Vertex AI).
`GOOGLE_GENAI_USE_VERTEXAI`         | No       | `FALSE`                 | Set to `TRUE` to route Gemini calls through Vertex AI.
`OWNER`                             | No       | `google`                | Repository owner.
`REPO`                              | No       | `adk-java`              | Repository name.
`MODEL`                             | No       | `gemini-flash-latest`   | Gemini model used for reasoning (a Flash model suits this high-volume task).
`INTERACTIVE`                       | No       | `1`                     | `0`/`false` for unattended workflow mode, `1`/`true` for interactive.
`DRY_RUN`                           | No       | `0`                     | `1`/`true` logs intended comment/label/close actions without calling GitHub.
`EVENT_NAME`                        | No       | —                       | GitHub event name (`schedule`, `workflow_dispatch`, `issues`, ...).
`ISSUE_NUMBER`                      | No       | —                       | Audit this single issue instead of running the batch sweep.
`ISSUE_COUNT_TO_PROCESS`            | No       | `20`                    | Max number of stale-candidate issues to audit per batch run.
`STALE_LABEL`                       | No       | `stale`                 | Label that marks an issue stale (must exist in the repo).
`REQUEST_CLARIFICATION_LABEL`       | No       | `request clarification` | Label added alongside `stale` when clarification was requested.
`MAINTAINERS`                       | No       | —                       | Comma-separated handles to treat as maintainers; overrides the collaborator lookup.
`STALE_HOURS_THRESHOLD`             | No       | `168` (7 days)          | Hours of inactivity after a maintainer question before marking stale.
`CLOSE_HOURS_AFTER_STALE_THRESHOLD` | No       | `168` (7 days)          | Hours an issue may stay stale before it is closed.
`GRAPHQL_COMMENT_LIMIT`             | No       | `30`                    | Most recent comments fetched per issue.
`GRAPHQL_EDIT_LIMIT`                | No       | `10`                    | Most recent description edits fetched per issue.
`GRAPHQL_TIMELINE_LIMIT`            | No       | `20`                    | Most recent timeline events fetched per issue.
`SLEEP_BETWEEN_ISSUES_MS`           | No       | `1500`                  | Pause between issues in a batch run (rate-limit friendliness).

--------------------------------------------------------------------------------

## Customizing for adk-java

The thresholds, label names, model and batch size are all environment-driven, so
the common adjustments need no code change. If you need different behavior (e.g.
a different decision tree), edit `AdkStaleAgent.buildInstruction` — it is a pure
method covered by unit tests.
