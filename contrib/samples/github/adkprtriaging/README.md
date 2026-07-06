# ADK PR Triaging Agent (Java)

The ADK PR Triaging Agent is a Java-based agent that triages GitHub pull
requests for the `google/adk-java` repository. It uses Gemini to analyze each
pull request, recommend a label that actually exists in `adk-java`, and check the
PR against the repository's contribution guidelines — posting a single, polite
comment when something important is missing.

This sample is the Java port of
[`adk-python/contributing/samples/adk_team/adk_pr_triaging_agent`](https://github.com/google/adk-python/tree/main/contributing/samples/adk_team/adk_pr_triaging_agent),
adapted to the **real label taxonomy of `adk-java`**. The Python agent applies
one of ten adk-python *component* labels (`services`, `models`, `mcp`, …) that do
not exist in `adk-java`, so — exactly like the sibling
[ADK Issue Triaging Agent](../adktriaging) — this port classifies PRs with
`adk-java`'s own labels.

It is built with the [Google ADK for Java](https://github.com/google/adk-java)
itself and doubles as a community sample: every tool is a real `FunctionTool`,
every JSON envelope matches the Python contract, and the agent runs in both
interactive mode (local CLI / `adk web`) and unattended GitHub Actions workflow
mode. All GitHub access goes through the shared `GitHubTools` (backed by the
[`org.kohsuke:github-api`](https://github-api.kohsuke.org/) client) that this
sample reuses with the ADK Issue Triaging Agent and the ADK Docs Release
Analyzer.

--------------------------------------------------------------------------------

## Triaging Workflow

For each pull request the agent:

1.  Fetches the PR details (title, body, state, author, labels, changed files,
    commits, recent comments, status checks and a truncated diff).
2.  **Skips** the PR (no label, no comment) when it is closed or already carries
    one of the allowed labels.
3.  Otherwise **labels** it with the single most appropriate label and, if the
    PR does not meet the contribution guidelines, **comments** asking the author
    for the missing context.

### Labels

The agent may only apply labels that exist in `google/adk-java`, listed in
`AdkPrTriagingAgent.ALLOWED_LABELS`:

`bug`, `enhancement`, `documentation`, `testing`, `sample`, `dependencies`,
`github`.

### Contribution-guideline checks

The agent embeds the repository's `CONTRIBUTING.md` (read at runtime) plus a
summary of the `adk-java` PR policy, and uses the PR's `status_checks`,
`commit_count`, `body` and `diff` to flag common gaps:

*   missing/failing **CLA** check,
*   more than a **single commit**,
*   a bug-fix PR with no **linked issue**,
*   an insufficient **description**.

To avoid spamming authors, every comment includes the bolded marker
`Response from ADK PR Triaging Agent`, and the agent is instructed not to comment
again when a comment containing that marker is already present.

--------------------------------------------------------------------------------

## Project Layout

```
contrib/samples/github/
├── GitHubTools.java         // Shared kohsuke-based GitHub tools (reused across samples)
└── adkprtriaging/
    ├── AdkPrTriagingAgent.java     // LlmAgent definition + 3 @Schema-annotated FunctionTools
    ├── AdkPrTriagingAgentRun.java  // Entry point: interactive + workflow modes
    ├── Settings.java               // Environment-variable configuration (lazy accessors)
    ├── pom.xml                     // Maven module config
    ├── src/test/java/...           // Unit tests for the deterministic logic
    └── README.md                   // This file
```

The GitHub Actions workflow lives at
`.github/workflows/pr-triage-adk-java.yml`.

--------------------------------------------------------------------------------

## Interactive Mode

Use interactive mode locally to dry-run the agent's recommendations before any
changes are made to your repository's pull requests.

In this mode the agent's system instruction includes `Only label or comment when
the user approves the labeling or commenting!` — the model describes its
recommendations and waits for your confirmation before invoking the
labeling/comment tools.

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
./mvnw -pl contrib/samples/github/adkprtriaging -am install -DskipTests
./mvnw -pl contrib/samples/github/adkprtriaging exec:java
```

The REPL prompts for a request, e.g. `triage pull request #123`, streams every
model event back to the terminal, and waits for your approval before each tool
call.

### Option B — ADK Web UI

The Java equivalent of Python's `adk web` is the `web` goal of the
[`google-adk-maven-plugin`](https://github.com/google/adk-java/tree/main/maven_plugin).
The goal loads an agent from a static-field reference, so it must run **in this
module's context** (so `AdkPrTriagingAgent` is on the runtime classpath). From
this module's directory:

```bash
cd contrib/samples/github/adkprtriaging
mvn google-adk:web \
    -Dagents=com.example.adkprtriaging.AdkPrTriagingAgent.ROOT_AGENT \
    -Dhost=localhost -Dport=8000
```

See the
[plugin README](https://github.com/google/adk-java/tree/main/maven_plugin) for
plugin-prefix setup. Then open <http://localhost:8000/dev-ui> and pick the
`adk_pr_triaging_assistant` agent from the dropdown. The same approval-based
instruction applies.

--------------------------------------------------------------------------------

## Verifying It Works

Because this agent mutates real GitHub pull requests, verify it in layers —
cheapest and safest first:

### 1. Unit tests (no secrets, no network)

The deterministic logic (label allowlist, tool wiring, instruction construction,
authorization guard, and the dry-run label/comment short-circuits) is covered by
JUnit tests. From the repository root:

```bash
./mvnw -pl contrib/samples/github/adkprtriaging -am test
```

### 2. `DRY_RUN` — full live pipeline, zero writes

Set `DRY_RUN=1` to exercise the entire pipeline (real Gemini calls, real PR
fetching) while the label/comment tools only **log** what they *would* do and
return a `"dry_run": true` envelope instead of calling GitHub's mutation
endpoints:

```bash
# Install the ADK libs + this sample once (no env vars needed for the build):
./mvnw -q -pl contrib/samples/github/adkprtriaging -am install -DskipTests

# Then run exec:java scoped to this module, with the env vars on the exec step:
GITHUB_TOKEN=… GOOGLE_API_KEY=… GOOGLE_GENAI_USE_VERTEXAI=0 \
INTERACTIVE=0 PULL_REQUEST_NUMBER=123 DRY_RUN=1 \
./mvnw -q -pl contrib/samples/github/adkprtriaging exec:java
```

This is the recommended way to confirm the workflow end-to-end before enabling
real writes. The same command without `DRY_RUN` is exactly what CI runs.

### 3. `workflow_dispatch`

Once the workflow is installed, trigger it manually from the Actions tab (it
supports `workflow_dispatch` with a `pr_number` input) and watch the logs —
ideally with `DRY_RUN` set to `1` for the first run.

--------------------------------------------------------------------------------

## GitHub Workflow Mode

In workflow mode the agent runs fully unattended: it triages the single pull
request that triggered the workflow — no human confirmation. Triggered by
`INTERACTIVE=0`.

> **Heads up:** the workflow ships with `DRY_RUN: '1'`, so the first runs only
> *log* the labels/comments they would apply. Flip it to `'0'` once you've
> confirmed the output looks right.

### Safety and prompt injection

PR titles, bodies and diffs are untrusted input fed to the model, so this sample
defends in depth:

*   The workflow uses `pull_request_target` but relies on the **default checkout
    (the base branch)** and never checks out the PR head, so untrusted PR code is
    never executed — the agent only reads the PR through the GitHub API.
*   Tools only apply labels from a fixed [allowlist](#labels).
*   The mutating tools are **bound to the authorized PR** (the one the workflow
    was triggered for), so a crafted body cannot steer the agent into modifying
    an unrelated pull request.
*   The shared `GitHubTools` writes are pinned to the configured `OWNER`/`REPO`,
    so untrusted content cannot redirect a label or comment to a different
    repository.

**Residual risk:** a sufficiently clever body could still mislead the
*classification* of its own PR (e.g. nudging `bug` vs. `enhancement`); the blast
radius is bounded to a wrong-but-valid label, or one extra comment, on that one
PR. Keep `DRY_RUN` on until you trust the output, and review the `permissions:`
block before widening the token's scope.

### Triggers

The supplied workflow runs the agent when a PR is `opened`, `reopened`, or
`edited`, and on manual `workflow_dispatch` (with a `pr_number` input).

### Installation

The workflow at `.github/workflows/pr-triage-adk-java.yml` is ready to run in the
`adk-java` repository. Set this secret on the repository:

| Secret           | Purpose                                            |
| ---------------- | -------------------------------------------------- |
| `GOOGLE_API_KEY` | Gemini API key for the agent (or wire up Vertex AI |
:                  : service accounts).                                 :

Labeling and commenting use the workflow's built-in `GITHUB_TOKEN`, which the
`permissions: pull-requests: write` block scopes appropriately — there is no PAT
to create or rotate. Provide your own PAT (and point `GITHUB_TOKEN` at it in the
workflow) only if you want triage actions attributed to a distinct bot identity.

### How it runs

The workflow checks out the repo, installs Temurin Java 17, then runs:

```bash
./mvnw -q -pl contrib/samples/github/adkprtriaging -am install -DskipTests
./mvnw -q -pl contrib/samples/github/adkprtriaging exec:java
```

with the environment variables passed in by the workflow file.

--------------------------------------------------------------------------------

## Environment Variables

Variable                    | Required | Default             | Purpose
--------------------------- | -------- | ------------------- | -------
`GITHUB_TOKEN`              | Yes      | —                   | PAT with `pull_requests:write`.
`GOOGLE_API_KEY`            | Yes\*    | —                   | Gemini API key (\*not required if you use Vertex AI).
`GOOGLE_GENAI_USE_VERTEXAI` | No       | `FALSE`             | Set to `TRUE` to route Gemini calls through Vertex AI.
`OWNER`                     | No       | `google`            | Repository owner.
`REPO`                      | No       | `adk-java`          | Repository name.
`MODEL`                     | No       | `gemini-pro-latest` | Gemini model used for triaging (a Pro model favors classification quality).
`INTERACTIVE`               | No       | `1`                 | `0`/`false` for unattended workflow mode, `1`/`true` for interactive.
`DRY_RUN`                   | No       | `0`                 | `1`/`true` logs intended label/comment actions without calling GitHub.
`PULL_REQUEST_NUMBER`       | No       | —                   | The PR to triage in workflow mode (set by GitHub Actions).
`EVENT_NAME`                | No       | —                   | GitHub event name (logged for diagnostics).
`CONTRIBUTING_MD_PATH`      | No       | `CONTRIBUTING.md`   | Path to the repo's `CONTRIBUTING.md`, embedded in the instruction. Missing file is tolerated.

--------------------------------------------------------------------------------

## Customizing for adk-java

`AdkPrTriagingAgent.ALLOWED_LABELS` already lists labels that exist in
`google/adk-java`, and `LABEL_GUIDELINES` describes each one. If `adk-java`'s
label set changes, edit `ALLOWED_LABELS` and the matching `LABEL_GUIDELINES`
rubric — both are normal `static final` fields, no other code changes required.

The contribution-guideline check reads `CONTRIBUTING.md` at runtime, so it stays
in sync with the repository automatically.
