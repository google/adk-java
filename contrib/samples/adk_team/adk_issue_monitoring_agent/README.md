<!--
 Copyright 2026 Google LLC

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->

# ADK Issue Monitoring Agent (Java) 🛡️

An automated moderation agent built with the **Google Agent Development Kit
(ADK) for Java**. It audits GitHub issue comments for SEO spam, unsolicited
promotional links, and irrelevant third-party endorsements. When spam is
detected it applies a `spam` label and posts a single comment alerting the
maintainers.

This is the Java port of the Python
[`adk_issue_monitoring_agent`](https://github.com/google/adk-python/tree/main/contributing/samples/adk_team/adk_issue_monitoring_agent)
sample, adapted to the `google/adk-java` repository. GitHub access goes through
the [`org.kohsuke:github-api`](https://github-api.kohsuke.org/) client (auth,
pagination, and JSON), so the sample doesn't hand-roll a REST client.

## How it works

1.  **Fetch maintainers** — the repository's collaborators (and `[bot]` accounts
    and the official `adk-bot`) are never treated as spam.
2.  **Fetch target issues** — all open issues when `INITIAL_FULL_SCAN=1`,
    otherwise only those updated in the last 24 hours. Issues already carrying
    the `spam` label are skipped.
3.  **Pre-filter in Java** — code blocks are stripped and long text truncated;
    threads the bot has already flagged are skipped. The Gemini model is only
    invoked when there is non-maintainer text to review, so there is **zero LLM
    cost for safe threads**.
4.  **Flag spam** — the agent calls the single `flag_issue_as_spam` tool, which
    adds the `spam` label and posts one maintainer alert. It re-reads state
    first, so re-runs don't duplicate the label or comment, and it refuses any
    issue outside the run's scanned set (a prompt-injection guard).

## Configuration

All configuration is via environment variables (typically GitHub Actions
secrets/vars).

### Required

| Variable         | Description                                            |
| :--------------- | :----------------------------------------------------- |
| `GITHUB_TOKEN`   | Token with `issues: write` scope (the built-in Actions |
:                  : token works).                                          :
| `GOOGLE_API_KEY` | Gemini API key (or configure Vertex AI +               |
:                  : `GOOGLE_GENAI_USE_VERTEXAI`).                          :

### Optional

| Variable            | Description                       | Default            |
| :------------------ | :-------------------------------- | :----------------- |
| `INITIAL_FULL_SCAN` | `1` to audit every open issue;    | `false`            |
:                     : otherwise only the last 24 hours. :                    :
| `DRY_RUN`           | `1` to log intended actions       | `false`            |
:                     : without writing to GitHub.        :                    :
| `SPAM_LABEL_NAME`   | Label applied to flagged issues.  | `spam`             |
| `BOT_NAME`          | Username of the official bot,     | `adk-bot`          |
:                     : whose comments are ignored.       :                    :
| `CONCURRENCY_LIMIT` | Issues processed concurrently.    | `3`                |
| `LLM_MODEL_NAME`    | Gemini model to use.              | `gemini-2.5-flash` |
| `OWNER`             | Repository owner.                 | `google`           |
| `REPO`              | Repository name.                  | `adk-java`         |

## Running locally

From the `adk-java` repository root:

```bash
export GITHUB_TOKEN="<token with issues:write>"
export GOOGLE_API_KEY="<your Gemini API key>"
export DRY_RUN=1          # verify the pipeline first; set to 0 to go live
export INITIAL_FULL_SCAN=1

./mvnw -pl contrib/samples/adk_team/adk_issue_monitoring_agent -am compile exec:java
```

## Deployment

Deploy the agent as a scheduled GitHub Actions workflow at
`.github/workflows/issue-monitor.yml` that runs `./mvnw -pl
contrib/samples/adk_team/adk_issue_monitoring_agent -am compile exec:java` on a
daily cron (plus manual dispatch with an optional full scan). Keep `DRY_RUN=1`
so the first scheduled runs only log their intended actions, then flip `DRY_RUN`
to `0` to let it apply labels and post alerts.
