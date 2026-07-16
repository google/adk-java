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

import org.junit.jupiter.api.Test;

/** Unit tests for the pure prompt builder in {@link AdkStaleAgentRun}. */
final class AdkStaleAgentRunTest {

  @Test
  void buildAuditPrompt_includesIssueNumberAndTool() {
    String prompt = AdkStaleAgentRun.buildAuditPrompt(123);
    assertThat(prompt).contains("#123");
    assertThat(prompt).contains("get_issue_state");
  }
}
