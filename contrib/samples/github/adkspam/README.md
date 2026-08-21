# ADK Issue Monitoring (Spam Detection) Agent (Java)

The ADK Issue Monitoring Agent is a Java-based agent that audits GitHub issues
for the `google/adk-java` repository. It uses Gemini to scan issue threads (the
original description plus non-maintainer comments) for SEO spam, unsolicited
promotional links, and other objectionable content. When spam is detected it
applies a `spam` label and posts a single alert comment for human maintainers.
**Nothing is ever deleted — the agent flags, humans decide.**

This sample is the Java port of
[`adk-python/contributing/samples/adk_team/adk_issue_monitoring_agent`](https://github.com/google/adk-python/tree/main/contributing/samples/adk_team/adk_issue_monitoring_agent),
adapted to the conventions of `adk-java`.

It is built with the [Google ADK for Java](https://github.com/google/adk-java)
itself and doubles as a community sample: the spam-flagging action is a real
`FunctionTool`, every JSON envelope matches the Python contract, and the agent
runs in both interactive mode (local CLI / `adk web`) and unattended GitHub
Actions workflow mode. All GitHub access goes through the shared `GitHubTools`
(backed by the [`org.kohsuke:github-api`](https://github-api.kohsuke.org/)
client) that this sample reuses with the ADK Issue Triaging Agent and the ADK
Docs Release Analyzer.

--------------------------------------------------------------------------------

## Key Features & Optimizations

Faithfully ported from the Python sample:

*   **Zero-waste LLM invocations:** Issues and comments are fetched via the
    GitHub API and pre-filtered in Java *before* the model runs. Content from
    maintainers (repository collaborators), `[bot]` accounts, and the official
    `adk-bot` is ignored. Gemini is never invoked for safe threads, saving the
    full token cost.
*   **Dual-mode scanning:** A **full scan** (`INITIAL_FULL_SCAN=1`) audits every
    open issue; the default **daily sweep** only audits issues updated in the
    last 24 hours.
*   **Token truncation:** Markdown code blocks (` ``` `) are replaced with
    `[CODE BLOCK REMOVED]` and unusually long text is truncated to 1,500
    characters before being sent to the model.
*   **Idempotency (anti-double-posting):** Before flagging, the agent checks the
    issue's labels and comment history for its own alert signature. If a thread
    is already flagged it is skipped, preventing duplicate labels/comments.

--------------------------------------------------------------------------------

## Project Layout

```
contrib/samples/github/
├── GitHubTools.java          // Shared kohsuke-based GitHub tools (reused across samples)
└── adkspam/
    ├── SpamDetectionAgent.java     // LlmAgent definition + the flag_issue_as_spam FunctionTool
    ├── SpamDetectionAgentRun.java  // Entry point: interactive + workflow modes, pre-filtering
    ├── Settings.java               // Environment-variable configuration (lazy accessors)
    ├── pom.xml                     // Maven module config
    ├── src/test/java/...           // Unit tests for the deterministic logic
    └── README.md                   // This file
```

The GitHub Actions workflow lives at
`.github/workflows/spam-detection-adk-java-issues.yml`.

> **Prerequisite:** the `spam` label (or whatever `SPAM_LABEL_NAME` is set to)
> must already exist in the repository. The agent applies the label but does not
> create it.

--------------------------------------------------------------------------------

## How It Works

The agent gives the model exactly one tool, `flag_issue_as_spam`. All the
cost-saving pre-filtering happens in `SpamDetectionAgentRun` before the model is
invoked:

1.  Fetch the repository's collaborators (treated as maintainers).
2.  Fetch the target issues — all open issues (full scan) or only those updated
    in the last 24 hours (daily sweep) — skipping any already carrying the spam
    label.
3.  For each issue, fetch its comments and assemble the reviewable text: the
    original description (unless authored by a maintainer/bot) plus every
    non-maintainer comment, each with code blocks stripped and truncated.
4.  Skip the issue entirely if the bot has already alerted on it, or if there is
    no non-maintainer text to review.
5.  Otherwise, send the compiled text to Gemini. If the model identifies spam it
    calls `flag_issue_as_spam`, which (idempotently) applies the `spam` label
    and posts one alert comment.

--------------------------------------------------------------------------------

## Interactive Mode

Use interactive mode locally to see the agent's reasoning before any change is
made to your repository's issues.

In this mode the agent's system instruction asks it to describe which text is
spam and wait for your confirmation before invoking the flagging tool.

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
./mvnw -pl contrib/samples/github/adkspam -am install -DskipTests
./mvnw -pl contrib/samples/github/adkspam exec:java
```

The REPL accepts a thread to review (or a request like `review issue #123 for
spam`), streams every model event back to the terminal, and waits for your
approval before flagging.

### Option B — ADK Web UI

The Java equivalent of Python's `adk web` is the `web` goal of the
[`google-adk-maven-plugin`](https://github.com/google/adk-java/tree/main/maven_plugin).
The goal loads an agent from a static-field reference, so it must run **in this
module's context** (so `SpamDetectionAgent` is on the runtime classpath). From
this module's directory:

```bash
cd contrib/samples/github/adkspam
mvn google-adk:web \
    -Dagents=com.example.adkspam.SpamDetectionAgent.ROOT_AGENT \
    -Dhost=localhost -Dport=8000
```

Then open <http://localhost:8000/dev-ui> and pick the `spam_auditor_agent` agent
from the dropdown. The same approval-based instruction applies.

--------------------------------------------------------------------------------

## Verifying It Works

Because this agent mutates real GitHub issues, verify it in layers — cheapest
and safest first:

### 1. Unit tests (no secrets, no network)

The deterministic logic (code-block stripping/truncation, maintainer/bot
detection, review-item assembly, the alert-comment builder, idempotency
predicates, and the authorization guard) is covered by JUnit tests:

```bash
./mvnw -pl contrib/samples/github/adkspam -am test
```

### 2. `DRY_RUN` — full live pipeline, zero writes

Set `DRY_RUN=1` to exercise the entire pipeline (real Gemini calls, real issue
fetching) while the label/comment tools only **log** what they *would* do and
return a `"dry_run": true` envelope instead of calling GitHub's mutation
endpoints:

```bash
# Install the ADK libs + this sample once (no env vars needed for the build):
./mvnw -q -pl contrib/samples/github/adkspam -am install -DskipTests

# Then run exec:java scoped to this module, with the env vars on the exec step:
GITHUB_TOKEN=… GOOGLE_API_KEY=… GOOGLE_GENAI_USE_VERTEXAI=0 \
INTERACTIVE=0 EVENT_NAME=schedule INITIAL_FULL_SCAN=1 DRY_RUN=1 \
./mvnw -q -pl contrib/samples/github/adkspam exec:java
```

This is the recommended way to confirm the workflow end-to-end before enabling
real writes. The same command without `DRY_RUN` is what CI runs.

### 3. `workflow_dispatch`

Once the workflow is installed, trigger it manually from the Actions tab (it
supports `workflow_dispatch`, including a `full_scan` checkbox) and watch the
logs — ideally with `DRY_RUN` set to `1` for the first run.

--------------------------------------------------------------------------------

## GitHub Workflow Mode

In workflow mode the agent runs fully unattended: it discovers issues to audit,
reviews their threads, and flags spam — no human confirmation. Triggered by
`INTERACTIVE=0`.

> **Heads up:** the workflow ships with `DRY_RUN: '1'`, so the first runs only
> *log* the labels/comments they would apply. Flip it to `'0'` once you've
> confirmed the output looks right.

### Safety and prompt injection

Issue and comment bodies are untrusted input fed to the model, so this sample
defends in depth:

*   The reviewed text is fenced and explicitly marked **untrusted** in the
    prompt, and the instruction tells the model to treat it strictly as data.
*   The flagging tool is **bound to the issue currently under review** — a
    crafted comment cannot steer the agent into flagging an unrelated issue.
*   The model never picks a label or a person; it can only apply the fixed,
    configured spam label and post one alert comment.
*   The shared `GitHubTools` writes are pinned to the configured `OWNER`/`REPO`,
    so untrusted content cannot redirect a label/comment to another repository.

**Residual risk:** a sufficiently clever body could still mislead the
*classification* of its own issue (a false positive or false negative on that
one issue); since the agent only flags for human review and never deletes, the
blast radius is bounded. Keep `DRY_RUN` on until you trust the output.

### Triggers

The supplied workflow runs the agent on:

1.  **New issues (`opened`)** — audits the single new issue.
2.  **Schedule (daily at 06:00 UTC)** — sweeps issues updated in the last 24
    hours.
3.  **Manual dispatch (`workflow_dispatch`)** — run on demand, with an optional
    `full_scan` checkbox to audit the entire open backlog.

### Installation

The workflow at `.github/workflows/spam-detection-adk-java-issues.yml` is ready
to run in the `adk-java` repository. Set this secret on the repository:

| Secret           | Purpose                                            |
| ---------------- | -------------------------------------------------- |
| `GOOGLE_API_KEY` | Gemini API key for the agent (or wire up Vertex AI |
:                  : service accounts).                                 :

Labeling and commenting use the workflow's built-in `GITHUB_TOKEN`, which the
`permissions: issues: write` block scopes appropriately — there is no PAT to
create or rotate. Provide your own PAT (and point `GITHUB_TOKEN` at it) only if
you want the spam label/alert comment attributed to a distinct bot identity.

--------------------------------------------------------------------------------

## Environment Variables

Variable                    | Required | Default               | Purpose
--------------------------- | -------- | --------------------- | -------
`GITHUB_TOKEN`              | Yes      | —                     | Token with `issues:write` (the Actions built-in token works).
`GOOGLE_API_KEY`            | Yes\*    | —                     | Gemini API key (\*not required if you use Vertex AI).
`GOOGLE_GENAI_USE_VERTEXAI` | No       | `FALSE`               | Set to `TRUE` to route Gemini calls through Vertex AI.
`OWNER`                     | No       | `google`              | Repository owner.
`REPO`                      | No       | `adk-java`            | Repository name.
`MODEL`                     | No       | `gemini-flash-latest` | Gemini model used for moderation (Flash favors latency/cost for this scan).
`SPAM_LABEL_NAME`           | No       | `spam`                | Label applied to flagged issues (must already exist in the repo).
`BOT_NAME`                  | No       | `adk-bot`             | GitHub handle of the official bot whose content is never scanned.
`BOT_ALERT_SIGNATURE`       | No       | (alert banner)        | Signature written in the alert comment; also the idempotency marker.
`INITIAL_FULL_SCAN`         | No       | `0`                   | `1`/`true` audits all open issues; otherwise only issues updated in the last 24h.
`ISSUE_SCAN_LIMIT`          | No       | `100`                 | Safety cap on how many open issues a single sweep processes.
`INTERACTIVE`               | No       | `1`                   | `0`/`false` for unattended workflow mode, `1`/`true` for interactive.
`DRY_RUN`                   | No       | `0`                   | `1`/`true` logs intended label/comment actions without calling GitHub.
`EVENT_NAME`                | No       | —                     | GitHub event name (`issues`, `schedule`, ...). Drives single-issue vs. sweep.
`ISSUE_NUMBER`              | No       | —                     | Set by GitHub Actions for `issues` events.
`ISSUE_TITLE`               | No       | —                     | Set by GitHub Actions for `issues` events.
`ISSUE_BODY`                | No       | —                     | Set by GitHub Actions for `issues` events.

--------------------------------------------------------------------------------

## Differences From the Python Sample

*   **Interactive mode** is a Java-only addition (matching the other adk-java
    GitHub samples); the Python agent is workflow-only.
*   **Pre-filtering concurrency:** the Python sample audits issues concurrently
    in chunks; this Java port processes them sequentially for simplicity and
    deterministic logging. Behavior (which issues get flagged) is identical.
*   **Maintainer detection** uses the repository's collaborator list. If the
    token cannot read collaborators, the agent logs a warning and proceeds with
    an empty maintainer set (every author is then scanned) rather than aborting.
