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
package com.example.adk_team.adk_issue_monitoring_agent;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure helpers and authorization guard in {@link AdkMonitoringAgent}. */
final class AdkMonitoringAgentTest {

  private static final String SIGNATURE = "🚨 SPAM ALERT 🚨";

  @Test
  void buildAlertBody_includesSignatureReasonAndCodeFence() {
    String body = AdkMonitoringAgent.buildAlertBody(SIGNATURE, "irrelevant shoe-store link");
    assertThat(body).startsWith(SIGNATURE);
    assertThat(body).contains("@maintainers");
    assertThat(body).contains("irrelevant shoe-store link");
    assertThat(body).contains("```text");
  }

  @Test
  void buildAlertBody_neutralizesBacktickBreakout() {
    String body = AdkMonitoringAgent.buildAlertBody(SIGNATURE, "```malicious``` payload");
    assertThat(body).doesNotContain("```malicious```");
    assertThat(body).contains("'''malicious''' payload");
  }

  // ---- Authorization guard (prompt-injection defense) ----

  @Test
  void authorizeIssues_seedsAndClears() {
    AdkMonitoringAgent.clearAuthorizedIssues();
    assertThat(AdkMonitoringAgent.isIssueAuthorized(5)).isFalse();

    AdkMonitoringAgent.authorizeIssues(List.of(5, 7));
    assertThat(AdkMonitoringAgent.isIssueAuthorized(5)).isTrue();
    assertThat(AdkMonitoringAgent.isIssueAuthorized(7)).isTrue();
    assertThat(AdkMonitoringAgent.isIssueAuthorized(6)).isFalse();
    assertThat(AdkMonitoringAgent.authorizedIssuesSnapshot()).containsExactly(5, 7);

    AdkMonitoringAgent.clearAuthorizedIssues();
    assertThat(AdkMonitoringAgent.isIssueAuthorized(5)).isFalse();
  }

  @Test
  void flagIssueAsSpam_unauthorizedIssue_returnsErrorWithoutSideEffects() {
    // An unauthorized issue is rejected before any network/token access, so this is safe to call
    // with no GITHUB_TOKEN configured. This is the prompt-injection guard.
    AdkMonitoringAgent.clearAuthorizedIssues();
    Map<String, Object> result = AdkMonitoringAgent.flagIssueAsSpam(999, "irrelevant link");
    assertThat(result).containsEntry("status", "error");
    assertThat((String) result.get("message")).contains("not in the set");
  }
}
