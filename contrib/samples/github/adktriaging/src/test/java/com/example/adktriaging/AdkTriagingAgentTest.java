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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic (non-network, non-env) logic of {@link AdkTriagingAgent}: label
 * allowlist, rotation parsing, the dry-run/placeholder guards, and the triage-decision filter.
 */
final class AdkTriagingAgentTest {

  // ---- Label allowlist ----

  @Test
  void componentLabels_areRealAdkJavaLabels() {
    assertThat(AdkTriagingAgent.COMPONENT_LABELS)
        .containsAtLeast("bug", "enhancement", "documentation", "question");
    // adk-python-only labels must not be present.
    assertThat(AdkTriagingAgent.COMPONENT_LABELS).doesNotContain("core");
    assertThat(AdkTriagingAgent.COMPONENT_LABELS).doesNotContain("agent engine");
  }

  @Test
  void labelGuidelines_mentionKindLabels() {
    assertThat(AdkTriagingAgent.LABEL_GUIDELINES).contains("bug");
    assertThat(AdkTriagingAgent.LABEL_GUIDELINES).contains("enhancement");
  }

  // ---- Rotation parsing ----

  @Test
  void parseRotation_splitsAndTrims() {
    assertThat(AdkTriagingAgent.parseRotation("alice, bob ,carol"))
        .containsExactly("alice", "bob", "carol")
        .inOrder();
  }

  @Test
  void parseRotation_nullOrBlankYieldsPlaceholder() {
    assertThat(AdkTriagingAgent.isPlaceholderRotation(AdkTriagingAgent.parseRotation(null)))
        .isTrue();
    assertThat(AdkTriagingAgent.isPlaceholderRotation(AdkTriagingAgent.parseRotation("   ")))
        .isTrue();
    assertThat(AdkTriagingAgent.isPlaceholderRotation(AdkTriagingAgent.parseRotation(",,")))
        .isTrue();
  }

  @Test
  void isPlaceholderRotation_falseForRealRotation() {
    assertThat(AdkTriagingAgent.isPlaceholderRotation(ImmutableList.of("alice", "bob"))).isFalse();
  }

  @Test
  void gtechRotation_defaultsToPlaceholderWhenUnset() {
    // GTECH_ASSIGNEES is not set in the unit-test environment, so the lazy accessor falls back to
    // the placeholder rotation (and never reads env at class-load time).
    assertThat(AdkTriagingAgent.isPlaceholderRotation(AdkTriagingAgent.gtechRotation())).isTrue();
  }

  // ---- Owner-assignment hardening when GTECH_ASSIGNEES is missing ----

  @Test
  void buildTools_includesAssignToolWhenOwnerAssignmentEnabled() {
    assertThat(
            AdkTriagingAgent.buildTools(/* ownerAssignmentEnabled= */ true).stream()
                .map(FunctionTool::name)
                .toList())
        .containsExactly(
            "list_untriaged_issues", "add_label_to_issue", "assign_gtech_owner_to_issue")
        .inOrder();
  }

  @Test
  void buildTools_withholdsAssignToolWhenOwnerAssignmentDisabled() {
    // With no real triagers configured, the assignment tool must not be exposed to the model so it
    // cannot loop on a tool that can only ever return the "no triagers configured" error.
    assertThat(
            AdkTriagingAgent.buildTools(/* ownerAssignmentEnabled= */ false).stream()
                .map(FunctionTool::name)
                .toList())
        .containsExactly("list_untriaged_issues", "add_label_to_issue")
        .inOrder();
  }

  @Test
  void buildInstruction_enabledMentionsAssignmentTool() {
    String instruction =
        AdkTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ false, /* ownerAssignmentEnabled= */ true);
    assertThat(instruction).contains("assign_gtech_owner_to_issue");
    assertThat(instruction).doesNotContain("DISABLED");
  }

  @Test
  void buildInstruction_disabledOmitsAssignmentToolAndAnnouncesDisabled() {
    String instruction =
        AdkTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ false, /* ownerAssignmentEnabled= */ false);
    assertThat(instruction).doesNotContain("assign_gtech_owner_to_issue");
    assertThat(instruction).contains("Owner assignment is DISABLED");
    assertThat(instruction).contains("GTECH_ASSIGNEES");
  }

  @Test
  void rootAgent_withholdsAssignToolWhenGtechAssigneesUnset() {
    // GTECH_ASSIGNEES is unset in the unit-test environment, so the real env-driven default must
    // withhold the assignment tool (the exact scenario the shipped workflow runs with by default).
    ImmutableList<String> toolNames =
        AdkTriagingAgent.rootAgent().tools().blockingGet().stream()
            .map(BaseTool::name)
            .collect(ImmutableList.toImmutableList());
    assertThat(toolNames).containsExactly("list_untriaged_issues", "add_label_to_issue");
  }

  // ---- Tool authority (prompt-injection guard) ----

  @Test
  void isIssueAuthorized_enforcementOffAllowsAnyIssue() {
    assertThat(AdkTriagingAgent.isIssueAuthorized(99, /* enforce= */ false, ImmutableSet.of()))
        .isTrue();
  }

  @Test
  void isIssueAuthorized_enforcementOnRestrictsToAuthorizedSet() {
    Set<Integer> authorized = ImmutableSet.of(7, 8);
    assertThat(AdkTriagingAgent.isIssueAuthorized(7, /* enforce= */ true, authorized)).isTrue();
    assertThat(AdkTriagingAgent.isIssueAuthorized(9, /* enforce= */ true, authorized)).isFalse();
  }

  @Test
  void authorizeIssue_recordsIssueAndClearResets() {
    AdkTriagingAgent.clearAuthorizedIssues();
    assertThat(AdkTriagingAgent.authorizedIssuesSnapshot()).isEmpty();

    AdkTriagingAgent.authorizeIssue(42);
    AdkTriagingAgent.authorizeIssue(43);
    assertThat(AdkTriagingAgent.authorizedIssuesSnapshot()).containsExactly(42, 43);

    AdkTriagingAgent.clearAuthorizedIssues();
    assertThat(AdkTriagingAgent.authorizedIssuesSnapshot()).isEmpty();
  }

  // ---- Kind-label idempotency ----

  @Test
  void kindLabelsToRemoveBeforeApplying_kindLabelReturnsTheOtherKind() {
    assertThat(AdkTriagingAgent.kindLabelsToRemoveBeforeApplying("bug"))
        .containsExactly("enhancement");
    assertThat(AdkTriagingAgent.kindLabelsToRemoveBeforeApplying("enhancement"))
        .containsExactly("bug");
  }

  @Test
  void kindLabelsToRemoveBeforeApplying_nonKindLabelReturnsEmpty() {
    assertThat(AdkTriagingAgent.kindLabelsToRemoveBeforeApplying("documentation")).isEmpty();
    assertThat(AdkTriagingAgent.kindLabelsToRemoveBeforeApplying("not-a-label")).isEmpty();
  }

  // ---- applyLabel ----

  @Test
  void applyLabel_rejectsUnknownLabel() {
    Map<String, Object> result = AdkTriagingAgent.applyLabel(1, "core", /* dryRun= */ false);
    assertThat(result).containsEntry("status", "error");
    assertThat((String) result.get("message")).contains("not an allowed label");
  }

  @Test
  void applyLabel_dryRunDoesNotCallNetwork() {
    Map<String, Object> result = AdkTriagingAgent.applyLabel(1, "bug", /* dryRun= */ true);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("dry_run", true);
    assertThat(result).containsEntry("applied_label", "bug");
  }

  // ---- assignOwner ----

  @Test
  void assignOwner_placeholderRotationReturnsError() {
    List<String> placeholder = AdkTriagingAgent.parseRotation(null);
    Map<String, Object> result = AdkTriagingAgent.assignOwner(1, placeholder, /* dryRun= */ false);
    assertThat(result).containsEntry("status", "error");
    assertThat((String) result.get("message")).contains("GTECH_ASSIGNEES");
  }

  @Test
  void assignOwner_emptyRotationReturnsError() {
    Map<String, Object> result =
        AdkTriagingAgent.assignOwner(1, ImmutableList.of(), /* dryRun= */ false);
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  void assignOwner_dryRunRoundRobinIsStable() {
    List<String> rotation = ImmutableList.of("a", "b", "c");
    // issue_number % 3 selects the assignee deterministically.
    assertThat(AdkTriagingAgent.assignOwner(3, rotation, true).get("assigned_owner"))
        .isEqualTo("a");
    assertThat(AdkTriagingAgent.assignOwner(4, rotation, true).get("assigned_owner"))
        .isEqualTo("b");
    assertThat(AdkTriagingAgent.assignOwner(5, rotation, true).get("assigned_owner"))
        .isEqualTo("c");
    Map<String, Object> result = AdkTriagingAgent.assignOwner(5, rotation, true);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("dry_run", true);
  }

  // ---- filterUntriagedIssues ----

  @Test
  void filterUntriagedIssues_flagsMissingLabelAndOwner() {
    List<Map<String, Object>> items =
        ImmutableList.of(
            issue(1, ImmutableList.of(), ImmutableList.of()),
            issue(2, ImmutableList.of("bug"), ImmutableList.of("x")),
            issue(3, ImmutableList.of("bug"), ImmutableList.of()),
            issue(4, ImmutableList.of(), ImmutableList.of("y")));

    List<Map<String, Object>> result = AdkTriagingAgent.filterUntriagedIssues(items, 100);

    // Issue #2 is fully triaged (has a recognized label + assignee) -> excluded.
    assertThat(result).hasSize(3);

    Map<String, Object> issue1 = byNumber(result, 1);
    assertThat(issue1).containsEntry("needs_component_label", true);
    assertThat(issue1).containsEntry("needs_owner", true);

    Map<String, Object> issue3 = byNumber(result, 3);
    assertThat(issue3).containsEntry("needs_component_label", false);
    assertThat(issue3).containsEntry("needs_owner", true);
    assertThat(issue3).containsEntry("existing_component_label", "bug");

    Map<String, Object> issue4 = byNumber(result, 4);
    assertThat(issue4).containsEntry("needs_component_label", true);
    assertThat(issue4).containsEntry("needs_owner", false);
  }

  @Test
  void filterUntriagedIssues_respectsLimit() {
    List<Map<String, Object>> items =
        ImmutableList.of(
            issue(1, ImmutableList.of(), ImmutableList.of()),
            issue(2, ImmutableList.of(), ImmutableList.of()),
            issue(3, ImmutableList.of(), ImmutableList.of()));
    assertThat(AdkTriagingAgent.filterUntriagedIssues(items, 2)).hasSize(2);
  }

  @Test
  void filterUntriagedIssues_nullItemsIsEmpty() {
    assertThat(AdkTriagingAgent.filterUntriagedIssues(null, 5)).isEmpty();
  }

  @Test
  void filterUntriagedIssues_returnsCompactPayload() {
    Map<String, Object> raw = new java.util.LinkedHashMap<>();
    raw.put("number", 7);
    raw.put("title", "Title");
    raw.put("body", "Body");
    raw.put("html_url", "https://github.com/google/adk-java/issues/7");
    raw.put("labels", ImmutableList.of("question"));
    raw.put("assignees", ImmutableList.of());

    Map<String, Object> issue =
        AdkTriagingAgent.filterUntriagedIssues(ImmutableList.of(raw), 100).get(0);

    // Keeps exactly the fields the model needs...
    assertThat(issue.keySet())
        .containsExactly(
            "number",
            "title",
            "body",
            "html_url",
            "labels",
            "has_component_label",
            "existing_component_label",
            "needs_component_label",
            "needs_owner");
    assertThat(issue).containsEntry("labels", ImmutableList.of("question"));
    // "question" is a recognized component label, so only an owner is still needed.
    assertThat(issue).containsEntry("needs_component_label", false);
    assertThat(issue).containsEntry("needs_owner", true);
  }

  private static Map<String, Object> issue(
      int number, List<String> labels, List<String> assignees) {
    return ImmutableMap.of(
        "number",
        number,
        "title",
        "Issue " + number,
        "body",
        "",
        "html_url",
        "https://github.com/google/adk-java/issues/" + number,
        "labels",
        labels,
        "assignees",
        assignees);
  }

  private static Map<String, Object> byNumber(List<Map<String, Object>> issues, int number) {
    return issues.stream()
        .filter(issue -> ((Number) issue.get("number")).intValue() == number)
        .findFirst()
        .orElseThrow(() -> new AssertionError("Issue #" + number + " not found in result"));
  }
}
