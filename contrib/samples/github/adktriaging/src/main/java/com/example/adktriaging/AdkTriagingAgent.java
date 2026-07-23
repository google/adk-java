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
package com.example.adktriaging;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.example.github.GitHubTools;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * ADK Issue Triaging Agent for {@code google/adk-java}.
 *
 * <p>This is the Java port of the Python {@code adk_triaging_agent/agent.py}, adapted to the actual
 * label taxonomy of {@code google/adk-java} (which, unlike adk-python, does not use per-component
 * labels). The agent uses Gemini to:
 *
 * <ul>
 *   <li>recommend a topic/kind label for each open issue (e.g. {@code bug}, {@code enhancement},
 *       {@code documentation}, {@code question}),
 *   <li>round-robin assign owners from a configurable triager rotation.
 * </ul>
 *
 * <p>All GitHub access goes through the shared {@link GitHubTools} (backed by the {@code
 * org.kohsuke:github-api} client) that this sample reuses with the ADK Docs Release Analyzer. Tool
 * methods are exposed as {@link FunctionTool}s and use {@code snake_case} via {@link Schema} so the
 * function declarations seen by the model match the Python implementation. Each tool returns an
 * {@link ImmutableMap} envelope &mdash; {@code {"status": "success", ...}} on success, {@code
 * {"status": "error", "message": "..."}} on failure &mdash; matching the Python contract.
 *
 * <p>NOTE: {@link #COMPONENT_LABELS} contains labels that actually exist in {@code google/adk-java}
 * as of this writing. {@link #gtechRotation()} cannot be derived from any public source (adk-java
 * has no {@code CODEOWNERS} file), so it defaults to an obvious placeholder and must be supplied at
 * runtime via the {@code GTECH_ASSIGNEES} environment variable (a comma-separated list of GitHub
 * handles). Real triager handles never need to live in source.
 */
public final class AdkTriagingAgent {

  // ===========================================================================
  // Configuration: labels, owners, rotation. Customize for adk-java here.
  // ===========================================================================

  /**
   * The set of labels the agent is allowed to apply. These are real labels in {@code
   * google/adk-java}. Unlike adk-python, adk-java has no per-component labels, so this is a flat
   * allowlist of topic/kind labels rather than a label&rarr;owner map.
   *
   * <p>Insertion order is preserved (via {@link ImmutableSet}) for deterministic enumeration.
   */
  public static final ImmutableSet<String> COMPONENT_LABELS =
      ImmutableSet.of(
          "bug",
          "enhancement",
          "documentation",
          "question",
          "testing",
          "sample",
          "dependencies",
          "github");

  /**
   * Kind labels (issue type). The triage rubric allows <b>at most one</b> of these per issue, so
   * before a kind label is applied any other kind label is removed first (see {@link #applyLabel}).
   * This keeps re-runs and re-classification from leaving an issue tagged both {@code bug} and
   * {@code enhancement}.
   */
  static final ImmutableSet<String> KIND_LABELS = ImmutableSet.of("bug", "enhancement");

  /**
   * The clearly-marked placeholder rotation used when {@code GTECH_ASSIGNEES} is not set. The agent
   * refuses to assign anyone while this placeholder is in effect (see {@link
   * #assignGtechOwnerToIssue}).
   */
  private static final ImmutableList<String> PLACEHOLDER_ROTATION =
      ImmutableList.of(
          "REPLACE_WITH_TRIAGER_1", "REPLACE_WITH_TRIAGER_2", "REPLACE_WITH_TRIAGER_3");

  /**
   * Round-robin rotation of triagers. Issues are assigned via {@code issue_number % N}. Sourced
   * from the {@code GTECH_ASSIGNEES} environment variable (comma-separated GitHub handles); falls
   * back to {@link #PLACEHOLDER_ROTATION} when unset.
   *
   * <p>Read lazily (per call) rather than at class load, matching the lazy-accessor pattern in
   * {@link Settings}: this keeps the class loadable in tests/agent loaders and lets the environment
   * be overridden before the rotation is first consulted.
   */
  public static ImmutableList<String> gtechRotation() {
    return parseRotation(Settings.gtechAssignees());
  }

  /**
   * Label rubric used in the agent's system instruction. Describes the real {@code google/adk-java}
   * labels so the model classifies issues using labels that exist in the repo.
   */
  public static final String LABEL_GUIDELINES =
      """
      Label rubric and disambiguation rules (these are the labels that exist in
      the google/adk-java repository):
      - "bug": A reproducible defect, regression, or unexpected error in ADK
        Java behavior. Apply this to bug reports.
      - "enhancement": A new feature request or an improvement to existing
        functionality. Apply this to feature requests.
      - "documentation": Issues about docs, READMEs, Javadoc, tutorials, or the
        content of code samples.
      - "question": Usage questions or requests for clarification with no
        reproducible defect.
      - "testing": Test utilities, testing infrastructure, code coverage, or
        flaky/broken tests.
      - "sample": Issues about the sample apps under contrib/samples or the
        tutorials.
      - "dependencies": Dependency upgrades, version conflicts, or build-time
        dependency problems.
      - "github": GitHub Actions, workflows, or repository automation.

      Guidance:
      - Always classify the issue kind: apply "bug" for bug reports and
        "enhancement" for feature requests.
      - Additionally apply at most one topic label (documentation, question,
        testing, sample, dependencies, github) when one clearly applies.
      - Prefer the most specific match. If no label can be assigned
        confidently, do not call the labeling tool.
      """;

  private AdkTriagingAgent() {}

  /**
   * Parses a comma-separated list of GitHub handles (e.g. the {@code GTECH_ASSIGNEES} env var) into
   * a rotation, falling back to {@link #PLACEHOLDER_ROTATION} when {@code csv} is null, blank, or
   * yields no handles. Pure function (no env access) so it is directly unit-testable.
   */
  static ImmutableList<String> parseRotation(@Nullable String csv) {
    if (csv != null && !csv.isBlank()) {
      ImmutableList<String> parsed =
          Arrays.stream(csv.split(","))
              .map(String::trim)
              .filter(handle -> !handle.isEmpty())
              .collect(toImmutableList());
      if (!parsed.isEmpty()) {
        return parsed;
      }
    }
    return PLACEHOLDER_ROTATION;
  }

  /** Returns true when {@code rotation} is the placeholder (i.e. no real triagers configured). */
  static boolean isPlaceholderRotation(List<String> rotation) {
    return rotation.equals(PLACEHOLDER_ROTATION);
  }

  // ===========================================================================
  // Tool authority (prompt-injection guard)
  // ===========================================================================

  /**
   * Issue numbers this run is allowed to mutate. Seeded with the single configured issue in
   * single-issue workflow mode (see {@code AdkTriagingAgentRun}) and populated by {@link
   * #listUntriagedIssues} in batch mode. This binds the model-chosen {@code issue_number} to issues
   * the <em>workflow</em> selected, so a crafted (prompt-injected) issue title/body cannot steer
   * the agent into labeling or assigning an unrelated issue. Enforcement is active only in
   * unattended workflow mode; in interactive mode a human approves each mutation, so the set is not
   * consulted.
   */
  private static final Set<Integer> AUTHORIZED_ISSUES = ConcurrentHashMap.newKeySet();

  /** Records that {@code issueNumber} may be mutated by the labeling/assignment tools this run. */
  static void authorizeIssue(int issueNumber) {
    AUTHORIZED_ISSUES.add(issueNumber);
  }

  /** Clears the authorized-issue set. Exposed for unit tests. */
  static void clearAuthorizedIssues() {
    AUTHORIZED_ISSUES.clear();
  }

  /** Returns an immutable snapshot of the authorized-issue set. Exposed for unit tests. */
  static ImmutableSet<Integer> authorizedIssuesSnapshot() {
    return ImmutableSet.copyOf(AUTHORIZED_ISSUES);
  }

  /**
   * Returns true if {@code issueNumber} may be mutated: either enforcement is off (interactive
   * mode, where a human approves each action) or the issue is in {@code authorized}. Pure w.r.t.
   * its arguments so it is directly unit-testable.
   */
  static boolean isIssueAuthorized(int issueNumber, boolean enforce, Set<Integer> authorized) {
    return !enforce || authorized.contains(issueNumber);
  }

  /**
   * Returns an error envelope if the current run is not authorized to mutate {@code issueNumber},
   * or {@code null} when the mutation may proceed. Enforcement is on only in unattended workflow
   * mode ({@code INTERACTIVE=0}).
   */
  private static @Nullable ImmutableMap<String, Object> authorizationError(int issueNumber) {
    if (isIssueAuthorized(issueNumber, !Settings.isInteractive(), AUTHORIZED_ISSUES)) {
      return null;
    }
    return errorResponse(
        "Error: issue #"
            + issueNumber
            + " is not in the set of issues this run is authorized to modify. Only triage the issue"
            + " this workflow was triggered for, or issues surfaced by list_untriaged_issues.");
  }

  // ===========================================================================
  // Agent factory
  // ===========================================================================

  /**
   * Builds the {@link LlmAgent}. Safe to call at class-init time: it only reads {@link Settings}
   * accessors that never throw (no {@code GITHUB_TOKEN} is required to construct the agent), so the
   * {@link #ROOT_AGENT} field and {@code adk web} agent loaders work without a token configured.
   */
  public static LlmAgent rootAgent() {
    // When no real triager rotation is configured (GTECH_ASSIGNEES unset), owner assignment is
    // disabled: the assignment tool is withheld from the model and the instruction tells it not to
    // assign. This avoids a retry storm where the model repeatedly calls an assignment tool that
    // can only ever return the "no triagers configured" error, burning model/GitHub quota for no
    // benefit (and, in non-dry-run mode, hammering GitHub's API) on every run until GTECH_ASSIGNEES
    // is set.
    boolean ownerAssignmentEnabled = !isPlaceholderRotation(gtechRotation());

    String instruction =
        buildInstruction(
            Settings.repo(), Settings.owner(), Settings.isInteractive(), ownerAssignmentEnabled);

    return LlmAgent.builder()
        .name("adk_triaging_assistant")
        .description("Triage ADK Java issues.")
        .model(Settings.model())
        .instruction(instruction)
        .tools(buildTools(ownerAssignmentEnabled))
        .build();
  }

  /**
   * Builds the agent's tool list. The owner-assignment tool is included only when {@code
   * ownerAssignmentEnabled} is true; otherwise it is withheld so the model cannot get stuck
   * retrying a tool that can only return the "no triagers configured" error. Deterministic (only
   * reflection, no env/network access), so both branches are directly unit-testable.
   */
  static ImmutableList<FunctionTool> buildTools(boolean ownerAssignmentEnabled) {
    ImmutableList.Builder<FunctionTool> tools = ImmutableList.builder();
    tools.add(FunctionTool.create(AdkTriagingAgent.class, "listUntriagedIssues"));
    tools.add(FunctionTool.create(AdkTriagingAgent.class, "addLabelToIssue"));
    if (ownerAssignmentEnabled) {
      tools.add(FunctionTool.create(AdkTriagingAgent.class, "assignGtechOwnerToIssue"));
    }
    return tools.build();
  }

  /**
   * Builds the agent's system instruction. Pure (no env/network), so the conditional
   * owner-assignment wording is directly unit-testable. When {@code ownerAssignmentEnabled} is
   * false the instruction omits the assignment step and tells the model that owner assignment is
   * disabled, matching the tool withheld by {@link #buildTools}.
   */
  static String buildInstruction(
      String repo, String owner, boolean interactive, boolean ownerAssignmentEnabled) {
    String approvalInstruction =
        interactive
            ? "Only label them when the user approves the labeling!"
            : "Do not ask for user approval for labeling! If you can't find appropriate"
                + " labels for the issue, do not label it.";

    String ownerWorkflowSection =
        ownerAssignmentEnabled
            ? """
            2. **If `needs_owner` is true**:
               - Use `assign_gtech_owner_to_issue` to assign an owner.

            Do NOT add a component label if `needs_component_label` is false.
            Do NOT assign an owner if `needs_owner` is false.\
            """
            : """
            2. Owner assignment is DISABLED for this run because no triager rotation is configured
               (the GTECH_ASSIGNEES environment variable is unset). There is no owner-assignment
               tool available, so never attempt to assign an owner and ignore the `needs_owner`
               flag entirely.

            Do NOT add a component label if `needs_component_label` is false.\
            """;

    String ownerReportingNote =
        ownerAssignmentEnabled
            ? "Mention the assigned owner only when you actually assign one."
            : "Owner assignment is disabled, so state that no owner was assigned because no"
                + " triagers are configured.";

    return String.format(
        """
        You are a triaging bot for the GitHub %1$s repo with the owner %2$s. You will help get \
        issues, and recommend a label.
        IMPORTANT: %3$s

        %4$s

        ## Triaging Workflow

        Each issue will have flags indicating what actions are needed:
        - `needs_component_label`: true if the issue needs a component label
        - `needs_owner`: true if the issue needs an owner assigned

        For each issue, perform ONLY the required actions based on the flags:

        1. **If `needs_component_label` is true**:
           - Use `add_label_to_issue` to classify the issue kind:
             - Bug report -> "bug"
             - Feature request -> "enhancement"
           - Optionally call `add_label_to_issue` again to add at most one
             topic label (documentation, question, testing, sample,
             dependencies, github) when one clearly applies.

        %5$s

        Response quality requirements:
        - Summarize the issue in your own words without leaving template
          placeholders (never output text like "[fill in later]").
        - Justify the chosen label with a short explanation referencing the
          issue details.
        - %6$s
        - If no label is applied, clearly state why.

        Present the following in an easy to read format highlighting issue
        number and your label.
        - the issue summary in a few sentences
        - your label recommendation and justification
        - the owner, if you assign the issue to an owner
        """,
        repo,
        owner,
        approvalInstruction,
        LABEL_GUIDELINES,
        ownerWorkflowSection,
        ownerReportingNote);
  }

  /**
   * Exposed for {@code adk web} / dev-UI agent loaders that look up a {@code public static final
   * BaseAgent ROOT_AGENT} field on the class.
   */
  public static final LlmAgent ROOT_AGENT = rootAgent();

  // ===========================================================================
  // Tools
  // ===========================================================================

  /**
   * Lists open issues that still need triaging. An issue is considered untriaged if it is missing a
   * recognized label OR it has no assignee. Each returned entry is a compact map (number, title,
   * body, url, labels, plus the triage flags) rather than the full GitHub issue payload, to keep
   * the model's context small.
   */
  @Schema(
      name = "list_untriaged_issues",
      description =
          "List open issues that need triaging. Each issue carries flags "
              + "indicating which actions are still required.")
  public static ImmutableMap<String, Object> listUntriagedIssues(
      @Schema(name = "issue_count", description = "Maximum number of issues to return.")
          int issueCount) {
    Map<String, Object> response =
        GitHubTools.listOpenIssues(Settings.owner(), Settings.repo(), /* maxResults= */ 100);
    if (!"success".equals(response.get("status"))) {
      return errorResponse("Error: " + githubError(response));
    }

    ImmutableList<Map<String, Object>> issues =
        filterUntriagedIssues(asIssueList(response.get("issues")), issueCount);
    // Authorize exactly the issues we surface so the model can only label/assign these (and not an
    // unrelated issue id injected via a crafted title/body) when running unattended.
    for (Map<String, Object> issue : issues) {
      if (issue.get("number") instanceof Integer number) {
        authorizeIssue(number);
      }
    }
    return ImmutableMap.of("status", "success", "issues", issues);
  }

  /**
   * Pure triage-decision logic: filters the issues returned by {@link GitHubTools#listOpenIssues}
   * down to those that still need a label and/or an owner, annotating each with {@code
   * needs_component_label} / {@code needs_owner} flags. Extracted (and free of network/env access)
   * so it can be unit-tested with a hand-built list.
   */
  static ImmutableList<Map<String, Object>> filterUntriagedIssues(
      List<Map<String, Object>> items, int issueCount) {
    List<Map<String, Object>> untriaged = new ArrayList<>();
    if (items == null) {
      return ImmutableList.copyOf(untriaged);
    }
    for (Map<String, Object> issue : items) {
      Set<String> issueLabels = new HashSet<>(stringList(issue.get("labels")));
      boolean hasAssignee = !stringList(issue.get("assignees")).isEmpty();

      Set<String> existingComponentLabels = new HashSet<>(issueLabels);
      existingComponentLabels.retainAll(COMPONENT_LABELS);
      boolean hasComponent = !existingComponentLabels.isEmpty();
      boolean needsComponentLabel = !hasComponent;
      boolean needsOwner = !hasAssignee;

      if (!(needsComponentLabel || needsOwner)) {
        continue;
      }

      // Return only the fields the model needs, not the entire GitHub issue payload.
      Map<String, Object> issueMap = new LinkedHashMap<>();
      issueMap.put("number", asInt(issue.get("number")));
      issueMap.put("title", asString(issue.get("title")));
      issueMap.put("body", asString(issue.get("body")));
      issueMap.put("html_url", asString(issue.get("html_url")));
      issueMap.put("labels", ImmutableList.copyOf(issueLabels));
      issueMap.put("has_component_label", hasComponent);
      issueMap.put(
          "existing_component_label",
          hasComponent ? existingComponentLabels.iterator().next() : null);
      issueMap.put("needs_component_label", needsComponentLabel);
      issueMap.put("needs_owner", needsOwner);
      untriaged.add(issueMap);
      if (untriaged.size() >= issueCount) {
        break;
      }
    }
    return ImmutableList.copyOf(untriaged);
  }

  /** Adds the specified label to a GitHub issue, validating it is on the allowlist. */
  @Schema(
      name = "add_label_to_issue",
      description = "Add a label to a GitHub issue (must be one of the allowed labels).")
  public static ImmutableMap<String, Object> addLabelToIssue(
      @Schema(name = "issue_number", description = "Issue number to label.") int issueNumber,
      @Schema(name = "label", description = "Label to apply.") String label) {
    ImmutableMap<String, Object> authError = authorizationError(issueNumber);
    if (authError != null) {
      return authError;
    }
    return applyLabel(issueNumber, label, Settings.isDryRun());
  }

  /**
   * Returns the kind labels that must be removed before applying {@code label} to preserve the "at
   * most one kind label" rule: empty unless {@code label} is itself a kind label, in which case it
   * is every <em>other</em> kind label. Pure, so it is directly unit-testable.
   */
  static ImmutableSet<String> kindLabelsToRemoveBeforeApplying(String label) {
    if (!KIND_LABELS.contains(label)) {
      return ImmutableSet.of();
    }
    return KIND_LABELS.stream().filter(kind -> !kind.equals(label)).collect(toImmutableSet());
  }

  /**
   * Core label-application logic with the {@code dryRun} flag passed explicitly so the allowlist
   * guard and dry-run short-circuit can be unit-tested without environment variables or network
   * access. Only the final branch performs a real GitHub call (via {@link GitHubTools}).
   *
   * <p>GitHub's add-labels endpoint <em>appends</em> a label rather than replacing the set, so
   * before adding a kind label ({@code bug}/{@code enhancement}) any conflicting kind label is
   * removed first. This keeps overlapping runs or a re-classification from leaving an issue tagged
   * with both kinds.
   */
  static ImmutableMap<String, Object> applyLabel(int issueNumber, String label, boolean dryRun) {
    System.out.printf("Attempting to add label '%s' to issue #%d%n", label, issueNumber);
    if (!COMPONENT_LABELS.contains(label)) {
      return errorResponse("Error: Label '" + label + "' is not an allowed label. Will not apply.");
    }
    if (dryRun) {
      System.out.printf("[DRY_RUN] Would add label '%s' to issue #%d%n", label, issueNumber);
      return ImmutableMap.of("status", "success", "dry_run", true, "applied_label", label);
    }

    removeConflictingKindLabels(issueNumber, label);
    Map<String, Object> response =
        GitHubTools.addLabelToIssue(Settings.owner(), Settings.repo(), issueNumber, label);
    if (!"success".equals(response.get("status"))) {
      return errorResponse("Error: " + githubError(response));
    }
    return ImmutableMap.of("status", "success", "applied_label", label);
  }

  /**
   * Removes any kind label that conflicts with {@code label} from the issue (a no-op when {@code
   * label} is not a kind label). Each removal is best-effort: {@link
   * GitHubTools#removeLabelFromIssue} already treats a missing label as a no-op success, so a
   * conflicting label that is not present is simply skipped.
   */
  private static void removeConflictingKindLabels(int issueNumber, String label) {
    for (String conflicting : kindLabelsToRemoveBeforeApplying(label)) {
      Map<String, Object> response =
          GitHubTools.removeLabelFromIssue(
              Settings.owner(), Settings.repo(), issueNumber, conflicting);
      if ("success".equals(response.get("status"))) {
        System.out.printf(
            "Removed conflicting kind label '%s' from issue #%d before applying '%s'%n",
            conflicting, issueNumber, label);
      }
    }
  }

  /**
   * Round-robin assigns a gTech triager to the issue using {@code issue_number % N}. This matches
   * the Python implementation and keeps the assignment stable for a given issue number.
   */
  @Schema(
      name = "assign_gtech_owner_to_issue",
      description = "Round-robin assign a gTech owner to a GitHub issue.")
  public static ImmutableMap<String, Object> assignGtechOwnerToIssue(
      @Schema(name = "issue_number", description = "Issue number to assign.") int issueNumber) {
    ImmutableMap<String, Object> authError = authorizationError(issueNumber);
    if (authError != null) {
      return authError;
    }
    return assignOwner(issueNumber, gtechRotation(), Settings.isDryRun());
  }

  /**
   * Core owner-assignment logic with the {@code rotation} and {@code dryRun} flag passed explicitly
   * so the empty/placeholder guards, round-robin selection, and dry-run short-circuit can be
   * unit-tested without environment variables or network access. Only the final branch performs a
   * real GitHub call (via {@link GitHubTools}).
   */
  static ImmutableMap<String, Object> assignOwner(
      int issueNumber, List<String> rotation, boolean dryRun) {
    System.out.printf("Attempting to assign gTech owner to issue #%d%n", issueNumber);
    if (rotation.isEmpty()) {
      return errorResponse("Error: the triager rotation is empty; cannot assign.");
    }
    if (isPlaceholderRotation(rotation)) {
      return errorResponse(
          "Error: No real triagers are configured, so no owner was assigned. Set the"
              + " GTECH_ASSIGNEES environment variable (a comma-separated list of GitHub handles)"
              + " to enable owner assignment.");
    }
    String assignee = rotation.get(Math.floorMod(issueNumber, rotation.size()));
    if (dryRun) {
      System.out.printf("[DRY_RUN] Would assign issue #%d to '%s'%n", issueNumber, assignee);
      return ImmutableMap.of("status", "success", "dry_run", true, "assigned_owner", assignee);
    }
    Map<String, Object> response =
        GitHubTools.assignIssue(
            Settings.owner(), Settings.repo(), issueNumber, ImmutableList.of(assignee));
    if (!"success".equals(response.get("status"))) {
      return errorResponse("Error: " + githubError(response));
    }
    return ImmutableMap.of("status", "success", "assigned_owner", assignee);
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** The canonical error response envelope used by every tool in this sample. */
  static ImmutableMap<String, Object> errorResponse(String message) {
    return ImmutableMap.of("status", "error", "message", message);
  }

  /** Extracts a human-readable message from a {@link GitHubTools} error envelope. */
  private static String githubError(Map<String, Object> response) {
    Object message = response.get("error_message");
    return message == null ? "GitHub request failed." : String.valueOf(message);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> asIssueList(@Nullable Object value) {
    if (value instanceof List<?> list) {
      List<Map<String, Object>> result = new ArrayList<>();
      for (Object element : list) {
        if (element instanceof Map<?, ?> map) {
          result.add((Map<String, Object>) map);
        }
      }
      return result;
    }
    return ImmutableList.of();
  }

  private static List<String> stringList(@Nullable Object value) {
    if (value instanceof List<?> list) {
      List<String> result = new ArrayList<>();
      for (Object element : list) {
        if (element != null) {
          result.add(String.valueOf(element));
        }
      }
      return result;
    }
    return ImmutableList.of();
  }

  private static int asInt(@Nullable Object value) {
    return (value instanceof Number number) ? number.intValue() : 0;
  }

  private static String asString(@Nullable Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
