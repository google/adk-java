# ADK Issue Triaging Agent (Java)

The ADK Issue Triaging Agent is a Java-based agent that triages GitHub issues
for the `google/adk-java` repository. It uses Gemini to analyze each issue,
recommend labels that actually exist in `adk-java`, and assign an owner based on
a configurable round-robin rotation.

This sample is the Java port of
[`adk-python/contributing/samples/adk_team/adk_triaging_agent`](https://github.com/google/adk-python/tree/main/contributing/samples/adk_team/adk_triaging_agent),
adapted to the **real label taxonomy of `adk-java`**. Unlike adk-python,
adk-java has no per-component labels and no `CODEOWNERS` file, so this port
classifies issues with adk-java's own labels and sources triager handles from an
environment variable instead of hard-coding them.

It is built with the [Google ADK for Java](https://github.com/google/adk-java)
itself and doubles as a community sample: every tool is a real `FunctionTool`,
every JSON envelope matches the Python contract, and the agent runs in both
interactive mode (local CLI / `adk web`) and unattended GitHub Actions workflow
mode. All GitHub access goes through the shared `GitHubTools` (backed by the
[`org.kohsuke:github-api`](https://github-api.kohsuke.org/) client) that this
sample reuses with the ADK Docs Release Analyzer.

--------------------------------------------------------------------------------

## Triaging Workflow

The agent performs different actions based on the issue state:

| Condition                          | Actions                                |
| ---------------------------------- | -------------------------------------- |
| Issue without a recognized label   | Add a kind label                       |
:                                    : (`bug`/`enhancement`) + optional topic :
:                                    : label                                  :
| Issue without an assignee          | Round-robin assign an owner            |
| Issue with no recognized label AND | Add label(s) + Assign owner            |
: no assignee                        :                                        :

### Labels

The agent may only apply labels that exist in `google/adk-java`, listed in
`AdkTriagingAgent.COMPONENT_LABELS`:

*   **Kind:** `bug` (bug reports), `enhancement` (feature requests).
*   **Topic:** `documentation`, `question`, `testing`, `sample`, `dependencies`,
    `github`.

adk-java categorizes issue kind via the `bug` / `enhancement` **labels** (not
GitHub's native issue-type field), so this agent applies those labels directly.

--------------------------------------------------------------------------------

## Project Layout

```
contrib/samples/github/
├── GitHubTools.java         // Shared kohsuke-based GitHub tools (reused across samples)
└── adktriaging/
    ├── AdkTriagingAgent.java     // LlmAgent definition + 3 @Schema-annotated FunctionTools
    ├── AdkTriagingAgentRun.java  // Entry point: interactive + workflow modes
    ├── Settings.java             // Environment-variable configuration (lazy accessors)
    ├── pom.xml                   // Maven module config
    ├── src/test/java/...         // Unit tests for the deterministic logic
    └── README.md                 // This file
```

The GitHub Actions workflow lives at
`.github/workflows/triage-adk-java-issues.yml`.

--------------------------------------------------------------------------------

## Interactive Mode

Use interactive mode locally to dry-run the agent's recommendations before any
changes are made to your repository's issues.

In this mode the agent's system instruction includes `Only label them when the
user approves the labeling!` — the model will describe its recommendations and
wait for your confirmation before invoking the labeling tools.

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
./mvnw -pl contrib/samples/github/adktriaging -am install -DskipTests
./mvnw -pl contrib/samples/github/adktriaging exec:java
```

The REPL prompts for a request, e.g. `triage the 3 oldest untriaged issues`,
streams every model event back to the terminal, and waits for your approval
before each tool call.

### Option B — ADK Web UI

The Java equivalent of Python's `adk web` is the `web` goal of the
[`google-adk-maven-plugin`](https://github.com/google/adk-java/tree/main/maven_plugin).
The goal loads an agent from a static-field reference, so it must run **in this
module's context** (so `AdkTriagingAgent` is on the runtime classpath). From
this module's directory:

```bash
cd contrib/samples/github/adktriaging
mvn google-adk:web \
    -Dagents=com.example.adktriaging.AdkTriagingAgent.ROOT_AGENT \
    -Dhost=localhost -Dport=8000
```

See the
[plugin README](https://github.com/google/adk-java/tree/main/maven_plugin) for
plugin-prefix setup (e.g. adding `com.google.adk` to your `pluginGroups`, or
invoking the fully-qualified goal). Then open <http://localhost:8000/dev-ui> and
pick the `adk_triaging_assistant` agent from the dropdown. The same
approval-based instruction applies.

--------------------------------------------------------------------------------

## Verifying It Works

Because this agent mutates real GitHub issues, verify it in layers — cheapest
and safest first:

### 1. Unit tests (no secrets, no network)

The deterministic logic (label allowlist, rotation parsing, dry-run/placeholder
guards, and the triage-decision filter) is covered by JUnit tests:

From the repository root:

```bash
./mvnw -pl contrib/samples/github/adktriaging -am test
```

### 2. `DRY_RUN` — full live pipeline, zero writes

Set `DRY_RUN=1` to exercise the entire pipeline (real Gemini calls, real issue
fetching) while the label/assign tools only **log** what they *would* do and
return a `"dry_run": true` envelope instead of calling GitHub's mutation
endpoints:

From the repository root:

```bash
# Install the ADK libs + this sample once (no env vars needed for the build):
./mvnw -q -pl contrib/samples/github/adktriaging -am install -DskipTests

# Then run exec:java scoped to this module, with the env vars on the exec step
# (exec:java with -am would also run on the parent/core modules, which have no
# mainClass):
GITHUB_TOKEN=… GOOGLE_API_KEY=… GOOGLE_GENAI_USE_VERTEXAI=0 \
INTERACTIVE=0 EVENT_NAME=schedule ISSUE_COUNT_TO_PROCESS=1 DRY_RUN=1 \
./mvnw -q -pl contrib/samples/github/adktriaging exec:java
```

This is the recommended way to confirm the workflow end-to-end before enabling
real writes. The same command without `DRY_RUN` is exactly what CI runs.

### 3. `workflow_dispatch`

Once the workflow is installed, trigger it manually from the Actions tab (it
supports `workflow_dispatch`) and watch the logs — ideally with the `DRY_RUN`
env set to `1` in the workflow for the first run.

--------------------------------------------------------------------------------

## GitHub Workflow Mode

In workflow mode the agent runs fully unattended: it discovers untriaged issues,
applies labels, and assigns owners — no human confirmation. Triggered by
`INTERACTIVE=0`.

> **Note:** owner assignment is skipped unless `GTECH_ASSIGNEES` is set (see
> [Environment Variables](#environment-variables)). Until then the agent only
> applies labels: the assignment tool is withheld from the model entirely, so
> the run spends no model/GitHub calls attempting (or retrying) assignments it
> cannot make.

> **Heads up:** the workflow ships with `DRY_RUN: '1'`, so the first runs only
> *log* the labels/assignees they would apply. Flip it to `'0'` once you've
> confirmed the output looks right.

### Safety and prompt injection

Issue titles and bodies are untrusted input fed to the model, so this sample
defends in depth: tools only apply labels from a fixed [allowlist](#labels),
owner assignment is a deterministic round-robin (the model never picks a
person), and the mutating tools are **bound to authorized issues** — in
single-issue mode only the triggering issue, and in batch mode only the issues
returned by `list_untriaged_issues` — so a crafted body cannot steer the agent
into modifying an unrelated issue. The shared `GitHubTools` writes are
additionally pinned to the configured `OWNER`/`REPO`, so untrusted content
cannot redirect a label or assignment to a different repository. **Residual
risk:** a sufficiently clever body could still mislead the *classification* of
its own issue (e.g. nudging `bug` vs. `enhancement`); the blast radius is
bounded to a wrong-but-valid label on that one issue. Keep `DRY_RUN` on until
you trust the output, and review the `permissions:` block before widening the
token's scope.

### Triggers

The supplied workflow runs the agent on:

1.  **New issues (`opened`)** — classifies the issue and applies labels.
2.  **Schedule (every 6 hours)** — batch-processes up to
    `ISSUE_COUNT_TO_PROCESS` (default `3`) untriaged issues to act as a safety
    net.
3.  **Manual dispatch (`workflow_dispatch`)** — run on demand from the Actions
    tab (handy for a first `DRY_RUN` verification).

### Installation

The workflow at `.github/workflows/triage-adk-java-issues.yml` is ready to run
in the `adk-java` repository. Set this secret on the repository:

| Secret           | Purpose                                            |
| ---------------- | -------------------------------------------------- |
| `GOOGLE_API_KEY` | Gemini API key for the agent (or wire up Vertex AI |
:                  : service accounts).                                 :

Labeling and assignment use the workflow's built-in `GITHUB_TOKEN`, which the
`permissions: issues: write` block scopes appropriately — there is no PAT to
create or rotate. Provide your own PAT (and point `GITHUB_TOKEN` at it in the
workflow) only if you want triage actions attributed to a distinct bot identity.

### How it runs

The workflow checks out the repo, installs Temurin Java 17, then runs:

```bash
# Install the ADK libs + sample, then run exec:java scoped to this module
# (exec:java with -am would also run on the parent/core modules, which have no
# mainClass).
./mvnw -q -pl contrib/samples/github/adktriaging -am install -DskipTests
./mvnw -q -pl contrib/samples/github/adktriaging exec:java
```

with the environment variables passed in by the workflow file.

--------------------------------------------------------------------------------

## Environment Variables

Variable                    | Required | Default             | Purpose
--------------------------- | -------- | ------------------- | -------
`GITHUB_TOKEN`              | Yes      | —                   | PAT with `issues:write`.
`GOOGLE_API_KEY`            | Yes\*    | —                   | Gemini API key (\*not required if you use Vertex AI).
`GOOGLE_GENAI_USE_VERTEXAI` | No       | `FALSE`             | Set to `TRUE` to route Gemini calls through Vertex AI.
`OWNER`                     | No       | `google`            | Repository owner.
`REPO`                      | No       | `adk-java`          | Repository name.
`MODEL`                     | No       | `gemini-pro-latest` | Gemini model used for triaging (a Pro model favors classification quality).
`INTERACTIVE`               | No       | `1`                 | `0`/`false` for unattended workflow mode, `1`/`true` for interactive.
`DRY_RUN`                   | No       | `0`                 | `1`/`true` logs intended label/assign actions without calling GitHub.
`EVENT_NAME`                | No       | —                   | GitHub event name (`issues`, `schedule`, ...). Drives single-issue path.
`ISSUE_NUMBER`              | No       | —                   | Set by GitHub Actions for `issues` events.
`ISSUE_TITLE`               | No       | —                   | Set by GitHub Actions for `issues` events.
`ISSUE_BODY`                | No       | —                   | Set by GitHub Actions for `issues` events.
`ISSUE_COUNT_TO_PROCESS`    | No       | `3`                 | Max number of issues to batch-process per scheduled run.
`GTECH_ASSIGNEES`           | No       | —                   | Comma-separated GitHub handles for round-robin owner assignment. When unset, owner assignment is disabled.

--------------------------------------------------------------------------------

## Customizing for adk-java

`AdkTriagingAgent.COMPONENT_LABELS` already lists labels that exist in
`google/adk-java`, and `LABEL_GUIDELINES` describes each one. Owner handles are
**not** hard-coded (adk-java has no public `CODEOWNERS`), so to enable owner
assignment you only need to set one environment variable:

```bash
export GTECH_ASSIGNEES="handle1,handle2,handle3"
```

Issues are assigned round-robin via `issue_number % N`. Until `GTECH_ASSIGNEES`
is set, owner assignment is disabled: the assignment tool is not registered with
the agent and the system instruction tells the model not to assign anyone, so
the agent applies labels and reports that no triagers are configured (without
spending calls retrying an assignment it cannot make).

If adk-java's label set changes, edit `AdkTriagingAgent.COMPONENT_LABELS` and
the matching `AdkTriagingAgent.LABEL_GUIDELINES` rubric — both are normal
`static final` fields, no other code changes required.
