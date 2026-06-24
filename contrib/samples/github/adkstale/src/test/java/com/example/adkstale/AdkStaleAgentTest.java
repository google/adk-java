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
package com.example.adkstale;

import static com.google.common.truth.Truth.assertThat;

import com.example.adkstale.AdkStaleAgent.State;
import com.example.adkstale.AdkStaleAgent.Timeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic (non-network, non-env) logic of {@link AdkStaleAgent}: the
 * GraphQL history reconstruction, the audit-state computation, the tool list/instruction, the label
 * allowlist, the authorization guard, and the dry-run short-circuits.
 */
final class AdkStaleAgentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant NOW = Instant.parse("2026-06-23T00:00:00Z");
  private static final String STALE = "stale";

  // ---- Helpers / pure functions ----

  @Test
  void isHuman_ignoresBotsAndBlanks() {
    assertThat(AdkStaleAgent.isHuman("alice", AdkStaleAgent.BOT_NAME)).isTrue();
    assertThat(AdkStaleAgent.isHuman(AdkStaleAgent.BOT_NAME, AdkStaleAgent.BOT_NAME)).isFalse();
    assertThat(AdkStaleAgent.isHuman("github-actions[bot]", AdkStaleAgent.BOT_NAME)).isFalse();
    assertThat(AdkStaleAgent.isHuman(null, AdkStaleAgent.BOT_NAME)).isFalse();
    assertThat(AdkStaleAgent.isHuman("", AdkStaleAgent.BOT_NAME)).isFalse();
  }

  @Test
  void formatDays_rendersWholeAndFractionalDays() {
    assertThat(AdkStaleAgent.formatDays(168.0)).isEqualTo("7");
    assertThat(AdkStaleAgent.formatDays(12.0)).isEqualTo("0.5");
    assertThat(AdkStaleAgent.formatDays(84.0)).isEqualTo("3.5");
  }

  @Test
  void parseHandles_splitsTrimsAndDropsBlanks() {
    assertThat(AdkStaleAgent.parseHandles("alice, bob ,, carol "))
        .containsExactly("alice", "bob", "carol")
        .inOrder();
    assertThat(AdkStaleAgent.parseHandles(null)).isEmpty();
    assertThat(AdkStaleAgent.parseHandles("   ")).isEmpty();
  }

  @Test
  void allowedLabels_areStaleAndRequestClarification() {
    // Env vars are unset in the test environment, so the defaults apply.
    assertThat(AdkStaleAgent.allowedLabels()).containsExactly("stale", "request clarification");
  }

  // ---- Tool list + instruction ----

  @Test
  void buildTools_exposesTheSixToolsInOrder() {
    assertThat(AdkStaleAgent.buildTools().stream().map(FunctionTool::name).toList())
        .containsExactly(
            "get_issue_state",
            "add_label_to_issue",
            "remove_label_from_issue",
            "add_stale_label_and_comment",
            "alert_maintainer_of_edit",
            "close_as_stale")
        .inOrder();
  }

  @Test
  void buildInstruction_substitutesPlaceholdersAndKeepsDecisionTree() {
    String instruction =
        AdkStaleAgent.buildInstruction(
            "adk-java", "google", "stale", "request clarification", "7", "7", false);
    assertThat(instruction).contains("google/adk-java");
    assertThat(instruction).contains("Stale Threshold: 7 days.");
    assertThat(instruction).contains("Close Threshold: 7 days.");
    assertThat(instruction).contains("get_issue_state");
    assertThat(instruction).contains("add_stale_label_and_comment");
    assertThat(instruction).contains("DECISION TREE");
    // No unsubstituted placeholders remain.
    assertThat(instruction).doesNotContain("{OWNER}");
    assertThat(instruction).doesNotContain("{REPO}");
    assertThat(instruction).doesNotContain("{stale_threshold_days}");
    assertThat(instruction).doesNotContain("{STALE_LABEL_NAME}");
  }

  @Test
  void buildInstruction_interactiveAddsApprovalClause() {
    String interactive =
        AdkStaleAgent.buildInstruction(
            "adk-java", "google", "stale", "request clarification", "7", "7", true);
    assertThat(interactive).contains("Approval (interactive mode)");
    assertThat(interactive).contains("ask the user to confirm");

    String unattended =
        AdkStaleAgent.buildInstruction(
            "adk-java", "google", "stale", "request clarification", "7", "7", false);
    assertThat(unattended).doesNotContain("Approval (interactive mode)");
  }

  // ---- Authorization (prompt-injection guard) ----

  @Test
  void isIssueAuthorized_enforcementOffAllowsAnyIssue() {
    assertThat(AdkStaleAgent.isIssueAuthorized(99, /* enforce= */ false, ImmutableSet.of()))
        .isTrue();
  }

  @Test
  void isIssueAuthorized_enforcementOnRestrictsToAuthorizedSet() {
    var authorized = ImmutableSet.of(7, 8);
    assertThat(AdkStaleAgent.isIssueAuthorized(7, /* enforce= */ true, authorized)).isTrue();
    assertThat(AdkStaleAgent.isIssueAuthorized(9, /* enforce= */ true, authorized)).isFalse();
  }

  @Test
  void authorizeIssue_recordsIssueAndClearResets() {
    AdkStaleAgent.clearAuthorizedIssues();
    assertThat(AdkStaleAgent.authorizedIssuesSnapshot()).isEmpty();

    AdkStaleAgent.authorizeIssue(42);
    AdkStaleAgent.authorizeIssue(43);
    assertThat(AdkStaleAgent.authorizedIssuesSnapshot()).containsExactly(42, 43);

    AdkStaleAgent.clearAuthorizedIssues();
    assertThat(AdkStaleAgent.authorizedIssuesSnapshot()).isEmpty();
  }

  // ---- needsMaintainerAlert ----

  @Test
  void needsMaintainerAlert_trueForUnalertedSilentEdit() {
    Instant editTime = Instant.parse("2026-06-20T00:00:00Z");
    assertThat(AdkStaleAgent.needsMaintainerAlert("author", "edited_description", editTime, null))
        .isTrue();
    assertThat(
            AdkStaleAgent.needsMaintainerAlert("other_user", "edited_description", editTime, null))
        .isTrue();
  }

  @Test
  void needsMaintainerAlert_falseWhenBotAlreadyAlerted() {
    Instant editTime = Instant.parse("2026-06-20T00:00:00Z");
    Instant alertTime = Instant.parse("2026-06-21T00:00:00Z");
    assertThat(
            AdkStaleAgent.needsMaintainerAlert("author", "edited_description", editTime, alertTime))
        .isFalse();
  }

  @Test
  void needsMaintainerAlert_falseForNonEditOrMaintainer() {
    Instant t = Instant.parse("2026-06-20T00:00:00Z");
    assertThat(AdkStaleAgent.needsMaintainerAlert("author", "commented", t, null)).isFalse();
    assertThat(AdkStaleAgent.needsMaintainerAlert("maintainer", "edited_description", t, null))
        .isFalse();
  }

  // ---- applyLabel / removeLabel ----

  @Test
  void applyLabel_rejectsLabelOutsideAllowlist() {
    Map<String, Object> result = AdkStaleAgent.applyLabel(1, "bug", /* dryRun= */ false);
    assertThat(result).containsEntry("status", "error");
    assertThat((String) result.get("message")).contains("not an allowed label");
  }

  @Test
  void applyLabel_dryRunDoesNotCallNetwork() {
    Map<String, Object> result = AdkStaleAgent.applyLabel(1, "stale", /* dryRun= */ true);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("dry_run", true);
    assertThat(result).containsEntry("applied_label", "stale");
  }

  @Test
  void removeLabel_dryRunDoesNotCallNetwork() {
    Map<String, Object> result = AdkStaleAgent.removeLabel(1, "stale", /* dryRun= */ true);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("dry_run", true);
    assertThat(result).containsEntry("removed_label", "stale");
  }

  @Test
  void markStale_alertEdit_closeStale_dryRunDoNotCallNetwork() {
    assertThat(AdkStaleAgent.markStale(1, /* dryRun= */ true))
        .containsEntry("action", "mark_stale");
    assertThat(AdkStaleAgent.alertEdit(1, /* dryRun= */ true))
        .containsEntry("action", "alert_maintainer_of_edit");
    assertThat(AdkStaleAgent.closeStale(1, /* dryRun= */ true))
        .containsEntry("action", "close_as_stale");
  }

  // ---- History reconstruction + computeIssueState ----

  @Test
  void computeIssueState_authorCommentedLast_isActive() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of());
    addComment(issue, "alice", "Any update on this?", "2026-06-20T00:00:00Z", null);

    Map<String, Object> state = compute(issue, List.of("bob"));

    assertThat(state).containsEntry("status", "success");
    assertThat(state).containsEntry("last_action_role", "author");
    assertThat(state).containsEntry("last_action_type", "commented");
    assertThat(state).containsEntry("last_actor_name", "alice");
    assertThat(state).containsEntry("last_comment_text", "Any update on this?");
    assertThat(state).containsEntry("is_stale", false);
    assertThat(state).containsEntry("maintainer_alert_needed", false);
    assertThat(asDouble(state, "days_since_activity")).isWithin(0.01).of(3.0);
  }

  @Test
  void computeIssueState_maintainerAskedQuestionLast_surfacesComment() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of());
    addComment(issue, "alice", "I hit a bug.", "2026-06-02T00:00:00Z", null);
    addComment(issue, "bob", "Can you provide logs?", "2026-06-10T00:00:00Z", null);

    Map<String, Object> state = compute(issue, List.of("bob"));

    assertThat(state).containsEntry("last_action_role", "maintainer");
    assertThat(state).containsEntry("last_actor_name", "bob");
    assertThat(state).containsEntry("last_comment_text", "Can you provide logs?");
    assertThat(state).containsEntry("is_stale", false);
    assertThat(asDouble(state, "days_since_activity")).isWithin(0.01).of(13.0);
  }

  @Test
  void computeIssueState_staleLabel_computesDaysSinceLabel() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of("stale"));
    addComment(issue, "bob", "Please clarify the repro.", "2026-06-10T00:00:00Z", null);
    addLabeledEvent(issue, "stale", "2026-06-18T00:00:00Z", "bob");

    Map<String, Object> state = compute(issue, List.of("bob"));

    assertThat(state).containsEntry("is_stale", true);
    assertThat(state).containsEntry("last_action_role", "maintainer");
    assertThat(asDouble(state, "days_since_stale_label")).isWithin(0.01).of(5.0);
    assertThat((List<?>) state.get("current_labels")).containsExactly("stale");
  }

  @Test
  void computeIssueState_silentDescriptionEditLast_needsAlert() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of());
    addComment(issue, "bob", "Please share a minimal repro.", "2026-06-05T00:00:00Z", null);
    addEdit(issue, "alice", "2026-06-20T00:00:00Z");

    Map<String, Object> state = compute(issue, List.of("bob"));

    assertThat(state).containsEntry("last_action_role", "author");
    assertThat(state).containsEntry("last_action_type", "edited_description");
    assertThat(state).containsEntry("maintainer_alert_needed", true);
    // Non-comment last action -> no comment text retained.
    assertThat(state.get("last_comment_text")).isNull();
  }

  @Test
  void computeIssueState_silentEditAlreadyAlerted_doesNotReAlert() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of());
    addComment(issue, "bob", "Please share a minimal repro.", "2026-06-05T00:00:00Z", null);
    addEdit(issue, "alice", "2026-06-20T00:00:00Z");
    // The bot already posted a silent-edit alert AFTER the edit.
    addComment(
        issue,
        AdkStaleAgent.BOT_NAME,
        AdkStaleAgent.BOT_ALERT_SIGNATURE + ". Maintainers, please review.",
        "2026-06-21T00:00:00Z",
        null);

    Map<String, Object> state = compute(issue, List.of("bob"));

    assertThat(state).containsEntry("last_action_type", "edited_description");
    assertThat(state).containsEntry("maintainer_alert_needed", false);
  }

  @Test
  void computeIssueState_ignoresBotActivity() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of());
    addComment(issue, "bob", "Need more info.", "2026-06-05T00:00:00Z", null);
    addComment(issue, "github-actions[bot]", "CI passed.", "2026-06-22T00:00:00Z", null);

    Map<String, Object> state = compute(issue, List.of("bob"));

    // The bot comment is ignored, so the maintainer remains the last actor.
    assertThat(state).containsEntry("last_action_role", "maintainer");
    assertThat(state).containsEntry("last_actor_name", "bob");
    assertThat(asDouble(state, "days_since_activity")).isWithin(0.01).of(18.0);
  }

  @Test
  void buildTimeline_usesEditTimeForEditedComments_andSortsChronologically() {
    ObjectNode issue = issueNode("alice", "2026-06-01T00:00:00Z", List.of());
    // Comment created early but edited late -> should sort by the edit time.
    addComment(issue, "alice", "edited later", "2026-06-02T00:00:00Z", "2026-06-19T00:00:00Z");
    addComment(issue, "bob", "in the middle", "2026-06-10T00:00:00Z", null);

    Timeline timeline =
        AdkStaleAgent.buildTimeline(
            issue, STALE, AdkStaleAgent.BOT_NAME, AdkStaleAgent.BOT_ALERT_SIGNATURE);

    // created (06-01), bob comment (06-10), alice edited comment (06-19).
    assertThat(timeline.history).hasSize(3);
    assertThat(timeline.history.get(2).actor).isEqualTo("alice");
    assertThat(timeline.history.get(2).type).isEqualTo("commented");

    State state = AdkStaleAgent.replay(timeline.history, List.of("bob"), timeline.issueAuthor);
    assertThat(state.lastActorName).isEqualTo("alice");
    assertThat(state.lastActivityTime).isEqualTo(Instant.parse("2026-06-19T00:00:00Z"));
  }

  // ---- Test JSON builders (shape mirrors the GraphQL `issue` node) ----

  private static Map<String, Object> compute(JsonNode issue, List<String> maintainers) {
    return AdkStaleAgent.computeIssueState(
        issue,
        maintainers,
        NOW,
        /* staleHours= */ 168.0,
        /* closeHours= */ 168.0,
        STALE,
        AdkStaleAgent.BOT_NAME,
        AdkStaleAgent.BOT_ALERT_SIGNATURE);
  }

  private static ObjectNode issueNode(String author, String createdAt, List<String> labels) {
    ObjectNode issue = MAPPER.createObjectNode();
    issue.putObject("author").put("login", author);
    issue.put("createdAt", createdAt);
    ArrayNode labelNodes = issue.putObject("labels").putArray("nodes");
    for (String label : labels) {
      labelNodes.addObject().put("name", label);
    }
    issue.putObject("comments").putArray("nodes");
    issue.putObject("userContentEdits").putArray("nodes");
    issue.putObject("timelineItems").putArray("nodes");
    return issue;
  }

  private static void addComment(
      ObjectNode issue,
      String author,
      String body,
      String createdAt,
      @Nullable String lastEditedAt) {
    ObjectNode comment = ((ArrayNode) issue.path("comments").path("nodes")).addObject();
    comment.putObject("author").put("login", author);
    comment.put("body", body);
    comment.put("createdAt", createdAt);
    if (lastEditedAt == null) {
      comment.putNull("lastEditedAt");
    } else {
      comment.put("lastEditedAt", lastEditedAt);
    }
  }

  private static void addEdit(ObjectNode issue, String editor, String editedAt) {
    ObjectNode edit = ((ArrayNode) issue.path("userContentEdits").path("nodes")).addObject();
    edit.putObject("editor").put("login", editor);
    edit.put("editedAt", editedAt);
  }

  private static void addLabeledEvent(
      ObjectNode issue, String label, String createdAt, String actor) {
    ObjectNode event = ((ArrayNode) issue.path("timelineItems").path("nodes")).addObject();
    event.put("__typename", "LabeledEvent");
    event.put("createdAt", createdAt);
    event.putObject("actor").put("login", actor);
    event.putObject("label").put("name", label);
  }

  private static double asDouble(Map<String, Object> state, String key) {
    return ((Number) state.get(key)).doubleValue();
  }

  @Test
  void rootAgent_buildsWithoutTokenOrNetwork() {
    // ROOT_AGENT is initialized at class load; confirm it exposes the expected tool set.
    ImmutableList<String> toolNames =
        AdkStaleAgent.ROOT_AGENT.tools().blockingGet().stream()
            .map(com.google.adk.tools.BaseTool::name)
            .collect(ImmutableList.toImmutableList());
    assertThat(toolNames)
        .containsExactly(
            "get_issue_state",
            "add_label_to_issue",
            "remove_label_from_issue",
            "add_stale_label_and_comment",
            "alert_maintainer_of_edit",
            "close_as_stale");
  }
}
