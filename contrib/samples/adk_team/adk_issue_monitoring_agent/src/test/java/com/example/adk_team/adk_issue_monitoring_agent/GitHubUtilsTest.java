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

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure helpers in {@link GitHubUtils}. */
final class GitHubUtilsTest {

  @Test
  void errorResponse_hasErrorStatusAndMessage() {
    Map<String, Object> response = GitHubUtils.errorResponse("boom");
    assertThat(response).containsEntry("status", "error");
    assertThat(response).containsEntry("message", "boom");
  }

  @Test
  void isSystemicStatus_classifiesFailures() {
    assertThat(GitHubUtils.isSystemicStatus(401)).isTrue();
    assertThat(GitHubUtils.isSystemicStatus(403)).isTrue();
    assertThat(GitHubUtils.isSystemicStatus(429)).isTrue();
    assertThat(GitHubUtils.isSystemicStatus(500)).isTrue();
    assertThat(GitHubUtils.isSystemicStatus(503)).isTrue();
    assertThat(GitHubUtils.isSystemicStatus(404)).isFalse();
    assertThat(GitHubUtils.isSystemicStatus(422)).isFalse();
  }

  @Test
  void hadSystemicFailure_defaultsFalseAfterReset() {
    GitHubUtils.clearSystemicFailureForTesting();
    assertThat(GitHubUtils.hadSystemicFailure()).isFalse();
  }
}
