# ADK Team Automations (Java)

This directory contains AI-powered GitHub automation agents for the `adk-java`
repository, implemented in idiomatic Java with the Google ADK for Java. Each
agent is both:

*   a production-grade automation that runs inside a GitHub Actions workflow,
    and
*   a reference sample showing how to build a real-world ADK agent in Java
    (function tools, structured outputs, multi-mode entry points).

These automations are the Java counterpart to the Python automations in
[`google/adk-python/contributing/samples/adk_team/`](https://github.com/google/adk-python/tree/main/contributing/samples/adk_team).

## Modules

| Module                        | Description                                 |
| ----------------------------- | ------------------------------------------- |
| `adk_triaging_agent/`         | First-pass issue triage: applies adk-java's |
:                               : kind/topic labels and round-robin assigns   :
:                               : issues to owners (handles supplied via env  :
:                               : var).                                       :
| `adk_issue_monitoring_agent/` | Spam auditor: scans non-maintainer issue    |
:                               : comments for spam/SEO/promotional content   :
:                               : and, when detected, applies a `spam` label  :
:                               : and alerts maintainers.                     :

More automations (PR triage, stale-issue auditor, release-to-docs analyzer) will
land here over time. They share the same env-var-driven config, GitHub REST
client patterns, and dual interactive/workflow entry points so all the modules
feel consistent to ADK Java contributors.
