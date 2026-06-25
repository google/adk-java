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
package com.example.adkspam;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic (non-network, non-env) logic of {@link SpamDetectionAgent}: tool
 * wiring, the system instruction, the prompt-injection authorization guard, the alert-comment
 * builder, and the idempotency predicates.
 */
final class SpamDetectionAgentTest {

  // ---- Tool wiring ----

  @Test
  void buildTools_exposesOnlyTheFlagTool() {
    assertThat(SpamDetectionAgent.buildTools().stream().map(FunctionTool::name).toList())
        .containsExactly("flag_issue_as_spam");
  }

  @Test
  void rootAgent_hasSingleFlagTool() {
    ImmutableList<String> toolNames =
        SpamDetectionAgent.rootAgent().tools().blockingGet().stream()
            .map(BaseTool::name)
            .collect(ImmutableList.toImmutableList());
    assertThat(toolNames).containsExactly("flag_issue_as_spam");
  }

  // ---- System instruction ----

  @Test
  void buildInstruction_interactiveAsksForApproval() {
    String instruction =
        SpamDetectionAgent.buildInstruction("google", "adk-java", /* interactive= */ true);
    assertThat(instruction).contains("approve");
    assertThat(instruction).contains("google/adk-java");
  }

  @Test
  void buildInstruction_workflowDoesNotAskForApproval() {
    String instruction =
        SpamDetectionAgent.buildInstruction("google", "adk-java", /* interactive= */ false);
    assertThat(instruction).contains("Do not ask for approval");
  }

  @Test
  void buildInstruction_describesSpamCriteriaAndTool() {
    String instruction =
        SpamDetectionAgent.buildInstruction("google", "adk-java", /* interactive= */ false);
    assertThat(instruction).contains("SPAM");
    assertThat(instruction).contains("flag_issue_as_spam");
    assertThat(instruction).contains("UNTRUSTED");
  }

  // ---- Tool authority (prompt-injection guard) ----

  @Test
  void isIssueAuthorized_enforcementOffAllowsAnyIssue() {
    assertThat(SpamDetectionAgent.isIssueAuthorized(99, /* enforce= */ false, ImmutableSet.of()))
        .isTrue();
  }

  @Test
  void isIssueAuthorized_enforcementOnRestrictsToAuthorizedSet() {
    Set<Integer> authorized = ImmutableSet.of(7, 8);
    assertThat(SpamDetectionAgent.isIssueAuthorized(7, /* enforce= */ true, authorized)).isTrue();
    assertThat(SpamDetectionAgent.isIssueAuthorized(9, /* enforce= */ true, authorized)).isFalse();
  }

  @Test
  void authorizeIssue_recordsIssueAndClearResets() {
    SpamDetectionAgent.clearAuthorizedIssues();
    assertThat(SpamDetectionAgent.authorizedIssuesSnapshot()).isEmpty();

    SpamDetectionAgent.authorizeIssue(42);
    SpamDetectionAgent.authorizeIssue(43);
    assertThat(SpamDetectionAgent.authorizedIssuesSnapshot()).containsExactly(42, 43);

    SpamDetectionAgent.clearAuthorizedIssues();
    assertThat(SpamDetectionAgent.authorizedIssuesSnapshot()).isEmpty();
  }

  // ---- Alert comment body ----

  @Test
  void alertBody_includesSignatureAndReason() {
    String body = SpamDetectionAgent.alertBody("SIG", "spammy link to a shoe store");
    assertThat(body).startsWith("SIG");
    assertThat(body).contains("spammy link to a shoe store");
    assertThat(body).contains("@maintainers");
  }

  @Test
  void alertBody_neutralizesBacktickFencesInReason() {
    String body = SpamDetectionAgent.alertBody("SIG", "look ```rm -rf``` here");
    // The injected fence is replaced so it cannot break out of this comment's own code fence.
    assertThat(body).doesNotContain("```rm -rf```");
    assertThat(body).contains("'''rm -rf'''");
  }

  @Test
  void alertBody_nullReasonIsHandled() {
    String body = SpamDetectionAgent.alertBody("SIG", null);
    assertThat(body).startsWith("SIG");
  }

  // ---- Idempotency predicates ----

  @Test
  void hasSpamLabel_trueWhenLabelPresentCaseInsensitive() {
    ImmutableMap<String, Object> issue = ImmutableMap.of("labels", ImmutableList.of("bug", "SPAM"));
    assertThat(SpamDetectionAgent.hasSpamLabel(issue, "spam")).isTrue();
  }

  @Test
  void hasSpamLabel_falseWhenAbsentOrNotAMap() {
    ImmutableMap<String, Object> issue = ImmutableMap.of("labels", ImmutableList.of("bug"));
    assertThat(SpamDetectionAgent.hasSpamLabel(issue, "spam")).isFalse();
    assertThat(SpamDetectionAgent.hasSpamLabel(null, "spam")).isFalse();
    assertThat(SpamDetectionAgent.hasSpamLabel("not-a-map", "spam")).isFalse();
  }

  @Test
  void hasSignatureComment_trueWhenAnyCommentContainsSignature() {
    ImmutableList<ImmutableMap<String, Object>> comments =
        ImmutableList.of(
            ImmutableMap.of("author", "a", "body", "hello"),
            ImmutableMap.of("author", "bot", "body", "SIG something detected"));
    assertThat(SpamDetectionAgent.hasSignatureComment(comments, "SIG")).isTrue();
  }

  @Test
  void hasSignatureComment_falseWhenNoneMatchOrNotAList() {
    ImmutableList<ImmutableMap<String, Object>> comments =
        ImmutableList.of(ImmutableMap.of("author", "a", "body", "hello"));
    assertThat(SpamDetectionAgent.hasSignatureComment(comments, "SIG")).isFalse();
    assertThat(SpamDetectionAgent.hasSignatureComment(null, "SIG")).isFalse();
  }
}
