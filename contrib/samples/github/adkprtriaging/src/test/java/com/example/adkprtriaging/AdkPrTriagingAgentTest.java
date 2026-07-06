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
package com.example.adkprtriaging;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the deterministic (non-network, non-env) logic of {@link AdkPrTriagingAgent}:
 * label allowlist, tool wiring, system-instruction construction, the prompt-injection authorization
 * guard, and the dry-run label/comment short-circuits.
 */
final class AdkPrTriagingAgentTest {

  // ---- Label allowlist ----

  @Test
  void allowedLabels_areRealAdkJavaLabels() {
    assertThat(AdkPrTriagingAgent.ALLOWED_LABELS)
        .containsAtLeast("bug", "enhancement", "documentation");
    // adk-python-only component labels must not be present.
    assertThat(AdkPrTriagingAgent.ALLOWED_LABELS).doesNotContain("core");
    assertThat(AdkPrTriagingAgent.ALLOWED_LABELS).doesNotContain("services");
    assertThat(AdkPrTriagingAgent.ALLOWED_LABELS).doesNotContain("models");
  }

  @Test
  void labelGuidelines_mentionKeyLabels() {
    assertThat(AdkPrTriagingAgent.LABEL_GUIDELINES).contains("bug");
    assertThat(AdkPrTriagingAgent.LABEL_GUIDELINES).contains("enhancement");
    assertThat(AdkPrTriagingAgent.LABEL_GUIDELINES).contains("documentation");
  }

  // ---- Tool wiring ----

  @Test
  void buildTools_exposesTheThreePrTools() {
    assertThat(AdkPrTriagingAgent.buildTools().stream().map(FunctionTool::name).toList())
        .containsExactly("get_pull_request_details", "add_label_to_pr", "add_comment_to_pr")
        .inOrder();
  }

  @Test
  void rootAgent_exposesTheThreePrTools() {
    ImmutableList<String> toolNames =
        AdkPrTriagingAgent.rootAgent().tools().blockingGet().stream()
            .map(BaseTool::name)
            .collect(ImmutableList.toImmutableList());
    assertThat(toolNames)
        .containsExactly("get_pull_request_details", "add_label_to_pr", "add_comment_to_pr");
  }

  // ---- System instruction ----

  @Test
  void buildInstruction_interactiveAsksForApproval() {
    String instruction =
        AdkPrTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ true, /* contributing= */ "");
    assertThat(instruction).contains("Only label or comment when the user approves");
  }

  @Test
  void buildInstruction_workflowDoesNotAskForApproval() {
    String instruction =
        AdkPrTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ false, /* contributing= */ "");
    assertThat(instruction).contains("Do not ask for user approval");
  }

  @Test
  void buildInstruction_mentionsRepoOwnerLabelsAndSignature() {
    String instruction =
        AdkPrTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ false, /* contributing= */ "");
    assertThat(instruction).contains("google/adk-java");
    assertThat(instruction).contains("enhancement");
    assertThat(instruction).contains(AdkPrTriagingAgent.AGENT_COMMENT_SIGNATURE);
  }

  @Test
  void buildInstruction_embedsContributingWhenProvided() {
    String instruction =
        AdkPrTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ false, "SIGN THE CLA AND USE ONE COMMIT");
    assertThat(instruction).contains("SIGN THE CLA AND USE ONE COMMIT");
  }

  @Test
  void buildInstruction_toleratesMissingContributing() {
    String instruction =
        AdkPrTriagingAgent.buildInstruction(
            "adk-java", "google", /* interactive= */ false, /* contributing= */ null);
    assertThat(instruction).contains("CONTRIBUTING.md was not available");
  }

  // ---- Tool authority (prompt-injection guard) ----

  @Test
  void isPrAuthorized_enforcementOffAllowsAnyPr() {
    assertThat(AdkPrTriagingAgent.isPrAuthorized(99, /* enforce= */ false, ImmutableSet.of()))
        .isTrue();
  }

  @Test
  void isPrAuthorized_enforcementOnRestrictsToAuthorizedSet() {
    Set<Integer> authorized = ImmutableSet.of(7, 8);
    assertThat(AdkPrTriagingAgent.isPrAuthorized(7, /* enforce= */ true, authorized)).isTrue();
    assertThat(AdkPrTriagingAgent.isPrAuthorized(9, /* enforce= */ true, authorized)).isFalse();
  }

  @Test
  void authorizePr_recordsPrAndClearResets() {
    AdkPrTriagingAgent.clearAuthorizedPrs();
    assertThat(AdkPrTriagingAgent.authorizedPrsSnapshot()).isEmpty();

    AdkPrTriagingAgent.authorizePr(42);
    AdkPrTriagingAgent.authorizePr(43);
    assertThat(AdkPrTriagingAgent.authorizedPrsSnapshot()).containsExactly(42, 43);

    AdkPrTriagingAgent.clearAuthorizedPrs();
    assertThat(AdkPrTriagingAgent.authorizedPrsSnapshot()).isEmpty();
  }

  // ---- applyLabel ----

  @Test
  void applyLabel_rejectsUnknownLabel() {
    Map<String, Object> result = AdkPrTriagingAgent.applyLabel(1, "core", /* dryRun= */ false);
    assertThat(result).containsEntry("status", "error");
    assertThat((String) result.get("message")).contains("not an allowed label");
  }

  @Test
  void applyLabel_dryRunDoesNotCallNetwork() {
    Map<String, Object> result = AdkPrTriagingAgent.applyLabel(1, "bug", /* dryRun= */ true);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("dry_run", true);
    assertThat(result).containsEntry("applied_label", "bug");
  }

  // ---- postComment ----

  @Test
  void postComment_rejectsEmptyComment() {
    Map<String, Object> result = AdkPrTriagingAgent.postComment(1, "   ", /* dryRun= */ false);
    assertThat(result).containsEntry("status", "error");
    assertThat((String) result.get("message")).contains("must not be empty");
  }

  @Test
  void postComment_dryRunDoesNotCallNetwork() {
    Map<String, Object> result =
        AdkPrTriagingAgent.postComment(1, "Please link an issue.", /* dryRun= */ true);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("dry_run", true);
    assertThat(result).containsEntry("added_comment", "Please link an issue.");
  }
}
